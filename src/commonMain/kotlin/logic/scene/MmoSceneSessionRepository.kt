package logic.scene

import model.MmoSceneSession
import neton.database.dbContext
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
        spawn: Vec2Fixed,
    ): MmoSceneSession {
        val created = MmoSceneSessionTable.insert(
            MmoSceneSession(
                roleId = roleId,
                sceneRef = sceneRef,
                channelId = channelId,
                sessionEpoch = sessionEpoch,
                status = 1,
                lastSeenAt = nowMs,
                // 出生点来自地图数据。位置由路径推算，静止 = 起点即终点、速度 0。
                startX = spawn.x, startY = spawn.y,
                targetX = spawn.x, targetY = spawn.y,
            ),
        )
        log.info(
            "mmo.scene.session.opened session_id=${created.id} role_id=$roleId " +
                "scene_ref=$sceneRef channel_id=$channelId epoch=$sessionEpoch",
        )
        return created
    }

    // 下面四个写操作都只改**自己那几列**(评审 2026-09-18):心跳、移动、状态迁移会在
    // 同一 RTT 里并发到达同一行,整行 `update(entity)` 会把别人刚写的路径/状态用旧快照
    // 冲掉——玩家被拉回原地、旧序号又能被受理、战斗中还能走路,都是这一个原因。

    /** 关闭会话。返回被关闭的那一行;原本就不在场时返回 null。 */
    open suspend fun close(session: MmoSceneSession, nowMs: Long): MmoSceneSession? {
        if (session.status != 1) return null
        val affected = dbContext().execute(
            "UPDATE mmo_scene_session SET status = 0, last_seen_at = :now, updated_at = :now WHERE id = :id AND status = 1",
            mapOf("now" to nowMs, "id" to session.id),
        )
        if (affected == 0L) return null
        log.info(
            "mmo.scene.session.closed session_id=${session.id} role_id=${session.roleId} " +
                "scene_ref=${session.sceneRef}",
        )
        return session.copy(status = 0, lastSeenAt = nowMs)
    }

    /**
     * 写回一段新的权威移动(路径、序号、版本)。只在行上的 `movement_seq` 仍小于新序号
     * 时生效——两条移动并发通过了服务层的序号比较时,数据库裁决谁赢,输家拿到 false
     * 并按 21605 处理,而不是用旧的起点覆盖新的路径。
     */
    open suspend fun updateMovement(session: MmoSceneSession): Boolean {
        val affected = dbContext().execute(
            """
            UPDATE mmo_scene_session SET
                movement_seq = :seq, entity_version = :version, path_id = :pathId,
                start_x = :sx, start_y = :sy, target_x = :tx, target_y = :ty,
                path_points = :points, path_start_ms = :startMs, speed = :speed,
                last_seen_at = :seen, updated_at = :seen
            WHERE id = :id AND status = 1 AND movement_seq < :seq
            """.trimIndent(),
            mapOf(
                "seq" to session.movementSeq, "version" to session.entityVersion, "pathId" to session.pathId,
                "sx" to session.startX, "sy" to session.startY, "tx" to session.targetX, "ty" to session.targetY,
                "points" to session.pathPoints, "startMs" to session.pathStartMs, "speed" to session.speed,
                "seen" to session.lastSeenAt, "id" to session.id,
            ),
        )
        return affected == 1L
    }

    /** 场景 ↔ 战斗切换的状态迁移(§15.2)。只改 `state`,返回写回后的行。 */
    open suspend fun updateState(session: MmoSceneSession, state: String): MmoSceneSession {
        dbContext().execute(
            "UPDATE mmo_scene_session SET state = :state, updated_at = :now WHERE id = :id",
            mapOf("state" to state, "now" to kotlin.time.Clock.System.now().toEpochMilliseconds(), "id" to session.id),
        )
        return session.copy(state = state)
    }

    /** heartbeat 续期。只动 `last_seen_at`。 */
    open suspend fun touch(session: MmoSceneSession, nowMs: Long) {
        dbContext().execute(
            "UPDATE mmo_scene_session SET last_seen_at = :now, updated_at = :now WHERE id = :id",
            mapOf("now" to nowMs, "id" to session.id),
        )
    }
}
