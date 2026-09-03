package logic.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logic.scene.Vec2Fixed

/**
 * 移动意图的线格式（JSON 镜像 `scene_move_intent.fbs` / `scene_move_ack.fbs`）。
 *
 * 字段名与 schema 一一对应，将来切 FlatBuffers 时只换编解码，语义与校验不动。
 * 三种 command 互斥（fbs 里是 union）；一个都没有 = V-I1，由服务层报 21610。
 */
object SceneMoveCodec {
    const val MAX_REQUEST_ID_BYTES: Int = 64

    sealed interface Command {
        data class MoveTo(val target: Vec2Fixed) : Command
        data object Stop : Command
        data class CancelPath(val pathId: Long) : Command
    }

    data class Intent(
        val protocolVersion: Int,
        val sceneSessionId: Long,
        val requestId: String,
        val movementSeq: Long,
        val command: Command?,
        val clientTimeMs: Long,
    ) {
        /**
         * 幂等比较用的规范化载荷：同 request_id 下比较的是「意图」而不是字节——
         * `client_time_ms` 是诊断字段，重试时变了不算不同意图。
         */
        fun canonical(): String = "$protocolVersion|$sceneSessionId|$movementSeq|$command"
    }

    data class Ack(
        val sceneSessionId: Long,
        val requestId: String,
        val acceptedMovementSeq: Long,
        val entityVersion: Long,
        val replayed: Boolean,
        val pathId: Long,
    )

    sealed interface DecodeError {
        data object NotAnObject : DecodeError
        data class UnsupportedVersion(val got: Int) : DecodeError
        data class MissingField(val name: String) : DecodeError
        data class RequestIdTooLong(val bytes: Int) : DecodeError
    }

    class DecodeFailure(val error: DecodeError) : Exception(error.toString())

    fun decodeIntent(bytes: ByteArray): Result<Intent> {
        val obj = runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
            ?: return fail(DecodeError.NotAnObject)
        val version = obj["protocol_version"]?.jsonPrimitive?.intOrNull ?: return fail(DecodeError.MissingField("protocol_version"))
        if (version != SceneHeartbeatCodec.PROTOCOL_VERSION) return fail(DecodeError.UnsupportedVersion(version))
        val session = obj["scene_session_id"]?.jsonPrimitive?.longOrNull ?: return fail(DecodeError.MissingField("scene_session_id"))
        val requestId = obj["request_id"]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            ?: return fail(DecodeError.MissingField("request_id"))
        val idBytes = requestId.encodeToByteArray().size
        if (idBytes > MAX_REQUEST_ID_BYTES) return fail(DecodeError.RequestIdTooLong(idBytes))
        val seq = obj["movement_seq"]?.jsonPrimitive?.longOrNull ?: return fail(DecodeError.MissingField("movement_seq"))
        val clientTime = obj["client_time_ms"]?.jsonPrimitive?.longOrNull ?: 0L
        val commandObj = obj["command"]?.let { it as? JsonObject }
        val command: Command? = when {
            commandObj == null -> null
            commandObj["move_to"] != null -> {
                val t = commandObj["move_to"]!!.jsonObject["target_position"]?.jsonObject
                    ?: return fail(DecodeError.MissingField("command.move_to.target_position"))
                val x = t["x"]?.jsonPrimitive?.intOrNull ?: return fail(DecodeError.MissingField("target_position.x"))
                val y = t["y"]?.jsonPrimitive?.intOrNull ?: return fail(DecodeError.MissingField("target_position.y"))
                Command.MoveTo(Vec2Fixed(x, y))
            }
            commandObj["stop"] != null -> Command.Stop
            commandObj["cancel_path"] != null -> {
                val pid = commandObj["cancel_path"]!!.jsonObject["path_id"]?.jsonPrimitive?.longOrNull
                    ?: return fail(DecodeError.MissingField("command.cancel_path.path_id"))
                Command.CancelPath(pid)
            }
            else -> null
        }
        return Result.success(Intent(version, session, requestId, seq, command, clientTime))
    }

    fun encodeAck(a: Ack): ByteArray = buildJsonObject {
        put("protocol_version", SceneHeartbeatCodec.PROTOCOL_VERSION)
        put("scene_session_id", a.sceneSessionId)
        put("request_id", a.requestId)
        put("accepted_movement_seq", a.acceptedMovementSeq)
        put("entity_version", a.entityVersion)
        put("replayed", a.replayed)
        put("path_id", a.pathId)
    }.toString().encodeToByteArray()

    private fun <T> fail(e: DecodeError): Result<T> = Result.failure(DecodeFailure(e))
}

/** `MovementStarted`（`scene_event.fbs`）的 JSON 镜像，走 PUBLIC topic。 */
fun ScenePublicEventCodec.encodeMovementStarted(
    sceneRef: String,
    seq: Long,
    entityId: Long,
    movementSeq: Long,
    entityVersion: Long,
    pathId: Long,
    start: Vec2Fixed,
    pathPoints: List<Vec2Fixed>,
    startTimeMs: Long,
    speed: Int,
    navigationVersion: Int,
    serverTimeMs: Long,
): String = buildJsonObject {
    put("protocol_version", SceneHeartbeatCodec.PROTOCOL_VERSION)
    put("topic", ScenePublicEventCodec.TOPIC)
    put("event", ScenePublicEventCodec.EVENT_MOVEMENT_STARTED)
    put("scene_ref", sceneRef)
    put("seq", seq)
    put("server_time_ms", serverTimeMs)
    putJsonObject("movement_started") {
        put("entity_id", entityId)
        put("movement_seq", movementSeq)
        put("entity_version", entityVersion)
        put("path_id", pathId)
        putJsonObject("authoritative_start_position") { put("x", start.x); put("y", start.y) }
        put("path_points", buildJsonArray { pathPoints.forEach { p -> add(buildJsonObject { put("x", p.x); put("y", p.y) }) } })
        put("start_time_ms", startTimeMs)
        put("speed", speed)
        put("navigation_version", navigationVersion)
    }
}.toString()
