package logic.battle

import kotlinx.coroutines.test.runTest
import logic.MmoErrorCodes
import logic.codec.BattleCodec
import logic.codec.BattleFlatCodec
import logic.codec.SceneMoveCodec
import logic.scene.FakeChannelService
import logic.scene.FakeMapRepository
import logic.scene.FakeRoleRepository
import logic.scene.FakeRoomGateway
import logic.scene.FakeSessionRepository
import logic.scene.NoopLogger
import logic.scene.SceneOutcome
import logic.scene.SceneRef
import logic.scene.SceneSequencer
import logic.scene.SceneService
import logic.scene.SceneSessionState
import logic.scene.TestMap
import logic.scene.Vec2Fixed
import model.MmoBattle
import model.MmoBattleCommand
import model.MmoBattleEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 评审 2026-09-18 的并发缺陷回归:这些场景在仓储层用"版本 / 唯一约束"裁决,
 * 服务层必须把裁决翻译成协议语义,而不是覆盖、也不是 500。
 */
class BattleConcurrencyTest {
    private val roles = FakeRoleRepository()
    private val sessions = FakeSessionRepository()
    private val rooms = FakeRoomGateway()
    private val channels = FakeChannelService(rooms)
    private var now = 100_000L

    /** 可编程的仓储:在指定钩子上模拟"别人先提交了"。 */
    private class RacingRepo : FakeBattleRepository() {
        var bumpVersionOnNextInsertCommand = false
        var throwUniqueOnNextInsertCommand = false
        override suspend fun insertCommand(command: MmoBattleCommand): MmoBattleCommand {
            if (throwUniqueOnNextInsertCommand) {
                throwUniqueOnNextInsertCommand = false
                throw IllegalStateException("[Database] :: [23505] duplicate key value violates unique constraint")
            }
            if (bumpVersionOnNextInsertCommand) {
                bumpVersionOnNextInsertCommand = false
                val b = battles.getValue(command.battleId)
                battles[b.id] = b.copy(stateVersion = b.stateVersion + 1)
            }
            return super.insertCommand(command)
        }
    }

    private val repo = RacingRepo()
    private val scenes = SceneService(
        log = NoopLogger, roles = roles, sessions = sessions, channels = channels,
        sequencer = SceneSequencer(), rooms = rooms, maps = FakeMapRepository(), clock = { now },
    )
    private val battles = BattleTestKit.service(roles, sessions, rooms, channels, repo, clock = { now })
    private val scene = "l-10023-7"

    init { kotlinx.coroutines.runBlocking { channels.provision(SceneRef.parse(scene)!!) } }

    private fun <T> ok(o: SceneOutcome<T>): T = assertIs<SceneOutcome.Success<T>>(o).value
    private fun fail(o: SceneOutcome<*>): SceneOutcome.Failure = assertIs<SceneOutcome.Failure>(o)

    private suspend fun startBattle(): BattleEntry {
        val alice = roles.seed(userId = 1, name = "Alice")
        val e = ok(scenes.enter(1, alice.id, scene, "d1"))
        val s = sessions.rows.getValue(e.sceneSessionId)
        val npc = TestMap.MONSTER_NPC
        sessions.rows[s.id] = s.copy(startX = npc.x - 1000, startY = npc.y, targetX = npc.x - 1000, targetY = npc.y)
        return ok(battles.start(1, 1, scene, npc.id, "d1"))
    }

    private suspend fun envelope(entry: BattleEntry, seq: Int, requestId: String = "r-$seq"): BattleCodec.CommandEnvelope {
        val snap = ok(battles.privateSnapshot(1, entry.battleId, 1))
        val slot = snap.openSlots.single()
        val target = snap.actors.first { it.side == BattleRules.MONSTER_SIDE && it.alive == 1 }.id
        val cmd = BattleCommand.Attack(target)
        return BattleCodec.CommandEnvelope(
            requestId, snap.battle.id, 1, slot.actorId, slot.id, snap.battle.round, snap.battle.phase, snap.battle.phaseVersion, seq,
            cmd, BattleCodec.encodeCommandPayload(cmd).toString(),
        )
    }

    @Test
    fun aStaleTickDoesNotResolveTheRoundTwice() = runTest {
        val entry = startBattle()
        val env = envelope(entry, 1)
        // 调度器读到了到期的战斗(版本 v),但在它结算前玩家提交把版本推到了 v+n。
        val stale = repo.battles.getValue(entry.battleId).copy(deadlineAtMs = now)
        ok(battles.submit(1, entry.channelId, env))
        val afterSubmit = repo.battles.getValue(entry.battleId)
        assertEquals(2, afterSubmit.round, "submit alone resolves round 1")
        // 用过期快照直接走提交路径:乐观锁必须拒绝,回合不能被结算第二次。
        repo.battles[entry.battleId] = afterSubmit
        val conflict = runCatching {
            repo.updateBattleIfVersion(stale.copy(round = 99), expectedStateVersion = stale.stateVersion)
        }.getOrThrow()
        assertEquals(false, conflict)
        assertEquals(2, repo.battles.getValue(entry.battleId).round)
        // 真正的 tick 走一遍也不会重复结算(截止已被推到下一回合)。
        assertEquals(0, battles.tick(now + 1))
        assertEquals(2, repo.battles.getValue(entry.battleId).round)
    }

    @Test
    fun submitRetriesOnceWhenTheBattleMovedUnderIt() = runTest {
        val entry = startBattle()
        val env = envelope(entry, 1)
        repo.bumpVersionOnNextInsertCommand = true
        val ack = ok(battles.submit(1, entry.channelId, env))
        assertEquals(1, ack.acceptedActionSeq)
        // 第一次 commit 撞版本 → 重读 → 第二次成功;指令只落一条。
        assertEquals(1, repo.commands.values.count { it.requestId == env.requestId })
    }

    @Test
    fun aUniqueViolationOnTheRequestIdReplaysTheStoredAck() = runTest {
        val entry = startBattle()
        val env = envelope(entry, 1, "dup")
        val first = ok(battles.submit(1, entry.channelId, env))
        // 模拟并发重试:第二次插入撞唯一索引(事务回滚),服务层按 §4.3 回放首次 ACK。
        repo.throwUniqueOnNextInsertCommand = true
        val env2 = env.copy(round = 2, phaseVersion = repo.battles.getValue(entry.battleId).phaseVersion, actionSeq = 2)
        // 同 request_id、不同载荷 → 21411;同载荷 → 回放。
        val differing = env2.copy(payloadJson = """{"defend":{}}""", payload = BattleCommand.Defend)
        assertEquals(MmoErrorCodes.BATTLE_IDEMPOTENCY_KEY_REUSE, fail(battles.submit(1, entry.channelId, differing)).code)
        repo.throwUniqueOnNextInsertCommand = true
        assertEquals(first, ok(battles.submit(1, entry.channelId, env2)))
    }

    @Test
    fun instantConflictIs21409NotAnOverwrite() = runTest {
        val entry = startBattle()
        val stateVersion = repo.battles.getValue(entry.battleId).stateVersion
        // 玩家拿着旧 state_version 认输,同时战斗已经推进。
        repo.battles[entry.battleId] = repo.battles.getValue(entry.battleId).copy(stateVersion = stateVersion + 1)
        val r = fail(battles.instant(1, entry.channelId, BattleCodec.InstantRequest("s1", entry.battleId, 1, stateVersion, BattleCodec.OP_SURRENDER)))
        assertEquals(MmoErrorCodes.BATTLE_STATE_VERSION_CONFLICT, r.code)
        assertEquals("COMMAND", repo.battles.getValue(entry.battleId).phase)
    }

    @Test
    fun heartbeatDoesNotClobberAMoveAndAStaleMoveLosesAtTheDatabase() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val e = ok(scenes.enter(1, alice.id, scene, "d1"))
        val stale = sessions.rows.getValue(e.sceneSessionId)
        ok(scenes.move(1, e.channelId, SceneMoveCodec.Intent(1, e.sceneSessionId, "m1", 1, SceneMoveCodec.Command.MoveTo(Vec2Fixed(20_000, 40_000)), 1)))
        // 心跳用的是移动前的快照:只改 last_seen_at,不得把路径/序号写回旧值。
        sessions.touch(stale, now + 5)
        val after = sessions.rows.getValue(e.sceneSessionId)
        assertEquals(1L, after.movementSeq)
        assertTrue(after.speed > 0)
        assertEquals(now + 5, after.lastSeenAt)
        // 直接用旧序号写移动列:数据库裁决输,返回 false。
        assertEquals(false, sessions.updateMovement(stale.copy(movementSeq = 1, speed = 0)))
        assertEquals(1L, sessions.rows.getValue(e.sceneSessionId).movementSeq)
    }

    @Test
    fun aMoveRetryIsReplayedEvenAfterTheSessionLeftActive() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val e = ok(scenes.enter(1, alice.id, scene, "d1"))
        val intent = SceneMoveCodec.Intent(1, e.sceneSessionId, "same", 1, SceneMoveCodec.Command.MoveTo(Vec2Fixed(20_000, 40_000)), 1)
        val first = ok(scenes.move(1, e.channelId, intent))
        sessions.updateState(sessions.rows.getValue(e.sceneSessionId), SceneSessionState.BATTLE_ENTERING)
        // 冻结顺序:幂等命中在状态判定之前 → 回放,而不是 21613。
        val again = ok(scenes.move(1, e.channelId, intent))
        assertTrue(again.replayed)
        assertEquals(first.pathId, again.pathId)
    }

    @Test
    fun undecodableOutboxRowsAreDroppedNotFatal() = runTest {
        val entry = startBattle()
        rooms.broadcastBytes.clear()
        // 往 outbox 塞一条编码器不认识的载荷。
        repo.insertEvent(MmoBattleEvent(battleId = entry.battleId, round = 1, visibility = "PUBLIC", streamSeq = 99, stateVersion = 99, serverTimeMs = now, payload = """{"from_the_future":{}}"""))
        battles.tick(now)
        assertTrue(repo.events.values.none { it.publishedAt == 0L }, "the bad row must be retired, not retried forever")
    }

    @Test
    fun criticalIsOnlySetForEventsAClientCannotSkip() = runTest {
        val entry = startBattle()
        ok(battles.submit(1, entry.channelId, envelope(entry, 1)))
        val byKey = repo.events.values.groupBy { it.payload.substringAfter('"').substringBefore('"') }
        assertEquals(1, byKey.getValue("phase_changed").first().critical)
        assertEquals(1, byKey.getValue("slots_offered").first().critical)
        assertEquals(0, byKey.getValue("damage_dealt").first().critical)
        assertEquals(0, byKey.getValue("initiative_resolved").first().critical)
    }

    @Test
    fun largeBacklogsAreChunkedWithinTheBatchLimit() = runTest {
        val entry = startBattle()
        rooms.broadcastBytes.clear()
        repeat(300) { i ->
            repo.insertEvent(MmoBattleEvent(battleId = entry.battleId, round = 1, visibility = "PUBLIC", streamSeq = 1000L + i, stateVersion = 1000L + i, serverTimeMs = now, payload = """{"actor_died":{"actor_id":1}}"""))
        }
        battles.tick(now)
        val batches = rooms.broadcastBytes.map { BattleFlatCodec.decodeEventBatch(it.second)!! }
        assertTrue(batches.all { it["events"]!!.let { e -> (e as kotlinx.serialization.json.JsonArray).size <= BattleFlatCodec.MAX_EVENTS_PER_BATCH } })
        assertEquals(300, batches.sumOf { (it["events"] as kotlinx.serialization.json.JsonArray).size })
    }

    @Test
    fun zombieCreatedBattlesAreClosedAndTheSessionReleased() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val e = ok(scenes.enter(1, alice.id, scene, "d1"))
        val zombie = repo.insertBattle(MmoBattle(sceneRef = scene, sceneSessionId = e.sceneSessionId, roleId = 1, rngSeed = 1, createdAt = now - BattleService.CREATED_GRACE_MS - 1))
        sessions.updateState(sessions.rows.getValue(e.sceneSessionId), SceneSessionState.BATTLE_ENTERING)
        battles.tick(now)
        assertEquals("CLOSED", repo.battles.getValue(zombie.id).phase)
        assertEquals(SceneSessionState.ACTIVE, sessions.rows.getValue(e.sceneSessionId).state)
    }
}
