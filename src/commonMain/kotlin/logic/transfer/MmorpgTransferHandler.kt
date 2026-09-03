package logic.transfer

import logic.MmoErrorCodes
import logic.codec.SceneHeartbeatCodec
import logic.codec.SceneMoveCodec
import logic.scene.SceneOutcome
import logic.scene.SceneService
import neton.logging.Logger
import transfer.PrivChatTransferContext
import transfer.PrivChatTransferHandler
import transfer.PrivChatTransferResult

/**
 * mmorpg 的 Channel Transfer 入口。
 *
 * `serviceName` 必须等于 `privchat_business_service.name`——dispatcher 走
 * `channel_id → service_id → service.name → registry.find(name)`，代码里不出现
 * `service_id`，部署侧改 ID 不需要重编。
 *
 * ### 为什么 route 分派写成穷举而不是前缀匹配
 *
 * 未知 route 必须以明确错误返回。前缀匹配容易把 `mmorpg/scene/move`（尚未实装）
 * 误吞进 heartbeat 分支，客户端会以为移动成功了。
 */
class MmorpgTransferHandler(
    private val log: Logger,
    private val scenes: SceneService,
) : PrivChatTransferHandler {

    override val serviceName: String = SERVICE_NAME

    override suspend fun handle(ctx: PrivChatTransferContext): PrivChatTransferResult =
        when (ctx.route) {
            ROUTE_SCENE_HEARTBEAT -> heartbeat(ctx)
            ROUTE_SCENE_MOVE -> move(ctx)
            else -> {
                log.warn("mmo.transfer.unknown_route route=${ctx.route} channel_id=${ctx.channelId}")
                PrivChatTransferResult.error(
                    MmoErrorCodes.SCENE_COMMAND_INVALID,
                    "unknown mmorpg route: ${ctx.route}",
                )
            }
        }

    private suspend fun heartbeat(ctx: PrivChatTransferContext): PrivChatTransferResult {
        val decoded = SceneHeartbeatCodec.decodeRequest(ctx.body)
        val request = decoded.getOrElse { failure ->
            val error = (failure as? SceneHeartbeatCodec.DecodeFailure)?.error
            val code = when (error) {
                is SceneHeartbeatCodec.DecodeError.UnsupportedVersion ->
                    MmoErrorCodes.SCENE_PROTOCOL_VERSION_UNSUPPORTED
                else -> MmoErrorCodes.SCENE_PAYLOAD_TOO_LARGE
            }
            log.warn(
                "mmo.transfer.heartbeat.decode_failed channel_id=${ctx.channelId} " +
                    "user_id=${ctx.userId} reason=${failure.message}",
            )
            return PrivChatTransferResult.error(code, failure.message ?: "malformed heartbeat")
        }

        // channelId 取自 dispatcher 解析出的上下文，不取自 body：body 是客户端可控的。
        return when (
            val outcome = scenes.heartbeat(
                userId = ctx.userId,
                channelId = ctx.channelId,
                sceneSessionId = request.sceneSessionId,
            )
        ) {
            is SceneOutcome.Failure -> PrivChatTransferResult.error(outcome.code, outcome.message)
            is SceneOutcome.Success -> PrivChatTransferResult.ok(
                SceneHeartbeatCodec.encodeResponse(
                    SceneHeartbeatCodec.Response(
                        sceneSessionId = outcome.value.sceneSessionId,
                        serverTimeMs = outcome.value.serverTimeMs,
                        publicSceneSeq = outcome.value.publicSceneSeq,
                    ),
                ),
            )
        }
    }

    private suspend fun move(ctx: PrivChatTransferContext): PrivChatTransferResult {
        val intent = SceneMoveCodec.decodeIntent(ctx.body).getOrElse { failure ->
            val error = (failure as? SceneMoveCodec.DecodeFailure)?.error
            val code = when (error) {
                is SceneMoveCodec.DecodeError.UnsupportedVersion -> MmoErrorCodes.SCENE_PROTOCOL_VERSION_UNSUPPORTED
                else -> MmoErrorCodes.SCENE_PAYLOAD_TOO_LARGE
            }
            log.warn("mmo.transfer.move.decode_failed channel_id=${ctx.channelId} user_id=${ctx.userId} reason=${failure.message}")
            return PrivChatTransferResult.error(code, failure.message ?: "malformed move intent")
        }
        return when (val outcome = scenes.move(userId = ctx.userId, channelId = ctx.channelId, intent = intent)) {
            // 拒绝一律走外层 code、data 为空（spec §9.1）。
            is SceneOutcome.Failure -> PrivChatTransferResult.error(outcome.code, outcome.message)
            is SceneOutcome.Success -> PrivChatTransferResult.ok(SceneMoveCodec.encodeAck(outcome.value))
        }
    }

    companion object {
        const val SERVICE_NAME: String = "mmorpg"

        /** 移动意图（上行）。 */
        const val ROUTE_SCENE_MOVE: String = "mmorpg/scene/move"

        /**
         * 场景心跳（上行）。route 恰好三段 `mmorpg/<域>/<动作>`
         * （MMO_ARCHITECTURE_SPEC §2.1）。
         */
        const val ROUTE_SCENE_HEARTBEAT: String = "mmorpg/scene/heartbeat"
    }
}
