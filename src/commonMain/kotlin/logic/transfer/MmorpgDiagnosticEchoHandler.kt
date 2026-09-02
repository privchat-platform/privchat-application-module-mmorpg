package logic.transfer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import neton.logging.Logger
import transfer.PrivChatTransferContext
import transfer.PrivChatTransferHandler
import transfer.PrivChatTransferResult

/**
 * **诊断用** Channel Transfer handler —— 链路验证，不是 MMORPG 业务实现。
 *
 * 它存在的唯一目的是回答一个问题：一串字节能不能带着调用者的身份，从客户端穿过
 * privchat-server、ServerEvent dispatch、两级路由，到达本模块并原路返回。
 *
 * ### 为什么是 `mmorpg/diagnostic/echo` 而不是 `mmorpg/scene/move`
 *
 * `MMO_WORLD_SCENE_SPEC` 已经为 scene 定义了正式契约：`scene_session_id`、
 * `movement_seq`、`MoveCommand(MoveTo/Stop/CancelPath)`、MoveIntentAck、幂等与
 * 权威位置，并且**明确禁止客户端自带场景身份**。一个收 `{"scene_id":..,"x":..}`
 * 的 handler 不满足其中任何一条。占用正式 route 会让「链路通了」被误读成
 * 「场景协议实现了」，而后者要重写数据模型和处理逻辑，不是换个编码那么简单。
 *
 * 诊断 route 与正式 scene route 分开，正式实现落地时本文件整个删除即可，
 * 不需要从中小心拆出临时代码。
 *
 * ### 回包内容
 *
 * 原样回传收到的字节，外加一份身份摘要。**不解析 body**：demo 不该对载荷格式有
 * 任何要求，客户端传 JSON、FlatBuffers 还是随机字节都应原样回来 —— 这样它同时
 * 也是二进制通道的验证，而不只是 JSON 的。
 */
class MmorpgDiagnosticEchoHandler(
    private val log: Logger,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : PrivChatTransferHandler {

    override val serviceName: String = "mmorpg"

    override suspend fun handle(ctx: PrivChatTransferContext): PrivChatTransferResult {
        if (ctx.route != ROUTE_DIAGNOSTIC_ECHO) {
            // 明确拒绝而不是静默成功：route 拼错是最常见的接入错误，静默会让它在
            // 客户端看起来像"服务端没反应"。
            log.warn(
                "mmorpg.diagnostic.unknown_route",
                mapOf("route" to ctx.route, "channelId" to ctx.channelId),
            )
            return PrivChatTransferResult.error(
                ERR_DEMO_ROUTE_UNKNOWN,
                "unknown route: ${ctx.route} (this module only serves $ROUTE_DIAGNOSTIC_ECHO)",
            )
        }

        log.info(
            "mmorpg.diagnostic.echo",
            mapOf(
                "channelId" to ctx.channelId,
                "userId" to ctx.userId,
                "bytes" to ctx.body.size,
            ),
        )

        // 身份摘要是这条 demo 的重点：证明 user_id / channel_id 穿过两级分发没有
        // 丢失或被改写。回包外层是 JSON，但**请求体不做任何解析**。
        val summary = buildJsonObject {
            put("echo_bytes", ctx.body.size)
            put("user_id", ctx.userId)
            put("channel_id", ctx.channelId)
            put("route", ctx.route)
            put("client_request_id", ctx.clientRequestId)
            put("server_ts", now())
        }
        // data = 摘要 JSON + 0x00 分隔 + 原始字节。分隔符让客户端既能读摘要，
        // 又能逐字节比对自己发出去的内容 —— 二进制载荷不需要可打印。
        val head = summary.toString().encodeToByteArray()
        return PrivChatTransferResult.ok(head + byteArrayOf(0) + ctx.body)
    }

    companion object {
        const val ROUTE_DIAGNOSTIC_ECHO = "mmorpg/diagnostic/echo"

        /**
         * 取自本模块自持号段的**末端**（见 registry/error_codes.toml）。
         *
         * 刻意不落在 scene 协议正在使用的 21600 起始处：那是正式协议的地盘，
         * 一个 demo 码混在中间会让后来者以为它是 scene 语义的一部分。
         * 正式实现落地时连同本文件一起删除。
         */
        const val ERR_DEMO_ROUTE_UNKNOWN = 21699
    }
}
