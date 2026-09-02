package logic.scene

import model.MmoSceneSession
import neton.database.dsl.*
import neton.logging.Logger
import table.MmoSceneSessionTable

/**
 * `mmo_scene_session` 的访问层。
 *
 * 这张表是 Transfer 鉴权链的最后一环：heartbeat 报上来的 `scene_session_id`
 * 必须在这里查得到、属于发起者的角色、且所在 channel 与 transfer 的 channel 一致。
 */
open class MmoSceneSessionRepository(
    private val log: Logger,
) {

    open suspend fun findById(sessionId: Long): MmoSceneSession? =
        MmoSceneSessionTable.get(sessionId)

    /** 角色当前在场的会话。同一角色同时只应有一条 status=1。 */
    open suspend fun findActiveByRole(roleId: Long): MmoSceneSession? =
        MmoSceneSessionTable.oneWhere {
            and(
                MmoSceneSession::roleId eq roleId,
                MmoSceneSession::status eq 1,
            )
        }

    /** 场景内在场名单。public snapshot 与 presence 事件都读它。 */
    open suspend fun listActiveByScene(sceneRef: String, limit: Int = 200): List<MmoSceneSession> =
        MmoSceneSessionTable.query {
            where {
                and(
                    MmoSceneSession::sceneRef eq sceneRef,
                    MmoSceneSession::status eq 1,
                )
            }
            orderBy(MmoSceneSession::id.asc())
            limitOffset(limit, 0)
        }.list()

    /**
     * 开一条新会话。调用方负责先把该角色的旧会话关掉——这里不隐式关闭，
     * 因为"离开旧场景"要广播 leave 事件，属于业务动作而非数据访问。
     */
    open suspend fun open(
        roleId: Long,
        sceneRef: String,
        channelId: Long,
        sessionEpoch: Long,
        nowMs: Long,
    ): MmoSceneSession {
        val created = MmoSceneSessionTable.insert(
            MmoSceneSession(
                roleId = roleId,
                sceneRef = sceneRef,
                channelId = channelId,
                sessionEpoch = sessionEpoch,
                status = 1,
                lastSeenAt = nowMs,
            ),
        )
        log.info(
            "mmo.scene.session.opened session_id=${created.id} role_id=$roleId " +
                "scene_ref=$sceneRef channel_id=$channelId epoch=$sessionEpoch",
        )
        return created
    }

    /** 关闭会话。返回被关闭的那一行；原本就不在场时返回 null。 */
    open suspend fun close(session: MmoSceneSession, nowMs: Long): MmoSceneSession? {
        if (session.status != 1) return null
        val closed = session.copy(status = 0, lastSeenAt = nowMs)
        MmoSceneSessionTable.update(closed)
        log.info(
            "mmo.scene.session.closed session_id=${session.id} role_id=${session.roleId} " +
                "scene_ref=${session.sceneRef}",
        )
        return closed
    }

    /** heartbeat 续期。只动 `last_seen_at`，不改状态。 */
    open suspend fun touch(session: MmoSceneSession, nowMs: Long) {
        MmoSceneSessionTable.update(session.copy(lastSeenAt = nowMs))
    }
}
