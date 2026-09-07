package logic.codec

import logic.scene.Vec2Fixed

/**
 * 移动意图 / ACK 的**领域对象**(`scene_move_intent.fbs` / `scene_move_ack.fbs` 的语义形态)。
 * 线格式只有 FlatBuffers,编解码在 [SceneMoveFlatCodec];三种 command 互斥(fbs 里是
 * union),一个都没有 = V-I1,由服务层报 21610。
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

}
