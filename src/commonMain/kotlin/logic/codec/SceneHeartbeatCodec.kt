package logic.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Scene heartbeat 的线格式。
 *
 * ### 为什么单独一层
 *
 * 编码今天是 JSON，将来要换 FlatBuffers（`protocol/schemas` 已经写好）。把编解码
 * 关在这一层，替换时业务逻辑不动。但**字段语义从第一天就按正式协议定**——如果
 * 现在图省事收 `{"x":1,"y":2}`，将来换编码时要改的就不是编解码而是整个数据模型。
 *
 * ### 版本切换不能静默
 *
 * [PROTOCOL_VERSION] 随请求上行。收到不认识的版本必须报错而不是尽力解析：静默
 * 兼容会让客户端以为自己用的是新协议，而服务端按旧语义处理，这类错位在生产上
 * 极难定位。将来引入 FlatBuffers 时，靠 file identifier + 本字段共同选择解码路径。
 */
object SceneHeartbeatCodec {

    /** 当前线协议版本。JSON 与未来的 FlatBuffers 共用同一个版本轴。 */
    const val PROTOCOL_VERSION: Int = 1

    data class Request(
        val protocolVersion: Int,
        val sceneSessionId: Long,
        val requestId: String,
        val clientTimeMs: Long,
    )

    data class Response(
        val sceneSessionId: Long,
        val serverTimeMs: Long,
        /**
         * 场景公共事件序号。客户端拿它和自己最后收到的广播 seq 比对，
         * 对不上说明漏收了，去拉 snapshot 补齐——心跳因此同时是一条对账通道，
         * 而不只是"我还活着"。
         */
        val publicSceneSeq: Long,
    )

    sealed interface DecodeError {
        data object NotAnObject : DecodeError
        data class UnsupportedVersion(val got: Int) : DecodeError
        data class MissingField(val name: String) : DecodeError
    }

    fun decodeRequest(bytes: ByteArray): Result<Request> {
        val obj = runCatching {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        }.getOrNull() ?: return Result.failure(DecodeFailure(DecodeError.NotAnObject))

        val version = obj["protocol_version"]?.jsonPrimitive?.intOrNull
            ?: return Result.failure(DecodeFailure(DecodeError.MissingField("protocol_version")))
        if (version != PROTOCOL_VERSION) {
            return Result.failure(DecodeFailure(DecodeError.UnsupportedVersion(version)))
        }

        val sessionId = obj["scene_session_id"]?.jsonPrimitive?.longOrNull
            ?: return Result.failure(DecodeFailure(DecodeError.MissingField("scene_session_id")))
        val requestId = obj["request_id"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return Result.failure(DecodeFailure(DecodeError.MissingField("request_id")))
        val clientTime = obj["client_time_ms"]?.jsonPrimitive?.longOrNull
            ?: return Result.failure(DecodeFailure(DecodeError.MissingField("client_time_ms")))

        return Result.success(Request(version, sessionId, requestId, clientTime))
    }

    fun encodeResponse(r: Response): ByteArray = buildJsonObject {
        put("protocol_version", PROTOCOL_VERSION)
        put("scene_session_id", r.sceneSessionId)
        put("server_time_ms", r.serverTimeMs)
        put("public_scene_seq", r.publicSceneSeq)
    }.toString().encodeToByteArray()

    class DecodeFailure(val error: DecodeError) : Exception(describe(error))
}

private fun describe(e: SceneHeartbeatCodec.DecodeError): String = when (e) {
    SceneHeartbeatCodec.DecodeError.NotAnObject -> "body is not a JSON object"
    is SceneHeartbeatCodec.DecodeError.UnsupportedVersion ->
        "unsupported protocol_version ${e.got}, this build speaks " +
            "${SceneHeartbeatCodec.PROTOCOL_VERSION}"
    is SceneHeartbeatCodec.DecodeError.MissingField -> "missing required field: ${e.name}"
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content.takeIf { it.isNotEmpty() } else null
