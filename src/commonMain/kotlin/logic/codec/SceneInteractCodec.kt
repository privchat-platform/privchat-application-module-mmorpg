package logic.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * NPC 交互（`mmorpg/scene/interact`）的线格式。
 *
 * 与移动同一原则：客户端只报"我要和谁交互"，在不在交互距离内由服务端按权威位置判。
 * 响应只是对话内容与选项——这是底座，玩法（任务、商店、进战斗）挂在 `options` 上。
 */
object SceneInteractCodec {
    data class Request(val sceneSessionId: Long, val requestId: String, val npcId: Long)
    data class Response(val npcId: Long, val name: String, val kind: String, val dialog: String, val options: List<String>)

    sealed interface DecodeError {
        data object NotAnObject : DecodeError
        data class UnsupportedVersion(val got: Int) : DecodeError
        data class MissingField(val name: String) : DecodeError
    }
    class DecodeFailure(val error: DecodeError) : Exception(error.toString())

    fun decodeRequest(bytes: ByteArray): Result<Request> {
        val obj = runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
            ?: return Result.failure(DecodeFailure(DecodeError.NotAnObject))
        val version = obj["protocol_version"]?.jsonPrimitive?.intOrNull ?: return Result.failure(DecodeFailure(DecodeError.MissingField("protocol_version")))
        if (version != SceneHeartbeatCodec.PROTOCOL_VERSION) return Result.failure(DecodeFailure(DecodeError.UnsupportedVersion(version)))
        val session = obj["scene_session_id"]?.jsonPrimitive?.longOrNull ?: return Result.failure(DecodeFailure(DecodeError.MissingField("scene_session_id")))
        val requestId = obj["request_id"]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(DecodeFailure(DecodeError.MissingField("request_id")))
        val npc = obj["npc_id"]?.jsonPrimitive?.longOrNull ?: return Result.failure(DecodeFailure(DecodeError.MissingField("npc_id")))
        return Result.success(Request(session, requestId, npc))
    }

    fun encodeResponse(r: Response): ByteArray = buildJsonObject {
        put("protocol_version", SceneHeartbeatCodec.PROTOCOL_VERSION)
        put("npc_id", r.npcId)
        put("name", r.name)
        put("kind", r.kind)
        put("dialog", r.dialog)
        put("options", buildJsonArray { r.options.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }.toString().encodeToByteArray()
}
