package logic.scene

import kotlinx.coroutines.test.runTest
import logic.MmoErrorCodes
import logic.codec.SceneInteractCodec
import logic.codec.SceneMoveCodec
import logic.codec.ScenePublicEventCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneMoveTest {

    private val roles = FakeRoleRepository()
    private val sessions = FakeSessionRepository()
    private val rooms = FakeRoomGateway()
    private var now = 10_000L
    private val channels = FakeChannelService(rooms)
    private val service = SceneService(
        log = NoopLogger, roles = roles, sessions = sessions, channels = channels,
        sequencer = SceneSequencer(), rooms = rooms, maps = FakeMapRepository(), clock = { now },
    )
    private val scene = "l-10023-7"

    init {
        kotlinx.coroutines.runBlocking { (service.let { channelsOf() }).provision(SceneRef.parse(scene)!!) }
    }

    private fun channelsOf(): FakeChannelService = channels

    private fun <T> ok(o: SceneOutcome<T>): T = assertIs<SceneOutcome.Success<T>>(o).value
    private fun fail(o: SceneOutcome<*>): SceneOutcome.Failure = assertIs<SceneOutcome.Failure>(o)

    private fun intent(session: Long, seq: Long, command: SceneMoveCodec.Command?, requestId: String = "r-$seq") =
        SceneMoveCodec.Intent(1, session, requestId, seq, command, clientTimeMs = 1)

    private fun moveTo(x: Int, y: Int) = SceneMoveCodec.Command.MoveTo(Vec2Fixed(x, y))

    private suspend fun enterAlice(): EnterResult {
        val alice = roles.seed(userId = 1, name = "Alice")
        return ok(service.enter(1, alice.id, scene, "d1"))
    }

    @Test
    fun acceptsAMoveAndAnnouncesTheAuthoritativePath() = runTest {
        val e = enterAlice()
        rooms.broadcasts.clear()

        val ack = ok(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(20_000, 40_000))))
        assertEquals(1L, ack.acceptedMovementSeq)
        assertFalse(ack.replayed)
        assertTrue(ack.pathId > 0)

        val (_, payload) = rooms.broadcasts.single()
        assertTrue(ScenePublicEventCodec.EVENT_MOVEMENT_STARTED in payload)
        // 权威起点是出生点，不是客户端说了算。
        assertTrue(""""authoritative_start_position":{"x":${TestMap.SPAWN.x},"y":${TestMap.SPAWN.y}}""" in payload, payload)
        assertTrue(""""speed":${logic.map.SceneMap.WALK_SPEED}""" in payload)
        assertTrue(""""start_time_ms":$now""" in payload)
    }

    @Test
    fun aStaleSequenceIsRejectedNotSilentlyDropped() = runTest {
        val e = enterAlice()
        ok(service.move(1, e.channelId, intent(e.sceneSessionId, 5, moveTo(1, 1), "a")))
        // 新 request_id 才能到序号比较；同 request_id 会先撞幂等窗口（21606）。
        val f = fail(service.move(1, e.channelId, intent(e.sceneSessionId, 5, moveTo(2, 2), "b")))
        assertEquals(MmoErrorCodes.SCENE_MOVEMENT_SEQ_STALE, f.code)
        assertEquals(MmoErrorCodes.SCENE_MOVEMENT_SEQ_STALE, fail(service.move(1, e.channelId, intent(e.sceneSessionId, 4, moveTo(2, 2), "c"))).code)
    }

    @Test
    fun aRetryWithTheSameRequestIdReplaysTheFirstAck() = runTest {
        val e = enterAlice()
        val first = ok(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(1, 1), "same")))
        rooms.broadcasts.clear()
        // 重试带同样的 seq：若先比序号就会被当成迟到拒掉——这是 V-I5 必须后判的原因。
        val again = ok(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(1, 1), "same")))
        assertTrue(again.replayed)
        assertEquals(first.pathId, again.pathId)
        assertTrue(rooms.broadcasts.isEmpty(), "a replay must not re-announce the movement")
    }

    @Test
    fun theSameRequestIdWithADifferentIntentIsRefused() = runTest {
        val e = enterAlice()
        ok(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(1, 1), "k")))
        val f = fail(service.move(1, e.channelId, intent(e.sceneSessionId, 2, moveTo(9, 9), "k")))
        assertEquals(MmoErrorCodes.SCENE_IDEMPOTENCY_KEY_REUSE, f.code)
    }

    @Test
    fun aTargetOutsideTheMapIsUnreachable() = runTest {
        val e = enterAlice()
        val f = fail(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(100_001, 0))))
        assertEquals(MmoErrorCodes.SCENE_MOVE_TARGET_UNREACHABLE, f.code)
        // 被拒的意图不占序号：同一 seq 换个合法目标必须能过。
        ok(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(1, 1))))
    }

    @Test
    fun aMissingCommandIsACommandError() = runTest {
        val e = enterAlice()
        assertEquals(MmoErrorCodes.SCENE_COMMAND_INVALID, fail(service.move(1, e.channelId, intent(e.sceneSessionId, 1, null))).code)
    }

    @Test
    fun stopFreezesAtTheServerSidePositionAndTakesASequenceNumber() = runTest {
        val e = enterAlice()
        // Walk left 30 units (the obstacle is on the right); 2 s later we are 10 units in.
        ok(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(TestMap.SPAWN.x - 30_000, TestMap.SPAWN.y))))
        now += 2_000
        val ack = ok(service.move(1, e.channelId, intent(e.sceneSessionId, 2, SceneMoveCodec.Command.Stop)))
        assertEquals(2L, ack.acceptedMovementSeq)
        val s = sessions.rows.getValue(e.sceneSessionId)
        assertEquals(0, s.speed)
        assertEquals(Vec2Fixed(TestMap.SPAWN.x - 10_000, TestMap.SPAWN.y), SceneMovement.positionAt(s, now))
        // Stop 也占用序号：再发一个 seq=2 的新意图（新 request_id）被当成迟到拒掉。
        assertEquals(MmoErrorCodes.SCENE_MOVEMENT_SEQ_STALE, fail(service.move(1, e.channelId, intent(e.sceneSessionId, 2, moveTo(1, 1), "late"))).code)
    }

    @Test
    fun snapshotsCarryPositionsAndTheInFlightPath() = runTest {
        val e = enterAlice()
        ok(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(TestMap.SPAWN.x - 30_000, TestMap.SPAWN.y))))
        now += 3_000
        val snap = ok(service.publicSnapshot(scene))
        val state = snap.roles.single().state
        assertEquals(Vec2Fixed(TestMap.SPAWN.x - 15_000, TestMap.SPAWN.y), state.position)
        assertEquals(1L, state.movementSeq)
        assertEquals(listOf(Vec2Fixed(TestMap.SPAWN.x - 30_000, TestMap.SPAWN.y)), state.movement?.pathPoints)
        now += 10_000
        assertNull(ok(service.publicSnapshot(scene)).roles.single().state.movement, "an arrived path is not in flight")
    }

    @Test
    fun aMoveFromTheWrongChannelOrAccountIsRefused() = runTest {
        val e = enterAlice()
        roles.seed(userId = 2, name = "Bob")
        assertEquals(MmoErrorCodes.SCENE_SESSION_INVALID, fail(service.move(1, e.channelId + 1, intent(e.sceneSessionId, 1, moveTo(1, 1)))).code)
        assertEquals(MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE, fail(service.move(2, e.channelId, intent(e.sceneSessionId, 1, moveTo(1, 1)))).code)
    }

    @Test
    fun aPathAroundAnObstacleHasMoreThanOneSegment() = runTest {
        val e = enterAlice()
        // 障碍在出生点正右方；目标在障碍另一侧同一水平线上，直线必然穿墙。
        val ack = ok(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(90_000, 50_000))))
        val s = sessions.rows.getValue(e.sceneSessionId)
        val path = SceneMovement.decodePath(s.pathPoints)
        assertTrue(path.size >= 2, "expected a detour, got $path")
        assertEquals(Vec2Fixed(90_000, 50_000), path.last())
        assertTrue(ack.pathId > 0)
    }

    @Test
    fun aTargetInsideAnObstacleIsUnreachable() = runTest {
        val e = enterAlice()
        assertEquals(MmoErrorCodes.SCENE_MOVE_TARGET_UNREACHABLE, fail(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(70_000, 52_000)))).code)
    }

    @Test
    fun interactRequiresTheAuthoritativePositionToBeInRange() = runTest {
        val e = enterAlice()
        val far = fail(service.interact(1, e.channelId, SceneInteractCodec.Request(e.sceneSessionId, "i-1", TestMap.NPC.id)))
        assertEquals(MmoErrorCodes.SCENE_INTERACT_OUT_OF_RANGE, far.code)
        // 走到 NPC 旁边（2 单位内），再交互。
        ok(service.move(1, e.channelId, intent(e.sceneSessionId, 1, moveTo(TestMap.NPC.x + 1_500, TestMap.NPC.y))))
        now += 60_000
        val r = ok(service.interact(1, e.channelId, SceneInteractCodec.Request(e.sceneSessionId, "i-2", TestMap.NPC.id)))
        assertEquals("驿站老板", r.name)
        assertTrue(r.dialog.isNotEmpty())
        assertEquals(MmoErrorCodes.SCENE_INTERACT_TARGET_NOT_FOUND, fail(service.interact(1, e.channelId, SceneInteractCodec.Request(e.sceneSessionId, "i-3", 999))).code)
    }
}
