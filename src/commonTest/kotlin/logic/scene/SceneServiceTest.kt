package logic.scene

import kotlinx.coroutines.test.runTest
import logic.MmoErrorCodes
import logic.codec.ScenePublicEventCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SceneServiceTest {

    private val roles = FakeRoleRepository()
    private val sessions = FakeSessionRepository()
    private val rooms = FakeRoomGateway()
    private val channels = FakeChannelService(rooms)
    private val sequencer = SceneSequencer()
    private var now = 1_000L

    private val service = SceneService(
        log = NoopLogger,
        roles = roles,
        sessions = sessions,
        channels = channels,
        sequencer = sequencer,
        rooms = rooms,
        maps = FakeMapRepository(),
        clock = { now },
    )

    private val scene = "l-10023-7"

    init {
        // 场景由后台开好；测试模拟这一步。
        kotlinx.coroutines.runBlocking { channels.provision(SceneRef.parse(scene)!!); channels.provision(SceneRef.parse("l-10024-1")!!) }
    }

    private fun <T> ok(o: SceneOutcome<T>): T = assertIs<SceneOutcome.Success<T>>(o).value
    private fun fail(o: SceneOutcome<*>): SceneOutcome.Failure = assertIs<SceneOutcome.Failure>(o)

    // ---------------- enter ----------------

    @Test
    fun enterOpensASessionIssuesATicketAndAnnouncesArrival() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")

        val r = ok(service.enter(userId = 1, roleId = alice.id, rawSceneRef = scene, deviceId = "d1"))

        assertEquals(scene, r.sceneRef.encode())
        assertTrue(r.sceneSessionId > 0)
        assertEquals(1L, r.sessionEpoch)
        assertEquals(r.channelId, rooms.tickets.single().first)
        assertEquals(1L, rooms.tickets.single().second)

        val (broadcastChannel, payload) = rooms.broadcasts.single()
        assertEquals(r.channelId, broadcastChannel)
        assertTrue(ScenePublicEventCodec.EVENT_ROLE_ENTERED in payload)
        assertTrue(""""role_name":"Alice"""" in payload)
    }

    @Test
    fun enterRejectsARoleThatBelongsToSomebodyElse() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        // 用户 2 拿着用户 1 的 role_id 进场景——只是改一个请求体字段的事。
        val f = fail(service.enter(userId = 2, roleId = alice.id, rawSceneRef = scene, deviceId = "d"))
        assertEquals(MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE, f.code)
        assertTrue(sessions.rows.isEmpty())
    }

    @Test
    fun enterRefusesAnUnprovisionedSceneInsteadOfCreatingIt() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        // 任何持有 token 的人都能 enter；让 enter 建 Room 等于让玩家凭空造场景。
        val f = fail(service.enter(1, alice.id, "l-999-1", "d1"))
        assertEquals(MmoErrorCodes.SCENE_NOT_FOUND, f.code)
        assertTrue(rooms.created.isEmpty(), "enter must not create rooms")
        assertTrue(sessions.rows.isEmpty())
    }

    @Test
    fun enterRejectsAMalformedSceneRef() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val f = fail(service.enter(1, alice.id, "not-a-scene", "d"))
        assertEquals(MmoErrorCodes.SCENE_GENERATION_MISMATCH, f.code)
    }

    @Test
    fun reEnteringTheSameSceneClosesTheOldSessionAndBumpsTheEpoch() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val first = ok(service.enter(1, alice.id, scene, "d1"))

        now = 2_000
        val second = ok(service.enter(1, alice.id, scene, "d1"))

        // 旧会话必须失效，否则断线重连后场景里会留下一个不动的幽灵，
        // 而两条会话都能通过心跳校验。
        assertEquals(0, sessions.rows.getValue(first.sceneSessionId).status)
        assertEquals(2L, second.sessionEpoch)
        assertEquals(
            MmoErrorCodes.SCENE_SESSION_INVALID,
            fail(service.heartbeat(1, first.channelId, first.sceneSessionId)).code,
        )
    }

    @Test
    fun movingToAnotherSceneAnnouncesDepartureOnTheOldChannel() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val first = ok(service.enter(1, alice.id, scene, "d1"))
        rooms.broadcasts.clear()

        val second = ok(service.enter(1, alice.id, "l-10024-1", "d1"))

        val left = rooms.broadcasts.first { ScenePublicEventCodec.EVENT_ROLE_LEFT in it.second }
        val entered = rooms.broadcasts.first { ScenePublicEventCodec.EVENT_ROLE_ENTERED in it.second }
        // leave 必须发在**旧** channel 上：发到新 channel 的话，旧场景里的人永远
        // 不知道他走了。
        assertEquals(first.channelId, left.first)
        assertEquals(second.channelId, entered.first)
        // 换场景是新会话，epoch 从 1 起算。
        assertEquals(1L, second.sessionEpoch)
    }

    @Test
    fun aFailedTicketLeavesNoHalfEnteredState() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        ok(service.enter(1, alice.id, scene, "d1"))
        val firstSession = sessions.rows.values.single { it.status == 1 }

        rooms.ticketFailure = IllegalStateException("server rejected the scope")
        runCatching { service.enter(1, alice.id, scene, "d1") }

        // 旧会话必须原封不动。签发失败后把它关掉的话，玩家既退出了原场景，
        // 又拿不到新场景的订阅权——两头落空。
        assertEquals(firstSession, sessions.rows.getValue(firstSession.id))
        assertEquals(1, sessions.rows.values.count { it.status == 1 })
    }

    @Test
    fun aFailedPresenceBroadcastDoesNotBlockTheEnter() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        rooms.broadcastFailure = IllegalStateException("server unreachable")

        // 玩家已经在场景里了。为一条广播把 enter 回滚，会把他卡在场景外。
        val r = ok(service.enter(1, alice.id, scene, "d1"))
        assertEquals(1, sessions.rows.values.count { it.status == 1 })
        assertTrue(r.ticket.isNotEmpty())
    }

    // ---------------- heartbeat ----------------

    @Test
    fun heartbeatAcceptsTheSessionThatOwnsTheChannel() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val entered = ok(service.enter(1, alice.id, scene, "d1"))

        now = 9_999
        val hb = ok(service.heartbeat(1, entered.channelId, entered.sceneSessionId))

        assertEquals(entered.sceneSessionId, hb.sceneSessionId)
        assertEquals(9_999L, hb.serverTimeMs)
        assertEquals(9_999L, sessions.rows.getValue(entered.sceneSessionId).lastSeenAt)
        // enter 广播过一次，所以序号是 1。客户端拿它和收到的广播对账。
        assertEquals(1L, hb.publicSceneSeq)
    }

    @Test
    fun heartbeatRejectsASessionSentFromADifferentChannel() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val entered = ok(service.enter(1, alice.id, scene, "d1"))

        // 少了这条校验，任何持有合法 session id 的人可以从**任意**已订阅的
        // channel 发心跳，channel 边界形同虚设。
        val f = fail(service.heartbeat(1, entered.channelId + 1, entered.sceneSessionId))
        assertEquals(MmoErrorCodes.SCENE_SESSION_INVALID, f.code)
    }

    @Test
    fun heartbeatRejectsASessionOwnedByAnotherAccount() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val entered = ok(service.enter(1, alice.id, scene, "d1"))
        roles.seed(userId = 2, name = "Bob")

        // Bob 订阅了同一个场景 channel（合法），拿 Alice 的 session id 发心跳。
        val f = fail(service.heartbeat(2, entered.channelId, entered.sceneSessionId))
        assertEquals(MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE, f.code)
    }

    @Test
    fun heartbeatRejectsAnUnknownSession() = runTest {
        assertEquals(
            MmoErrorCodes.SCENE_SESSION_INVALID,
            fail(service.heartbeat(1, 5000, 999_999)).code,
        )
    }

    // ---------------- leave ----------------

    @Test
    fun leaveClosesTheSessionAndAnnouncesDeparture() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val entered = ok(service.enter(1, alice.id, scene, "d1"))
        rooms.broadcasts.clear()

        ok(service.leave(1, alice.id, scene))

        assertEquals(0, sessions.rows.getValue(entered.sceneSessionId).status)
        assertTrue(ScenePublicEventCodec.EVENT_ROLE_LEFT in rooms.broadcasts.single().second)
    }

    @Test
    fun leaveIsIdempotent() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        ok(service.enter(1, alice.id, scene, "d1"))
        ok(service.leave(1, alice.id, scene))
        // 客户端重发 leave 是常态（网络重试、退出流程走两遍）。
        ok(service.leave(1, alice.id, scene))
    }

    @Test
    fun leaveRefusesToPretendWhenTheRoleIsElsewhere() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        ok(service.enter(1, alice.id, scene, "d1"))

        // 返回成功会让客户端以为自己退出了它其实还在的那个场景。
        val f = fail(service.leave(1, alice.id, "l-10024-1"))
        assertEquals(MmoErrorCodes.SCENE_SESSION_INVALID, f.code)
        assertEquals(1, sessions.rows.values.count { it.status == 1 })
    }

    // ---------------- snapshot ----------------

    @Test
    fun publicSnapshotListsEveryoneWhoIsPresent() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val bob = roles.seed(userId = 2, name = "Bob")
        ok(service.enter(1, alice.id, scene, "d1"))
        ok(service.enter(2, bob.id, scene, "d2"))

        val snap = ok(service.publicSnapshot(scene))
        assertEquals(listOf("Alice", "Bob"), snap.roles.map { it.roleName })

        ok(service.leave(1, alice.id, scene))
        assertEquals(listOf("Bob"), ok(service.publicSnapshot(scene)).roles.map { it.roleName })
    }

    @Test
    fun publicSnapshotOfAnUnopenedSceneIsNotFound() = runTest {
        assertEquals(MmoErrorCodes.SCENE_NOT_FOUND, fail(service.publicSnapshot("l-999-1")).code)
    }

    @Test
    fun privateSnapshotGivesTheClientWhatItNeedsToResumeAfterAReconnect() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        val entered = ok(service.enter(1, alice.id, scene, "d1"))

        val snap = ok(service.privateSnapshot(1, alice.id, scene))

        assertEquals(entered.sceneSessionId, snap.sceneSessionId)
        assertEquals(entered.channelId, snap.channelId)
        assertEquals(entered.sessionEpoch, snap.sessionEpoch)
    }

    @Test
    fun privateSnapshotIsNotReadableByAnotherAccount() = runTest {
        val alice = roles.seed(userId = 1, name = "Alice")
        ok(service.enter(1, alice.id, scene, "d1"))
        roles.seed(userId = 2, name = "Bob")

        assertEquals(
            MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE,
            fail(service.privateSnapshot(2, alice.id, scene)).code,
        )
    }
}
