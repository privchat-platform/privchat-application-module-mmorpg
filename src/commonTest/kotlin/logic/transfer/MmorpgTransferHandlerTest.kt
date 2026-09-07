package logic.transfer

import kotlinx.coroutines.test.runTest
import logic.MmoErrorCodes
import logic.codec.SceneFlatCodec
import logic.codec.SceneHeartbeatCodec
import logic.codec.SceneMoveCodec
import logic.codec.SceneMoveFlatCodec
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import transfer.PrivChatTransferContext

class MmorpgTransferHandlerTest {

    private val roles = FakeRoleRepository()
    private val sessions = FakeSessionRepository()
    private val rooms = FakeRoomGateway()
    private val channels = FakeChannelService(rooms).also {
        kotlinx.coroutines.runBlocking { it.provision(SceneRef.parse("l-10023-7")!!) }
    }
    private val scenes = SceneService(
        log = NoopLogger,
        roles = roles,
        sessions = sessions,
        channels = channels,
        sequencer = SceneSequencer(),
        rooms = rooms,
        maps = FakeMapRepository(),
        clock = { 12_345L },
    )
    private val handler = MmorpgTransferHandler(NoopLogger, scenes, battles = logic.battle.BattleTestKit.service(roles, sessions, rooms, channels))

    private fun ctx(
        route: String,
        body: ByteArray,
        userId: Long = 1,
        channelId: Long = 5000,
    ) = PrivChatTransferContext(
        internalRequestId = "int-1",
        clientRequestId = "cli-1",
        traceId = null,
        channelId = channelId,
        roomId = channelId,
        userId = userId,
        serviceId = 9200,
        serviceName = "mmorpg",
        businessRefId = null,
        businessRefType = null,
        route = route,
        body = body,
        receivedAtMs = 0,
    )

    // 线格式只有 FlatBuffers:请求体由同一套 codec 编出(MHR1 / MMI1)。
    private fun heartbeatBody(sessionId: Long, version: Int = 1) =
        SceneFlatCodec.encodeHeartbeat(SceneHeartbeatCodec.Request(version, sessionId, "r-1", 1))

    private fun moveBody(sessionId: Long, x: Int, y: Int) = SceneMoveFlatCodec.encodeIntent(
        SceneMoveCodec.Intent(1, sessionId, "m-1", 1, SceneMoveCodec.Command.MoveTo(logic.scene.Vec2Fixed(x, y)), 1),
    )

    private suspend fun enterAlice(): Pair<Long, Long> {
        val alice = roles.seed(userId = 1, name = "Alice")
        val r = assertIs<SceneOutcome.Success<*>>(
            scenes.enter(1, alice.id, "l-10023-7", "d1"),
        ).value as logic.scene.EnterResult
        return r.sceneSessionId to r.channelId
    }

    @Test
    fun bindsToTheServiceNameNotAServiceId() {
        // dispatcher 走 channel_id → service_id → service.name → registry.find(name)。
        // 这个字符串是代码与 privchat_business_service 那一行的唯一绑定键。
        assertEquals("mmorpg", handler.serviceName)
        assertTrue(MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT.startsWith("${handler.serviceName}/"))
        assertEquals(3, MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT.split('/').size)
    }

    @Test
    fun answersAValidHeartbeat() = runTest {
        val (sessionId, channelId) = enterAlice()

        val result = handler.handle(
            ctx(MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT, heartbeatBody(sessionId), channelId = channelId),
        )

        assertEquals(0, result.code)
        val ack = SceneFlatCodec.decodeHeartbeatAck(result.data)!!
        assertEquals(sessionId, ack.sceneSessionId)
        assertEquals(12345L, ack.serverTimeMs)
    }

    @Test
    fun rejectsAnUnknownRouteInsteadOfSwallowingIt() = runTest {
        // 前缀匹配会把未实装的 route 误吞进已有分支，客户端会以为动作成功了。
        // 用 21610 而不是 21600：客户端收到 21600 会去重建场景，但场景是好的。
        val result = handler.handle(ctx("mmorpg/scene/teleport", ByteArray(0)))
        assertEquals(MmoErrorCodes.SCENE_COMMAND_INVALID, result.code)
        assertTrue("mmorpg/scene/teleport" in result.message)
    }

    @Test
    fun acceptsAMoveAndReturnsTheAck() = runTest {
        val (sessionId, channelId) = enterAlice()
        val result = handler.handle(ctx(MmorpgTransferHandler.ROUTE_SCENE_MOVE, moveBody(sessionId, 1000, 2000), channelId = channelId))
        assertEquals(0, result.code)
        val ack = SceneMoveFlatCodec.decodeAck(result.data)!!
        assertEquals(1L, ack.acceptedMovementSeq)
        assertEquals(false, ack.replayed)
    }

    @Test
    fun aRejectedMoveCarriesTheCodeOutsideAndNoData() = runTest {
        val (sessionId, channelId) = enterAlice()
        val result = handler.handle(ctx(MmorpgTransferHandler.ROUTE_SCENE_MOVE, moveBody(sessionId, -5, 0), channelId = channelId))
        assertEquals(MmoErrorCodes.SCENE_MOVE_TARGET_UNREACHABLE, result.code)
        assertTrue(result.data.isEmpty(), "rejections carry no data (spec 9.1)")
    }

    @Test
    fun mapsAnUnsupportedProtocolVersionToItsOwnCode() = runTest {
        val (sessionId, channelId) = enterAlice()
        val result = handler.handle(
            ctx(
                MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT,
                heartbeatBody(sessionId, version = 99),
                channelId = channelId,
            ),
        )
        // 和"载荷坏了"分开：客户端要能区分"该升级了"和"我发错了"。
        assertEquals(MmoErrorCodes.SCENE_PROTOCOL_VERSION_UNSUPPORTED, result.code)
    }

    @Test
    fun mapsAMalformedBodyToPayloadError() = runTest {
        val result = handler.handle(MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT.let { ctx(it, "garbage".encodeToByteArray()) })
        assertEquals(MmoErrorCodes.SCENE_PAYLOAD_TOO_LARGE, result.code)
        // JSON 属于 HTTP 接口;transfer 上出现 JSON 同样是非法载荷,不再有第二种格式。
        val json = handler.handle(ctx(MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT, """{"protocol_version":1,"scene_session_id":1,"request_id":"r"}""".encodeToByteArray()))
        assertEquals(MmoErrorCodes.SCENE_PAYLOAD_TOO_LARGE, json.code)
    }

    @Test
    fun takesTheChannelFromTheDispatcherNotFromTheBody() = runTest {
        val (sessionId, channelId) = enterAlice()

        // 同一个 session id，但 transfer 来自另一个 channel。body 里写什么都不影响
        // 判定——channelId 只从 dispatcher 解析出的上下文取。
        val result = handler.handle(
            ctx(
                MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT,
                heartbeatBody(sessionId),
                channelId = channelId + 1,
            ),
        )
        assertEquals(MmoErrorCodes.SCENE_SESSION_INVALID, result.code)
    }

    @Test
    fun rejectsAHeartbeatForSomebodyElsesSession() = runTest {
        val (sessionId, channelId) = enterAlice()
        roles.seed(userId = 2, name = "Bob")

        val result = handler.handle(
            ctx(
                MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT,
                heartbeatBody(sessionId),
                userId = 2,
                channelId = channelId,
            ),
        )
        assertEquals(MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE, result.code)
    }
}
