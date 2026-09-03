package logic.transfer

import kotlinx.coroutines.test.runTest
import logic.MmoErrorCodes
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
        body: String,
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
        body = body.encodeToByteArray(),
        receivedAtMs = 0,
    )

    private fun heartbeatBody(sessionId: Long, version: Int = 1) =
        """{"protocol_version":$version,"scene_session_id":$sessionId,""" +
            """"request_id":"r-1","client_time_ms":1}"""

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
        val payload = result.data.decodeToString()
        assertTrue(""""scene_session_id":$sessionId""" in payload)
        assertTrue(""""server_time_ms":12345""" in payload)
    }

    @Test
    fun rejectsAnUnknownRouteInsteadOfSwallowingIt() = runTest {
        // 前缀匹配会把未实装的 route 误吞进已有分支，客户端会以为动作成功了。
        // 用 21610 而不是 21600：客户端收到 21600 会去重建场景，但场景是好的。
        val result = handler.handle(ctx("mmorpg/scene/teleport", "{}"))
        assertEquals(MmoErrorCodes.SCENE_COMMAND_INVALID, result.code)
        assertTrue("mmorpg/scene/teleport" in result.message)
    }

    @Test
    fun acceptsAMoveAndReturnsTheAck() = runTest {
        val (sessionId, channelId) = enterAlice()
        val body = """{"protocol_version":1,"scene_session_id":$sessionId,"request_id":"m-1","movement_seq":1,""" +
            """"command":{"move_to":{"target_position":{"x":1000,"y":2000}}},"client_time_ms":1}"""
        val result = handler.handle(ctx(MmorpgTransferHandler.ROUTE_SCENE_MOVE, body, channelId = channelId))
        assertEquals(0, result.code)
        val payload = result.data.decodeToString()
        assertTrue(""""accepted_movement_seq":1""" in payload, payload)
        assertTrue(""""replayed":false""" in payload, payload)
    }

    @Test
    fun aRejectedMoveCarriesTheCodeOutsideAndNoData() = runTest {
        val (sessionId, channelId) = enterAlice()
        val body = """{"protocol_version":1,"scene_session_id":$sessionId,"request_id":"m-1","movement_seq":1,""" +
            """"command":{"move_to":{"target_position":{"x":-5,"y":0}}}}"""
        val result = handler.handle(ctx(MmorpgTransferHandler.ROUTE_SCENE_MOVE, body, channelId = channelId))
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
        val result = handler.handle(MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT.let { ctx(it, "garbage") })
        assertEquals(MmoErrorCodes.SCENE_PAYLOAD_TOO_LARGE, result.code)
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
