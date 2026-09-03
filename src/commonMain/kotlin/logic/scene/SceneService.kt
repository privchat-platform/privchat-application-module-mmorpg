package logic.scene

import kotlin.time.Clock
import logic.MmoErrorCodes
import logic.codec.SceneMoveCodec
import logic.codec.ScenePublicEventCodec
import logic.codec.encodeMovementStarted
import model.MmoRole
import model.MmoSceneSession
import neton.logging.Logger

/**
 * 场景生命周期：进入 / 离开 / 心跳 / 快照。
 *
 * ### 为什么所有校验都在服务端重做
 *
 * 客户端上行只带 `scene_session_id`，其余身份（哪个账号、哪个角色、哪个场景、
 * 哪个 channel）全部由服务端从会话反查。任何一环改成"信客户端自报"，都等于把
 * 越权改成一次普通的字段篡改。
 *
 * ### 结果用 [SceneOutcome] 而不是异常表达
 *
 * 同一段逻辑要同时服务 HTTP Controller 和 Transfer handler，两者的错误出口不同
 * （HTTP 状态码 vs `TransferResponse.code`）。用异常会迫使其中一方去 catch 具体
 * 异常类型再翻译，而漏 catch 的代价是 500。返回值形式让编译器帮忙盯着分支。
 */
class SceneService(
    private val log: Logger,
    private val roles: MmoRoleRepository,
    private val sessions: MmoSceneSessionRepository,
    private val channels: SceneChannelService,
    private val sequencer: SceneSequencer,
    private val rooms: SceneRoomGateway,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val idempotency: SceneMoveIdempotency = SceneMoveIdempotency(),
) {

    // ---------------- 进入场景 ----------------

    /**
     * 角色进入场景。
     *
     * 已在别处（或同一场景断线重连）时先关掉旧会话并广播 leave，再开新会话。
     * 不这么做的话，"重连"会在场景里留下一个永远不动的幽灵角色，而且两条会话
     * 都能通过 heartbeat 校验，等于同一个角色有两份权威状态。
     */
    suspend fun enter(
        userId: Long,
        roleId: Long,
        rawSceneRef: String,
        deviceId: String,
    ): SceneOutcome<EnterResult> {
        val sceneRef = SceneRef.parse(rawSceneRef)
            ?: return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_GENERATION_MISMATCH,
                "malformed scene_ref: '$rawSceneRef'",
            )
        val role = requireOwnedRole(userId, roleId) ?: return notControllable(userId, roleId)

        // 场景由后台开好；玩家进不存在的场景是错误，不是建场景的时机。
        val channelId = channels.find(sceneRef) ?: return SceneOutcome.Failure(
            MmoErrorCodes.SCENE_NOT_FOUND,
            "scene ${sceneRef.encode()} is not open; provision it from the admin console",
        )
        val now = nowMs()

        // ticket 先签。它只依赖 channel/user/device，不依赖会话状态，所以可以放在
        // 任何状态变更之前——一旦签发失败（server 不可达、参数被拒），此时还没有
        // 关掉旧会话、也没有开新会话，玩家停在原地重试即可。反过来先开会话的话，
        // 一次签发失败会留下一个"在场但拿不到订阅权"的角色：他自己收不到广播，
        // 别人却在名单里看得见他。
        val ticket = rooms.issueTicket(
            channelId = channelId,
            userId = userId,
            deviceId = deviceId,
            scope = TICKET_SCOPE,
        )

        // 旧会话的 epoch 决定新 epoch：递增而非从 1 开始，否则重进后旧 heartbeat
        // 会和新会话撞上同一个 epoch，失效判定就失灵了。
        val previous = sessions.findActiveByRole(roleId)
        var nextEpoch = 1L
        if (previous != null) {
            sessions.close(previous, now)
            broadcastPresence(
                ScenePublicEventCodec.EVENT_ROLE_LEFT,
                previous.sceneRef,
                previous.channelId,
                role,
                now,
            )
            if (previous.sceneRef == sceneRef.encode()) nextEpoch = previous.sessionEpoch + 1
        }

        val session = sessions.open(
            roleId = roleId,
            sceneRef = sceneRef.encode(),
            channelId = channelId,
            sessionEpoch = nextEpoch,
            nowMs = now,
        )

        broadcastPresence(
            ScenePublicEventCodec.EVENT_ROLE_ENTERED,
            sceneRef.encode(),
            channelId,
            role,
            now,
        )

        return SceneOutcome.Success(
            EnterResult(
                sceneRef = sceneRef,
                channelId = channelId,
                sceneSessionId = session.id,
                sessionEpoch = session.sessionEpoch,
                ticket = ticket.ticket,
                ticketExp = ticket.exp,
            ),
        )
    }

    // ---------------- 离开场景 ----------------

    /** 幂等：已经不在场时返回成功，不报错。客户端重发 leave 是常态。 */
    suspend fun leave(userId: Long, roleId: Long, rawSceneRef: String): SceneOutcome<Unit> {
        val sceneRef = SceneRef.parse(rawSceneRef)
            ?: return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_GENERATION_MISMATCH,
                "malformed scene_ref: '$rawSceneRef'",
            )
        val role = requireOwnedRole(userId, roleId) ?: return notControllable(userId, roleId)

        val session = sessions.findActiveByRole(roleId)
            ?: return SceneOutcome.Success(Unit)
        if (session.sceneRef != sceneRef.encode()) {
            // 角色在别的场景里。把这当成功会让客户端以为自己退出了它其实还在的
            // 那个场景，之后的 heartbeat 全部对不上。
            return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_SESSION_INVALID,
                "role $roleId is in ${session.sceneRef}, not ${sceneRef.encode()}",
            )
        }

        val now = nowMs()
        sessions.close(session, now)
        broadcastPresence(
            ScenePublicEventCodec.EVENT_ROLE_LEFT,
            session.sceneRef,
            session.channelId,
            role,
            now,
        )
        return SceneOutcome.Success(Unit)
    }

    // ---------------- 心跳 ----------------

    /**
     * heartbeat 的校验链，顺序不能变：
     *
     * 1. 会话存在
     * 2. 会话仍在场（`status = 1`）
     * 3. 会话的 channel == 这条 transfer 实际所在的 channel
     * 4. 会话的角色属于发起 transfer 的账号
     *
     * 第 3 步是最容易被省掉、也最关键的一步：没有它，任何持有合法 session id 的
     * 人可以从**任意**已订阅的 channel 上发心跳，channel 边界形同虚设。
     */
    suspend fun heartbeat(
        userId: Long,
        channelId: Long,
        sceneSessionId: Long,
    ): SceneOutcome<HeartbeatResult> {
        val session = sessions.findById(sceneSessionId)
        if (session == null || session.status != 1) {
            return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_SESSION_INVALID,
                "scene session $sceneSessionId is unknown or already closed",
            )
        }
        if (session.channelId != channelId) {
            log.warn(
                "mmo.scene.heartbeat.channel_mismatch session_id=$sceneSessionId " +
                    "session_channel=${session.channelId} transfer_channel=$channelId user_id=$userId",
            )
            return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_SESSION_INVALID,
                "scene session $sceneSessionId does not belong to channel $channelId",
            )
        }
        val role = roles.findById(session.roleId)
        if (role == null || role.userId != userId || role.status != 1) {
            log.warn(
                "mmo.scene.heartbeat.role_mismatch session_id=$sceneSessionId " +
                    "role_id=${session.roleId} user_id=$userId",
            )
            return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE,
                "role ${session.roleId} is not controllable by user $userId",
            )
        }

        val now = nowMs()
        sessions.touch(session, now)
        return SceneOutcome.Success(
            HeartbeatResult(
                sceneSessionId = session.id,
                serverTimeMs = now,
                publicSceneSeq = sequencer.current(session.sceneRef),
            ),
        )
    }

    // ---------------- 移动 ----------------

    /**
     * 移动意图（spec §4.1、幂等矩阵 §4.1.1、校验规则 V-I1..V-I5）。
     *
     * 顺序是契约的一部分：
     *   身份链（同心跳）→ 幂等窗口 → 序号 → command 语义 → 边界 → 受理。
     * 幂等命中必须早于序号比较，否则合法重试会被当成乱序迟到拒掉。
     *
     * 受理即快速 ACK；`MovementStarted` 作为公共事件异步广播（§4.1）。
     */
    suspend fun move(
        userId: Long,
        channelId: Long,
        intent: SceneMoveCodec.Intent,
    ): SceneOutcome<SceneMoveCodec.Ack> {
        val session = sessions.findById(intent.sceneSessionId)
        if (session == null || session.status != 1 || session.channelId != channelId) {
            return SceneOutcome.Failure(MmoErrorCodes.SCENE_SESSION_INVALID, "scene session ${intent.sceneSessionId} is not valid on channel $channelId")
        }
        val role = roles.findById(session.roleId)
        if (role == null || role.userId != userId || role.status != 1) {
            return notControllable(userId, session.roleId)
        }
        val now = nowMs()

        when (val hit = idempotency.lookup(session.id, intent, now)) {
            is SceneMoveIdempotency.Lookup.Replay -> return SceneOutcome.Success(hit.ack)
            SceneMoveIdempotency.Lookup.Conflict -> return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_IDEMPOTENCY_KEY_REUSE, "request_id '${intent.requestId}' was already used with a different intent",
            )
            SceneMoveIdempotency.Lookup.Miss -> Unit
        }
        if (intent.movementSeq <= session.movementSeq) {
            return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_MOVEMENT_SEQ_STALE, "movement_seq ${intent.movementSeq} is not above accepted ${session.movementSeq}",
            )
        }
        val command = intent.command
            ?: return SceneOutcome.Failure(MmoErrorCodes.SCENE_COMMAND_INVALID, "move intent carries no command")

        // 权威起点永远是服务端此刻推算的位置，不是客户端自报的。
        val here = SceneMovement.positionAt(session, now)
        val target: Vec2Fixed = when (command) {
            is SceneMoveCodec.Command.MoveTo -> {
                if (!SceneMap.contains(command.target)) {
                    return SceneOutcome.Failure(MmoErrorCodes.SCENE_MOVE_TARGET_UNREACHABLE, "target ${command.target} is outside the map")
                }
                command.target
            }
            SceneMoveCodec.Command.Stop -> here
            // 只取消指定的那条路径；不是当前路径就什么都不动（但序号照样推进）。
            is SceneMoveCodec.Command.CancelPath -> if (command.pathId == session.pathId) here else Vec2Fixed(session.targetX, session.targetY)
        }
        val path = SceneMap.pathTo(here, target)
        val moving = path.isNotEmpty()
        val updated = session.copy(
            movementSeq = intent.movementSeq,
            entityVersion = session.entityVersion + 1,
            pathId = session.pathId + 1,
            startX = here.x, startY = here.y,
            targetX = target.x, targetY = target.y,
            pathStartMs = now,
            speed = if (moving) SceneMap.WALK_SPEED else 0,
            lastSeenAt = now,
        )
        sessions.updateMovement(updated)
        val ack = SceneMoveCodec.Ack(
            sceneSessionId = session.id,
            requestId = intent.requestId,
            acceptedMovementSeq = intent.movementSeq,
            entityVersion = updated.entityVersion,
            replayed = false,
            pathId = updated.pathId,
        )
        idempotency.remember(session.id, intent, ack, now)

        val seq = sequencer.next(session.sceneRef)
        val payload = ScenePublicEventCodec.encodeMovementStarted(
            sceneRef = session.sceneRef, seq = seq, entityId = role.id,
            movementSeq = updated.movementSeq, entityVersion = updated.entityVersion, pathId = updated.pathId,
            start = here, pathPoints = path, startTimeMs = now, speed = updated.speed,
            navigationVersion = SceneMap.NAVIGATION_VERSION, serverTimeMs = now,
        )
        runCatching { rooms.broadcast(session.channelId, payload) }.onFailure {
            log.warn("mmo.scene.movement.broadcast_failed scene_ref=${session.sceneRef} role_id=${role.id} err=${it.message}")
        }
        return SceneOutcome.Success(ack)
    }

    // ---------------- 快照 ----------------

    /** 场景内的在场名单。任何能看到该场景的人都可以读。 */
    suspend fun publicSnapshot(rawSceneRef: String): SceneOutcome<PublicSnapshot> {
        val sceneRef = SceneRef.parse(rawSceneRef)
            ?: return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_GENERATION_MISMATCH,
                "malformed scene_ref: '$rawSceneRef'",
            )
        val encoded = sceneRef.encode()
        channels.find(sceneRef) ?: return SceneOutcome.Failure(
            MmoErrorCodes.SCENE_NOT_FOUND,
            "scene $encoded has not been opened",
        )

        val now = nowMs()
        val present = sessions.listActiveByScene(encoded)
        val named = present.mapNotNull { s ->
            roles.findById(s.roleId)?.let { PresentRole(it.id, it.name, entityStateOf(s, now)) }
        }
        return SceneOutcome.Success(
            PublicSnapshot(sceneRef, sequencer.current(encoded), named, now),
        )
    }

    /**
     * 角色自己的场景状态。**断线重连的恢复入口**：客户端重连后先拉这个，
     * 拿回 `scene_session_id` 与序号基线，再决定是续用还是重新 enter。
     */
    suspend fun privateSnapshot(
        userId: Long,
        roleId: Long,
        rawSceneRef: String,
    ): SceneOutcome<PrivateSnapshot> {
        val sceneRef = SceneRef.parse(rawSceneRef)
            ?: return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_GENERATION_MISMATCH,
                "malformed scene_ref: '$rawSceneRef'",
            )
        requireOwnedRole(userId, roleId) ?: return notControllable(userId, roleId)

        val session = sessions.findActiveByRole(roleId)
        if (session == null || session.sceneRef != sceneRef.encode()) {
            return SceneOutcome.Failure(
                MmoErrorCodes.SCENE_SESSION_INVALID,
                "role $roleId has no active session in ${sceneRef.encode()}",
            )
        }
        return SceneOutcome.Success(
            PrivateSnapshot(
                sceneRef = sceneRef,
                roleId = roleId,
                sceneSessionId = session.id,
                sessionEpoch = session.sessionEpoch,
                channelId = session.channelId,
                lastSeenAt = session.lastSeenAt,
                publicSceneSeq = sequencer.current(session.sceneRef),
                self = entityStateOf(session, nowMs()),
            ),
        )
    }

    // ---------------- 内部 ----------------

    private fun entityStateOf(s: MmoSceneSession, now: Long): EntityState {
        val arrived = SceneMovement.arrivalMs(s) <= now
        return EntityState(
            entityId = s.roleId,
            entityVersion = s.entityVersion,
            movementSeq = s.movementSeq,
            position = SceneMovement.positionAt(s, now),
            // 在途路径随快照下发，迟到的订阅者据此本地插值而不必等下一次事件。
            movement = if (s.speed > 0 && !arrived) MovementInProgress(
                pathId = s.pathId,
                start = Vec2Fixed(s.startX, s.startY),
                pathPoints = listOf(Vec2Fixed(s.targetX, s.targetY)),
                startTimeMs = s.pathStartMs,
                speed = s.speed,
            ) else null,
        )
    }

    private suspend fun requireOwnedRole(userId: Long, roleId: Long): MmoRole? =
        roles.findById(roleId)?.takeIf { it.userId == userId && it.status == 1 }

    private fun <T> notControllable(userId: Long, roleId: Long): SceneOutcome<T> {
        log.warn("mmo.scene.role_not_controllable user_id=$userId role_id=$roleId")
        return SceneOutcome.Failure(
            MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE,
            "role $roleId is not controllable by user $userId",
        )
    }

    /**
     * presence 广播失败不影响主流程：玩家已经进/出场了，一次广播丢失让别人晚一点
     * 从 snapshot 看到他，而把整个 enter 回滚掉会让玩家卡在场景外。
     */
    private suspend fun broadcastPresence(
        event: String,
        sceneRef: String,
        channelId: Long,
        role: MmoRole,
        nowMs: Long,
    ) {
        val seq = sequencer.next(sceneRef)
        val payload = ScenePublicEventCodec.encodePresence(
            event = event,
            sceneRef = sceneRef,
            seq = seq,
            roleId = role.id,
            roleName = role.name,
            serverTimeMs = nowMs,
        )
        runCatching { rooms.broadcast(channelId, payload) }
            .onFailure {
                log.warn(
                    "mmo.scene.presence.broadcast_failed event=$event scene_ref=$sceneRef " +
                        "channel_id=$channelId role_id=${role.id} err=${it.message}",
                )
            }
    }

    // 时钟注入：测试要断言 last_seen_at 被推进，而不是断言"两次真实取时不相等"。
    private fun nowMs(): Long = clock()

    companion object {
        /**
         * Room ticket 的 scope。server 端只接受 `"subscribe"`，其它值一律 400
         * ——这是个封闭枚举，不是给业务侧打标记用的自由字段。
         */
        const val TICKET_SCOPE: String = "subscribe"
    }
}

/** 服务层结果。失败携带 spec 段位内的错误码，两个调用方各自翻译成自己的出口格式。 */
sealed interface SceneOutcome<out T> {
    data class Success<T>(val value: T) : SceneOutcome<T>
    data class Failure(val code: Int, val message: String) : SceneOutcome<Nothing>
}

data class EnterResult(
    val sceneRef: SceneRef,
    val channelId: Long,
    val sceneSessionId: Long,
    val sessionEpoch: Long,
    val ticket: String,
    val ticketExp: Long,
)

data class HeartbeatResult(
    val sceneSessionId: Long,
    val serverTimeMs: Long,
    val publicSceneSeq: Long,
)

/** `EntityState`（`scene_common.fbs`）的领域形态，加上在途路径。 */
data class EntityState(
    val entityId: Long,
    val entityVersion: Long,
    val movementSeq: Long,
    val position: Vec2Fixed,
    val movement: MovementInProgress?,
)

data class MovementInProgress(
    val pathId: Long,
    val start: Vec2Fixed,
    val pathPoints: List<Vec2Fixed>,
    val startTimeMs: Long,
    val speed: Int,
)

data class PresentRole(val roleId: Long, val roleName: String, val state: EntityState)

data class PublicSnapshot(
    val sceneRef: SceneRef,
    val publicSceneSeq: Long,
    val roles: List<PresentRole>,
    val serverTimeMs: Long,
)

data class PrivateSnapshot(
    val sceneRef: SceneRef,
    val roleId: Long,
    val sceneSessionId: Long,
    val sessionEpoch: Long,
    val channelId: Long,
    val lastSeenAt: Long,
    val publicSceneSeq: Long,
    val self: EntityState,
)
