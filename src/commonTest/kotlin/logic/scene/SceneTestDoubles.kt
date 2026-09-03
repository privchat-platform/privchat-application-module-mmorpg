package logic.scene

import model.MmoRole
import model.MmoSceneSession
import neton.logging.Fields
import neton.logging.Logger
import neton.logging.emptyFields

/** 测试用无输出 Logger。断言看的是行为，不是日志。 */
object NoopLogger : Logger {
    override fun trace(msg: String, fields: Fields) = Unit
    override fun debug(msg: String, fields: Fields) = Unit
    override fun info(msg: String, fields: Fields) = Unit
    override fun warn(msg: String, fields: Fields, cause: Throwable?) = Unit
    override fun error(msg: String, fields: Fields, cause: Throwable?) = Unit
}

/**
 * 内存版角色仓储。
 *
 * 覆写全部方法，所以基类那些会打数据库的实现一次都不会被调用——不这样的话，
 * 漏覆写一个方法会在测试里静默走到真实 Table，症状是莫名其妙的连接错误。
 */
class FakeRoleRepository : MmoRoleRepository(NoopLogger) {
    val rows = mutableMapOf<Long, MmoRole>()
    private var nextId = 1L

    fun seed(userId: Long, name: String, status: Int = 1): MmoRole {
        val role = MmoRole(id = nextId++, userId = userId, name = name, status = status)
        rows[role.id] = role
        return role
    }

    override suspend fun findById(roleId: Long): MmoRole? = rows[roleId]

    override suspend fun listByUser(userId: Long): List<MmoRole> =
        rows.values.filter { it.userId == userId && it.status == 1 }.sortedBy { it.id }

    override suspend fun findByName(name: String): MmoRole? = rows.values.firstOrNull { it.name == name }

    override suspend fun create(userId: Long, name: String): MmoRole = seed(userId, name)
}

/** 内存版会话仓储。 */
class FakeSessionRepository : MmoSceneSessionRepository(NoopLogger) {
    val rows = mutableMapOf<Long, MmoSceneSession>()
    private var nextId = 100L

    override suspend fun findById(sessionId: Long): MmoSceneSession? = rows[sessionId]

    override suspend fun findActiveByRole(roleId: Long): MmoSceneSession? =
        rows.values.firstOrNull { it.roleId == roleId && it.status == 1 }

    override suspend fun listActiveByScene(sceneRef: String, limit: Int): List<MmoSceneSession> =
        rows.values.filter { it.sceneRef == sceneRef && it.status == 1 }.sortedBy { it.id }.take(limit)

    override suspend fun open(
        roleId: Long,
        sceneRef: String,
        channelId: Long,
        sessionEpoch: Long,
        nowMs: Long,
    ): MmoSceneSession {
        val s = MmoSceneSession(
            id = nextId++,
            roleId = roleId,
            sceneRef = sceneRef,
            channelId = channelId,
            sessionEpoch = sessionEpoch,
            status = 1,
            lastSeenAt = nowMs,
            startX = SceneMap.SPAWN.x, startY = SceneMap.SPAWN.y,
            targetX = SceneMap.SPAWN.x, targetY = SceneMap.SPAWN.y,
        )
        rows[s.id] = s
        return s
    }

    override suspend fun close(session: MmoSceneSession, nowMs: Long): MmoSceneSession? {
        if (session.status != 1) return null
        val closed = session.copy(status = 0, lastSeenAt = nowMs)
        rows[closed.id] = closed
        return closed
    }

    override suspend fun touch(session: MmoSceneSession, nowMs: Long) {
        rows[session.id] = session.copy(lastSeenAt = nowMs)
    }

    override suspend fun updateMovement(session: MmoSceneSession) {
        rows[session.id] = session
    }
}

/** 记录所有对 server 的调用，供断言"广播确实发了/没发"。 */
class FakeRoomGateway(
    private val channelId: Long = 5000L,
) : SceneRoomGateway {
    val created = mutableListOf<String>()
    val tickets = mutableListOf<Triple<Long, Long, String>>()
    val broadcasts = mutableListOf<Pair<Long, String>>()

    /** 设为非 null 时 [broadcast] 抛出，用来验证广播失败不会拖垮主流程。 */
    var broadcastFailure: Throwable? = null

    /** 设为非 null 时 [issueTicket] 抛出，用来验证签发失败不留下半截状态。 */
    var ticketFailure: Throwable? = null

    override suspend fun createRoom(name: String): Long {
        created += name
        return channelId
    }

    override suspend fun issueTicket(
        channelId: Long,
        userId: Long,
        deviceId: String,
        scope: String,
    ): SceneRoomGateway.Ticket {
        ticketFailure?.let { throw it }
        tickets += Triple(channelId, userId, deviceId)
        return SceneRoomGateway.Ticket("ticket-$channelId-$userId", exp = 1_700_000_000)
    }

    override suspend fun broadcast(channelId: Long, payload: String) {
        broadcastFailure?.let { throw it }
        broadcasts += channelId to payload
    }
}

/**
 * 内存版 channel 解析。基类的两个方法都被覆写，所以那些真实依赖（room gateway、
 * 两张表）在测试里从不被触碰——它们只是构造函数上的占位。
 */
class FakeChannelService(
    rooms: FakeRoomGateway,
) : SceneChannelService(
    log = NoopLogger,
    rooms = rooms,
    businessChannelResolver = transfer.PrivchatBusinessChannelResolver(NoopLogger),
    businessServiceRepository = transfer.PrivchatBusinessServiceRepository(NoopLogger),
) {
    private val byScene = mutableMapOf<String, Long>()
    private var nextChannel = 5000L

    override suspend fun getOrCreate(sceneRef: SceneRef): Long =
        byScene.getOrPut(sceneRef.encode()) { nextChannel++ }

    override suspend fun find(sceneRef: SceneRef): Long? = byScene[sceneRef.encode()]
}
