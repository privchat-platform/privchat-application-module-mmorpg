package logic.battle

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logic.MmoErrorCodes
import logic.codec.BattleCodec
import logic.codec.SceneInteractCodec
import logic.codec.SceneMoveCodec
import logic.scene.EnterResult
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 覆盖 spec §15.8 的验收清单：发起 → 提交 → 结算 → 关闭 → 回场景，加上失败补偿与幂等矩阵。 */
class BattleServiceTest {
    private val roles = FakeRoleRepository()
    private val sessions = FakeSessionRepository()
    private val rooms = FakeRoomGateway()
    private val channels = FakeChannelService(rooms)
    private val repo = FakeBattleRepository()
    private var now = 100_000L
    private val scenes = SceneService(
        log = NoopLogger, roles = roles, sessions = sessions, channels = channels,
        sequencer = SceneSequencer(), rooms = rooms, maps = FakeMapRepository(), clock = { now },
    )
    private val battles = BattleTestKit.service(roles, sessions, rooms, channels, repo, clock = { now })
    private val scene = "l-10023-7"

    init { kotlinx.coroutines.runBlocking { channels.provision(SceneRef.parse(scene)!!) } }

    private fun <T> ok(o: SceneOutcome<T>): T = assertIs<SceneOutcome.Success<T>>(o).value
    private fun fail(o: SceneOutcome<*>): SceneOutcome.Failure = assertIs<SceneOutcome.Failure>(o)

    /** 进场景并把权威位置放到怪 NPC 旁边。 */
    private suspend fun enterNextToMonster(): EnterResult {
        val alice = roles.seed(userId = 1, name = "Alice")
        val e = ok(scenes.enter(1, alice.id, scene, "d1"))
        val s = sessions.rows.getValue(e.sceneSessionId)
        val npc = TestMap.MONSTER_NPC
        sessions.rows[s.id] = s.copy(startX = npc.x - 1000, startY = npc.y, targetX = npc.x - 1000, targetY = npc.y)
        rooms.broadcasts.clear()
        return e
    }

    private fun publicEvents(): List<JsonObject> =
        rooms.broadcasts.filter { "mmorpg.battle.public" in it.second }.flatMap { (_, p) -> Json.parseToJsonElement(p).jsonObject["events"]!!.jsonArray.map { it.jsonObject } }
    private fun privateEvents(): List<JsonObject> =
        rooms.transfers.flatMap { t -> Json.parseToJsonElement(t[3] as String).jsonObject["events"]!!.jsonArray.map { it.jsonObject } }
    private fun payloadKeys(events: List<JsonObject>) = events.map { it["payload"]!!.jsonObject.keys.single() }

    private suspend fun snapshot(battleId: Long, roleId: Long = 1) = ok(battles.privateSnapshot(1, battleId, roleId))

    private fun envelope(snap: BattleSnapshot, seq: Int, requestId: String = "r-$seq", payload: BattleCommand? = null): BattleCodec.CommandEnvelope {
        val slot = snap.openSlots.single()
        val target = snap.actors.first { it.side == BattleRules.MONSTER_SIDE && it.alive == 1 }.id
        val cmd = payload ?: BattleCommand.Attack(target)
        return BattleCodec.CommandEnvelope(
            requestId = requestId, battleId = snap.battle.id, roleId = snap.recipientRoleId, actorId = slot.actorId, commandSlotId = slot.id,
            round = snap.battle.round, phase = snap.battle.phase, phaseVersion = snap.battle.phaseVersion, actionSeq = seq,
            payload = cmd, payloadJson = BattleCodec.encodeCommandPayload(cmd).toString(),
        )
    }

    @Test
    fun interactOffersBattleOnlyForNpcsWithAnEncounter() = runTest {
        val e = enterNextToMonster()
        val r = ok(scenes.interact(1, e.channelId, SceneInteractCodec.Request(e.sceneSessionId, "i1", TestMap.MONSTER_NPC.id)))
        assertEquals(listOf("battle", "leave"), r.options)
    }

    @Test
    fun startProvisionsTheBattleAndLocksTheSceneSession() = runTest {
        val e = enterNextToMonster()
        val entry = ok(battles.start(1, 1, scene, TestMap.MONSTER_NPC.id, "d1"))
        assertEquals(BattleService.TRANSITION_READY, entry.status)
        assertTrue(entry.channelId >= 9000 && entry.ticket.isNotEmpty())
        assertEquals(SceneSessionState.IN_BATTLE, sessions.rows.getValue(e.sceneSessionId).state)
        assertEquals(1, channels.battleChannels.size)
        // 公共：CREATED→COMMAND；私有：SlotsOffered。
        assertEquals(listOf("phase_changed"), payloadKeys(publicEvents()))
        assertEquals(listOf("slots_offered"), payloadKeys(privateEvents()))
        assertEquals(listOf(entry.channelId, 1L, BattleService.ROUTE_BATTLE_EVENT), rooms.transfers.single().take(3))
        // server 对定向 transfer 的 request_id 有熵要求:必须是 UUID v4。
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(rooms.transfers.single()[4] as String))
        // 续接拿到同一份。
        assertEquals(entry, ok(battles.transition(1, entry.transitionId)))
        fail(battles.transition(2, entry.transitionId)).let { assertEquals(MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE, it.code) }
        // 战斗中：不能动、不能再打、不能重进。
        val move = SceneMoveCodec.Intent(1, e.sceneSessionId, "m1", 1, SceneMoveCodec.Command.MoveTo(Vec2Fixed(10_000, 10_000)), clientTimeMs = 1)
        assertEquals(MmoErrorCodes.SCENE_STATE_NOT_ALLOWED, fail(scenes.move(1, e.channelId, move)).code)
        assertEquals(MmoErrorCodes.SCENE_STATE_NOT_ALLOWED, fail(battles.start(1, 1, scene, TestMap.MONSTER_NPC.id, "d1")).code)
        assertEquals(MmoErrorCodes.SCENE_STATE_NOT_ALLOWED, fail(scenes.enter(1, 1, scene, "d1")).code)
        // private snapshot 有 slot，public 没有。
        val snap = snapshot(entry.battleId)
        assertEquals(listOf("ATTACK", "DEFEND", "ESCAPE", "WAIT"), Json.parseToJsonElement(snap.openSlots.single().allowedCommands).jsonArray.map { it.jsonPrimitive.content })
        assertTrue(ok(battles.publicSnapshot(entry.battleId)).openSlots.isEmpty())
    }

    @Test
    fun startRefusesOutOfRangeAndNonCombatNpcs() = runTest {
        val e = enterNextToMonster()
        // 驿站老板不在旁边：先撞距离。
        assertEquals(MmoErrorCodes.SCENE_INTERACT_OUT_OF_RANGE, fail(battles.start(1, 1, scene, TestMap.NPC.id, "d1")).code)
        assertEquals(MmoErrorCodes.SCENE_INTERACT_TARGET_NOT_FOUND, fail(battles.start(1, 1, scene, 999, "d1")).code)
        // 挪到驿站老板旁边：他没有遭遇配置，不可战。
        val s = sessions.rows.getValue(e.sceneSessionId)
        sessions.rows[s.id] = s.copy(startX = TestMap.NPC.x, startY = TestMap.NPC.y, targetX = TestMap.NPC.x, targetY = TestMap.NPC.y)
        assertEquals(MmoErrorCodes.SCENE_COMMAND_INVALID, fail(battles.start(1, 1, scene, TestMap.NPC.id, "d1")).code)
    }

    @Test
    fun provisioningFailureCompensatesBackToActive() = runTest {
        val e = enterNextToMonster()
        channels.battleChannelFailure = IllegalStateException("server down")
        assertEquals(MmoErrorCodes.BATTLE_NOT_FOUND, fail(battles.start(1, 1, scene, TestMap.MONSTER_NPC.id, "d1")).code)
        assertEquals(SceneSessionState.ACTIVE, sessions.rows.getValue(e.sceneSessionId).state)
        assertEquals(BattleService.TRANSITION_FAILED, repo.transitions.values.single().status)
        assertEquals("CLOSED", repo.battles.values.single().phase)
        channels.battleChannelFailure = null
        ok(battles.start(1, 1, scene, TestMap.MONSTER_NPC.id, "d1"))
    }

    @Test
    fun submitFollowsTheIdempotencyMatrixAndResolvesWhenEveryoneSubmitted() = runTest {
        val e = enterNextToMonster()
        val entry = ok(battles.start(1, 1, scene, TestMap.MONSTER_NPC.id, "d1"))
        rooms.broadcasts.clear(); rooms.transfers.clear()
        val snap = snapshot(entry.battleId)
        val ack = ok(battles.submit(1, entry.channelId, envelope(snap, 1)))
        assertEquals(1, ack.acceptedActionSeq)
        // 单人单 slot → 全员提交 → 立即结算：公共事件里有先手与伤害，进入第 2 回合。
        val keys = payloadKeys(publicEvents())
        assertTrue("initiative_resolved" in keys && "damage_dealt" in keys, keys.toString())
        assertEquals(2, repo.battles.getValue(entry.battleId).round)
        assertEquals("COMMAND", repo.battles.getValue(entry.battleId).phase)
        assertTrue(payloadKeys(privateEvents()).containsAll(listOf("command_accepted", "slots_offered")))
        // 同 request_id 同载荷 → 回放同一 ACK（即使回合已经翻过去）。
        assertEquals(ack, ok(battles.submit(1, entry.channelId, envelope(snap, 1))))
        // 同 request_id 不同载荷 → 21411；同 seq 新 request_id → 21412。
        assertEquals(MmoErrorCodes.BATTLE_IDEMPOTENCY_KEY_REUSE, fail(battles.submit(1, entry.channelId, envelope(snap, 1, payload = BattleCommand.Defend))).code)
        assertEquals(MmoErrorCodes.BATTLE_ACTION_SEQ_REUSE, fail(battles.submit(1, entry.channelId, envelope(snap, 1, requestId = "other"))).code)
        // 旧回合的 slot / round → 21402（round 判定先于 slot）。
        assertEquals(MmoErrorCodes.BATTLE_ROUND_MISMATCH, fail(battles.submit(1, entry.channelId, envelope(snap, 2))).code)
        val snap2 = snapshot(entry.battleId)
        assertEquals(MmoErrorCodes.BATTLE_COMMAND_NOT_ALLOWED_IN_SLOT, fail(battles.submit(1, entry.channelId, envelope(snap2, 2, payload = BattleCommand.Unsupported(CommandKind.CAST_SKILL)))).code)
        assertEquals(MmoErrorCodes.BATTLE_COMMAND_REJECTED, fail(battles.submit(1, entry.channelId, envelope(snap2, 2, payload = BattleCommand.Attack(999)))).code)
        assertEquals(MmoErrorCodes.BATTLE_ACTOR_NOT_CONTROLLABLE, fail(battles.submit(2, entry.channelId, envelope(snap2, 2))).code)
        assertEquals(MmoErrorCodes.BATTLE_NOT_FOUND, fail(battles.submit(1, e.channelId, envelope(snap2, 2))).code)
    }

    @Test
    fun deadlineAppliesTheDefaultActionAndEventsAreRedeliveredAfterPublishFailure() = runTest {
        val entry = enterNextToMonster().let { ok(battles.start(1, 1, scene, TestMap.MONSTER_NPC.id, "d1")) }
        rooms.broadcasts.clear()
        assertEquals(0, battles.tick(now + 1_000))
        rooms.broadcastFailure = IllegalStateException("room down")
        now += BattleRules.ROUND_MS + 1
        assertEquals(1, battles.tick(now))
        assertTrue(rooms.broadcasts.isEmpty())
        assertTrue(repo.events.values.any { it.visibility == "PUBLIC" && it.publishedAt == 0L })
        rooms.broadcastFailure = null
        battles.tick(now)
        val locked = publicEvents().first { it["payload"]!!.jsonObject.containsKey("phase_changed") }
        assertEquals(true, locked["default_action_applied"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(repo.events.values.none { it.publishedAt == 0L })
    }

    @Test
    fun fightingToTheEndSettlesClosesAndReturnsTheSessionToActive() = runTest {
        val e = enterNextToMonster()
        val entry = ok(battles.start(1, 1, scene, TestMap.MONSTER_NPC.id, "d1"))
        var seq = 0
        while (repo.battles.getValue(entry.battleId).phase == "COMMAND") {
            seq += 1
            ok(battles.submit(1, entry.channelId, envelope(snapshot(entry.battleId), seq)))
            assertTrue(seq < 20, "battle did not end")
        }
        val battle = repo.battles.getValue(entry.battleId)
        assertEquals("SETTLE", battle.phase)
        assertEquals(0, battle.winnerSide)
        val settled = publicEvents().first { it["payload"]!!.jsonObject.containsKey("battle_settled") }["payload"]!!.jsonObject["battle_settled"]!!.jsonObject
        assertEquals("bs-${entry.battleId}", settled["settlement_request_id"]!!.jsonPrimitive.content)
        assertEquals("PENDING", repo.settlements.values.single().status)
        assertEquals(SceneSessionState.BATTLE_EXITING, sessions.rows.getValue(e.sceneSessionId).state)
        // CLOSED 后快照不可见（21400），会话回 ACTIVE，可以移动。
        now += BattleRules.EXIT_GRACE_MS + 1
        assertEquals(1, battles.tick(now))
        assertEquals("CLOSED", repo.battles.getValue(entry.battleId).phase)
        assertEquals(MmoErrorCodes.BATTLE_NOT_FOUND, fail(battles.publicSnapshot(entry.battleId)).code)
        assertEquals(SceneSessionState.ACTIVE, sessions.rows.getValue(e.sceneSessionId).state)
        ok(scenes.move(1, e.channelId, SceneMoveCodec.Intent(1, e.sceneSessionId, "m1", 1, SceneMoveCodec.Command.MoveTo(Vec2Fixed(10_000, 10_000)), clientTimeMs = 1)))
    }

    @Test
    fun reenteringTheSceneWhileExitingIsTheClientSideExitPath() = runTest {
        val e = enterNextToMonster()
        val entry = ok(battles.start(1, 1, scene, TestMap.MONSTER_NPC.id, "d1"))
        val snap = snapshot(entry.battleId)
        ok(battles.instant(1, entry.channelId, BattleCodec.InstantRequest("s1", entry.battleId, 1, snap.battle.stateVersion, BattleCodec.OP_SURRENDER)))
        assertEquals(1, repo.battles.getValue(entry.battleId).winnerSide)
        assertEquals(MmoErrorCodes.BATTLE_STATE_VERSION_CONFLICT, fail(battles.instant(1, entry.channelId, BattleCodec.InstantRequest("s2", entry.battleId, 1, 0, BattleCodec.OP_SURRENDER))).code)
        // 结算后重新 enter：epoch+1、新会话 ACTIVE；旧会话已关，CLOSED 时不会再碰它。
        val again = ok(scenes.enter(1, 1, scene, "d1"))
        assertEquals(e.sessionEpoch + 1, again.sessionEpoch)
        assertEquals(SceneSessionState.ACTIVE, sessions.rows.getValue(again.sceneSessionId).state)
        now += BattleRules.EXIT_GRACE_MS + 1
        battles.tick(now)
        assertEquals(0, sessions.rows.getValue(e.sceneSessionId).status)
        assertNotNull(repo.battles.getValue(entry.battleId).takeIf { it.phase == "CLOSED" })
    }

    @Test
    fun adminAbortEndsWithoutAWinner() = runTest {
        val entry = enterNextToMonster().let { ok(battles.start(1, 1, scene, TestMap.MONSTER_NPC.id, "d1")) }
        ok(battles.abort(entry.battleId, "ops"))
        assertEquals(2, repo.battles.getValue(entry.battleId).winnerSide)
        assertEquals(MmoErrorCodes.BATTLE_NOT_FOUND, fail(battles.abort(999, "ops")).code)
    }
}
