package logic.codec

import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlatBufferBuilder
import com.google.flatbuffers.kotlin.Table
import logic.scene.Vec2Fixed
import privchat.mmorpg.scene.CancelPath
import privchat.mmorpg.scene.MoveCommand
import privchat.mmorpg.scene.MoveIntentAck
import privchat.mmorpg.scene.MoveIntentEnvelope
import privchat.mmorpg.scene.MoveTo

/**
 * 移动意图 / ACK 的 **FlatBuffers** 线格式(`scene_move_intent.fbs` MMI1、
 * `scene_move_ack.fbs` MMA1),MMO_ARCHITECTURE_SPEC §10.6 的正式格式。
 *
 * 产出与 [SceneMoveCodec] 相同的领域对象,所以 SceneService 与 V-I* 校验一行不改;
 * 变的只是字节 ↔ 对象这一层。
 *
 * ### 这里没有 verifier
 *
 * flatbuffers-kotlin 运行时不带 Verifier(C++ 才有)。服务端解码不可信输入时的防线是:
 * 包长上限、identifier、root offset 落在包内、字符串/子表偏移由生成代码的
 * `lookupField` 按 vtable 读取(越界会抛异常,这里统一捕获成解码失败)。这不等价于
 * 完整校验,所以本 codec 只用于**已鉴权连接上的 transfer**,并在 spec 里记为已知差距。
 */
object SceneMoveFlatCodec {
    const val IDENT_INTENT: String = "MMI1"
    const val IDENT_ACK: String = "MMA1"
    const val MAX_BYTES: Int = 64 * 1024

    /** 是否像一个 MMI1 包:root offset 后紧跟 4 字节 identifier(FlatBuffers 布局)。 */
    fun looksLikeIntent(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[4] == 'M'.code.toByte() && bytes[5] == 'M'.code.toByte() &&
            bytes[6] == 'I'.code.toByte() && bytes[7] == '1'.code.toByte()

    fun decodeIntent(bytes: ByteArray): Result<SceneMoveCodec.Intent> {
        if (bytes.size > MAX_BYTES) return fail(SceneMoveCodec.DecodeError.RequestIdTooLong(bytes.size))
        if (!looksLikeIntent(bytes)) return fail(SceneMoveCodec.DecodeError.NotAnObject)
        return runCatching {
            val buf = ArrayReadWriteBuffer(bytes)
            val env = MoveIntentEnvelope.asRoot(buf)
            val version = env.protocolVersion.toInt()
            if (version != SceneHeartbeatCodec.PROTOCOL_VERSION) throw SceneMoveCodec.DecodeFailure(SceneMoveCodec.DecodeError.UnsupportedVersion(version))
            val requestId = env.requestId?.takeIf { it.isNotEmpty() }
                ?: throw SceneMoveCodec.DecodeFailure(SceneMoveCodec.DecodeError.MissingField("request_id"))
            val idBytes = requestId.encodeToByteArray().size
            if (idBytes > SceneMoveCodec.MAX_REQUEST_ID_BYTES) throw SceneMoveCodec.DecodeFailure(SceneMoveCodec.DecodeError.RequestIdTooLong(idBytes))
            val command: SceneMoveCodec.Command? = when (env.commandType) {
                MoveCommand.MoveTo -> {
                    val t = (env.command(MoveTo()) as? MoveTo)?.targetPosition
                        ?: throw SceneMoveCodec.DecodeFailure(SceneMoveCodec.DecodeError.MissingField("command.move_to.target_position"))
                    SceneMoveCodec.Command.MoveTo(Vec2Fixed(t.x, t.y))
                }
                MoveCommand.Stop -> SceneMoveCodec.Command.Stop
                MoveCommand.CancelPath -> SceneMoveCodec.Command.CancelPath(
                    ((env.command(CancelPath()) as? CancelPath)?.pathId ?: 0UL).toLong(),
                )
                else -> null // NONE 或未知分支 → V-I1,由服务层报 21610
            }
            SceneMoveCodec.Intent(
                protocolVersion = version,
                sceneSessionId = env.sceneSessionId.toLong(),
                requestId = requestId,
                movementSeq = env.movementSeq.toLong(),
                command = command,
                clientTimeMs = env.clientTimeMs.toLong(),
            )
        }.recoverCatching { e ->
            if (e is SceneMoveCodec.DecodeFailure) throw e
            throw SceneMoveCodec.DecodeFailure(SceneMoveCodec.DecodeError.NotAnObject)
        }
    }

    fun encodeAck(a: SceneMoveCodec.Ack): ByteArray {
        val b = FlatBufferBuilder(128)
        val requestId = b.createString(a.requestId)
        MoveIntentAck.startMoveIntentAck(b)
        MoveIntentAck.addProtocolVersion(b, SceneHeartbeatCodec.PROTOCOL_VERSION.toUInt())
        MoveIntentAck.addSceneSessionId(b, a.sceneSessionId.toULong())
        MoveIntentAck.addRequestId(b, requestId)
        MoveIntentAck.addAcceptedMovementSeq(b, a.acceptedMovementSeq.toUInt())
        MoveIntentAck.addEntityVersion(b, a.entityVersion.toULong())
        MoveIntentAck.addReplayed(b, a.replayed)
        MoveIntentAck.addPathId(b, a.pathId.toULong())
        val root = MoveIntentAck.endMoveIntentAck(b)
        MoveIntentAck.finishMoveIntentAckBuffer(b, root)
        return b.sizedByteArray()
    }

    /** 测试与工具用:把领域意图编码成 MMI1(客户端的职责,服务端不发意图)。 */
    fun encodeIntent(i: SceneMoveCodec.Intent): ByteArray {
        val b = FlatBufferBuilder(128)
        val requestId = b.createString(i.requestId)
        var type = MoveCommand.None
        var union = 0
        when (val c = i.command) {
            is SceneMoveCodec.Command.MoveTo -> {
                MoveTo.startMoveTo(b)
                MoveTo.addTargetPosition(b, privchat.mmorpg.scene.Vec2Fixed.createVec2Fixed(b, c.target.x, c.target.y))
                union = MoveTo.endMoveTo(b).value; type = MoveCommand.MoveTo
            }
            SceneMoveCodec.Command.Stop -> {
                privchat.mmorpg.scene.Stop.startStop(b)
                union = privchat.mmorpg.scene.Stop.endStop(b).value; type = MoveCommand.Stop
            }
            is SceneMoveCodec.Command.CancelPath -> {
                union = CancelPath.createCancelPath(b, c.pathId.toULong()).value; type = MoveCommand.CancelPath
            }
            null -> Unit
        }
        MoveIntentEnvelope.startMoveIntentEnvelope(b)
        MoveIntentEnvelope.addProtocolVersion(b, i.protocolVersion.toUInt())
        MoveIntentEnvelope.addSceneSessionId(b, i.sceneSessionId.toULong())
        MoveIntentEnvelope.addRequestId(b, requestId)
        MoveIntentEnvelope.addMovementSeq(b, i.movementSeq.toUInt())
        MoveIntentEnvelope.addCommandType(b, type)
        if (union != 0) MoveIntentEnvelope.addCommand(b, com.google.flatbuffers.kotlin.UnionOffset(union))
        MoveIntentEnvelope.addClientTimeMs(b, i.clientTimeMs.toULong())
        val root = MoveIntentEnvelope.endMoveIntentEnvelope(b)
        MoveIntentEnvelope.finishMoveIntentEnvelopeBuffer(b, root)
        return b.sizedByteArray()
    }

    fun decodeAck(bytes: ByteArray): SceneMoveCodec.Ack? = runCatching {
        val buf = ArrayReadWriteBuffer(bytes)
        if (!Table.hasIdentifier(buf, IDENT_ACK)) return null
        val a = MoveIntentAck.asRoot(buf)
        SceneMoveCodec.Ack(
            sceneSessionId = a.sceneSessionId.toLong(), requestId = a.requestId ?: "", acceptedMovementSeq = a.acceptedMovementSeq.toLong(),
            entityVersion = a.entityVersion.toLong(), replayed = a.replayed, pathId = a.pathId.toLong(),
        )
    }.getOrNull()

    private fun <T> fail(e: SceneMoveCodec.DecodeError): Result<T> = Result.failure(SceneMoveCodec.DecodeFailure(e))
}
