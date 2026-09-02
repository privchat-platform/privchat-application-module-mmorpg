package logic.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import neton.logging.Logger
import transfer.PrivChatTransferContext
import transfer.PrivChatTransferHandler
import transfer.PrivChatTransferResult

/**
 * MMORPG 模块的 Channel Transfer 入口（MMO_ARCHITECTURE_SPEC §2 + dispatch §5.2）。
 *
 * 分发链路（spec 定死，本 handler 是它的末端）：
 * ```
 * client transfer_bytes → privchat-server
 *   → ServerEvent(event_type="transfer.requested")
 *   → POST /service/privchat/server-event/dispatch
 *   → TransferRequestedServerEventHandler        (一级：认领 event_type)
 *   → channel_id → serviceName="mmorpg"
 *   → PrivChatTransferServiceRegistry
 *   → 本 handler                                  (二级：channel-scoped 业务)
 * ```
 *
 * v1 只有一条 route，目的是把整条链路钉住而不是实现玩法：
 *
 *   route = `mmorpg/scene/move`   （spec §1 要求恰好 3 段 service/域/动作）
 *   body  = `{"scene_id":1,"x":10,"y":20,"seq":7}`   UTF-8 JSON
 *   resp  = `{"accepted":true,"scene_id":1,"x":10,"y":20,"seq":7,"server_ts":...}`
 *
 * **编码为什么暂时是 JSON**：`protocol/schemas` 下已有完整的 FlatBuffers scene
 * 协议，但 flatc 的 Kotlin 后端只产出 JVM 绑定（`java.nio.ByteBuffer` /
 * `com.google.flatbuffers.Table`），在 Kotlin/Native 编不过。schema 与 fixtures
 * 原样保留，先用 JSON 打通传输与分发；编码方案定了再替换 body 的编解码，
 * **route 与分发链路不受影响**。
 */
class MmorpgTransferHandler(
    private val log: Logger,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : PrivChatTransferHandler {

    override val serviceName: String = "mmorpg"

    override suspend fun handle(ctx: PrivChatTransferContext): PrivChatTransferResult {
        if (ctx.route != ROUTE_SCENE_MOVE) {
            // 未知 route 明确拒绝，不静默成功：客户端拼错 route 时必须立刻看到。
            log.warn(
                "mmorpg.transfer.unknown_route",
                mapOf("route" to ctx.route, "channelId" to ctx.channelId),
            )
            return PrivChatTransferResult.error(ERR_UNKNOWN_ROUTE, "unknown route: ${ctx.route}")
        }

        val body = decode(ctx.body)
            ?: return PrivChatTransferResult.error(ERR_BAD_BODY, "body is not a JSON object")

        val sceneId = body.longField("scene_id")
        val x = body.longField("x")
        val y = body.longField("y")
        val seq = body.longField("seq")
        if (sceneId == null || x == null || y == null || seq == null) {
            return PrivChatTransferResult.error(
                ERR_BAD_BODY,
                "scene_id / x / y / seq are all required and must be integers",
            )
        }

        log.info(
            "mmorpg.transfer.scene_move",
            mapOf(
                "channelId" to ctx.channelId,
                "userId" to ctx.userId,
                "sceneId" to sceneId,
                "seq" to seq,
            ),
        )

        // v1 不落库、不做 AOI：链路验证只需要证明请求带着完整的身份与载荷到达了这里。
        val resp = buildJsonObject {
            put("accepted", true)
            put("scene_id", sceneId)
            put("x", x)
            put("y", y)
            put("seq", seq)
            put("user_id", ctx.userId)
            put("server_ts", now())
        }
        return PrivChatTransferResult.ok(resp.toString().encodeToByteArray())
    }

    private fun decode(bytes: ByteArray): JsonObject? = runCatching {
        Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    }.getOrNull()

    private fun JsonObject.longField(name: String): Long? =
        this[name]?.jsonPrimitive?.longOrNull

    companion object {
        const val ROUTE_SCENE_MOVE = "mmorpg/scene/move"

        // 取自 registry/error_codes.toml 的 mmo-scene 号段（21600-21699）。
        // 不自己编号：号段是注册过的，随手取值正是核心表 20900 / 20920 两次
        // 真实碰撞的成因。
        /** SceneRouteUnknown */
        const val ERR_UNKNOWN_ROUTE = 21611
        /** SceneCommandInvalid */
        const val ERR_BAD_BODY = 21610
    }
}
