package logic.codec

import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlatBufferBuilder
import com.google.flatbuffers.kotlin.Offset
import com.google.flatbuffers.kotlin.UnionOffset
import logic.scene.SceneRef
import logic.scene.Vec2Fixed
import privchat.mmorpg.scene.HeartbeatAck
import privchat.mmorpg.scene.HeartbeatRequest
import privchat.mmorpg.scene.InteractAck
import privchat.mmorpg.scene.InteractRequest
import privchat.mmorpg.scene.MovementStarted
import privchat.mmorpg.scene.RolePresence
import privchat.mmorpg.scene.SceneEvent
import privchat.mmorpg.scene.SceneEventBatchEnvelope
import privchat.mmorpg.scene.SceneEventPayload
import privchat.mmorpg.scene.SceneKind
import privchat.mmorpg.scene.Visibility

/**
 * 场景协议除移动外的 **FlatBuffers** 线格式(MMO_ARCHITECTURE_SPEC §10.6):
 * 心跳 MHR1/MHA1、交互 MIR1/MIA1、PUBLIC 事件批 MSE1(RolePresence / MovementStarted)。
 *
 * 领域对象与 JSON 镜像 codec 完全相同,服务层不感知编码;JSON 镜像只剩过渡与诊断。
 * 与 [SceneMoveFlatCodec] 同样的说明:Kotlin 运行时没有 Verifier,解码只用于已鉴权
 * 连接上的 transfer,并把生成代码的越界异常统一收敛成解码失败。
 */
object SceneFlatCodec {
    const val IDENT_HEARTBEAT: String = "MHR1"
    const val IDENT_INTERACT: String = "MIR1"
    const val IDENT_EVENT_BATCH: String = "MSE1"
    const val MAX_BYTES: Int = 64 * 1024

    /** FlatBuffers 布局:root offset 后紧跟 4 字节 identifier。 */
    fun identifierOf(bytes: ByteArray): String? =
        if (bytes.size >= 8) bytes.copyOfRange(4, 8).decodeToString().takeIf { id -> id.all { it in 'A'..'Z' || it in '0'..'9' } } else null

    // ---------------- 心跳 ----------------

    fun decodeHeartbeat(bytes: ByteArray): Result<SceneHeartbeatCodec.Request> = guarded(SceneHeartbeatCodec.DecodeError.NotAnObject) {
        if (bytes.size > MAX_BYTES || identifierOf(bytes) != IDENT_HEARTBEAT) throw SceneHeartbeatCodec.DecodeFailure(SceneHeartbeatCodec.DecodeError.NotAnObject)
        val r = HeartbeatRequest.asRoot(ArrayReadWriteBuffer(bytes))
        val version = r.protocolVersion.toInt()
        if (version != SceneHeartbeatCodec.PROTOCOL_VERSION) throw SceneHeartbeatCodec.DecodeFailure(SceneHeartbeatCodec.DecodeError.UnsupportedVersion(version))
        val requestId = r.requestId?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= SceneMoveCodec.MAX_REQUEST_ID_BYTES }
            ?: throw SceneHeartbeatCodec.DecodeFailure(SceneHeartbeatCodec.DecodeError.MissingField("request_id"))
        SceneHeartbeatCodec.Request(version, r.sceneSessionId.toLong(), requestId, r.clientTimeMs.toLong())
    }

    fun encodeHeartbeatAck(r: SceneHeartbeatCodec.Response): ByteArray {
        val b = FlatBufferBuilder(64)
        val root = HeartbeatAck.createHeartbeatAck(
            b, SceneHeartbeatCodec.PROTOCOL_VERSION.toUInt(), r.sceneSessionId.toULong(), r.serverTimeMs.toULong(), r.publicSceneSeq.toULong(),
        )
        HeartbeatAck.finishHeartbeatAckBuffer(b, root)
        return b.sizedByteArray()
    }

    fun encodeHeartbeat(r: SceneHeartbeatCodec.Request): ByteArray {
        val b = FlatBufferBuilder(64)
        val id = b.createString(r.requestId)
        val root = HeartbeatRequest.createHeartbeatRequest(b, r.protocolVersion.toUInt(), r.sceneSessionId.toULong(), id, r.clientTimeMs.toULong())
        HeartbeatRequest.finishHeartbeatRequestBuffer(b, root)
        return b.sizedByteArray()
    }

    fun decodeHeartbeatAck(bytes: ByteArray): SceneHeartbeatCodec.Response? = runCatching {
        val a = HeartbeatAck.asRoot(ArrayReadWriteBuffer(bytes))
        SceneHeartbeatCodec.Response(a.sceneSessionId.toLong(), a.serverTimeMs.toLong(), a.publicSceneSeq.toLong())
    }.getOrNull()

    // ---------------- 交互 ----------------

    fun decodeInteract(bytes: ByteArray): Result<SceneInteractCodec.Request> = guarded(SceneInteractCodec.DecodeError.NotAnObject) {
        if (bytes.size > MAX_BYTES || identifierOf(bytes) != IDENT_INTERACT) throw SceneInteractCodec.DecodeFailure(SceneInteractCodec.DecodeError.NotAnObject)
        val r = InteractRequest.asRoot(ArrayReadWriteBuffer(bytes))
        val version = r.protocolVersion.toInt()
        if (version != SceneHeartbeatCodec.PROTOCOL_VERSION) throw SceneInteractCodec.DecodeFailure(SceneInteractCodec.DecodeError.UnsupportedVersion(version))
        val requestId = r.requestId?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= SceneMoveCodec.MAX_REQUEST_ID_BYTES }
            ?: throw SceneInteractCodec.DecodeFailure(SceneInteractCodec.DecodeError.MissingField("request_id"))
        SceneInteractCodec.Request(r.sceneSessionId.toLong(), requestId, r.npcId.toLong())
    }

    fun encodeInteractAck(r: SceneInteractCodec.Response): ByteArray {
        val b = FlatBufferBuilder(256)
        val options = r.options.map { b.createString(it) }
        InteractAck.startOptionsVector(b, options.size)
        for (o in options.asReversed()) b.add(o)
        val optionsVec = b.endVector<String>()
        val name = b.createString(r.name)
        val kind = b.createString(r.kind)
        val dialog = b.createString(r.dialog)
        InteractAck.startInteractAck(b)
        InteractAck.addProtocolVersion(b, SceneHeartbeatCodec.PROTOCOL_VERSION.toUInt())
        InteractAck.addNpcId(b, r.npcId.toULong())
        InteractAck.addName(b, name)
        InteractAck.addKind(b, kind)
        InteractAck.addDialog(b, dialog)
        InteractAck.addOptions(b, optionsVec)
        val root = InteractAck.endInteractAck(b)
        InteractAck.finishInteractAckBuffer(b, root)
        return b.sizedByteArray()
    }

    fun decodeInteractAck(bytes: ByteArray): SceneInteractCodec.Response? = runCatching {
        val a = InteractAck.asRoot(ArrayReadWriteBuffer(bytes))
        SceneInteractCodec.Response(
            a.npcId.toLong(), a.name ?: "", a.kind ?: "", a.dialog ?: "",
            (0 until a.optionsLength).map { a.options(it) ?: "" },
        )
    }.getOrNull()

    // ---------------- PUBLIC 事件批(MSE1)----------------

    /** 一条待编码的场景事件;`payload` 是领域形态,编码时才落成 union。 */
    sealed interface Event {
        val seq: Long
        data class Presence(override val seq: Long, val roleId: Long, val roleName: String, val entered: Boolean, val position: Vec2Fixed) : Event
        data class Movement(
            override val seq: Long, val entityId: Long, val movementSeq: Long, val entityVersion: Long, val pathId: Long,
            val start: Vec2Fixed, val pathPoints: List<Vec2Fixed>, val startTimeMs: Long, val speed: Int, val navigationVersion: Int,
        ) : Event
    }

    /** 一个 PUBLIC 批次:AOI 未实装期间 MovementStarted 与 RolePresence 都走 PUBLIC(VALIDATION V-E2 过渡)。 */
    fun encodePublicBatch(sceneRef: SceneRef, events: List<Event>, serverTimeMs: Long): ByteArray {
        require(events.isNotEmpty())
        val b = FlatBufferBuilder(512)
        val eventOffsets = events.map { e ->
            val (type, union) = when (e) {
                is Event.Presence -> {
                    val name = b.createString(e.roleName)
                    RolePresence.startRolePresence(b)
                    RolePresence.addRoleId(b, e.roleId.toULong())
                    RolePresence.addRoleName(b, name)
                    RolePresence.addEntered(b, e.entered)
                    RolePresence.addPosition(b, privchat.mmorpg.scene.Vec2Fixed.createVec2Fixed(b, e.position.x, e.position.y))
                    SceneEventPayload.RolePresence to RolePresence.endRolePresence(b).value
                }
                is Event.Movement -> {
                    MovementStarted.startPathPointsVector(b, e.pathPoints.size)
                    for (p in e.pathPoints.asReversed()) privchat.mmorpg.scene.Vec2Fixed.createVec2Fixed(b, p.x, p.y)
                    val points = b.endVector<privchat.mmorpg.scene.Vec2Fixed>()
                    MovementStarted.startMovementStarted(b)
                    MovementStarted.addEntityId(b, e.entityId.toULong())
                    MovementStarted.addMovementSeq(b, e.movementSeq.toUInt())
                    MovementStarted.addEntityVersion(b, e.entityVersion.toULong())
                    MovementStarted.addPathId(b, e.pathId.toULong())
                    MovementStarted.addAuthoritativeStartPosition(b, privchat.mmorpg.scene.Vec2Fixed.createVec2Fixed(b, e.start.x, e.start.y))
                    MovementStarted.addPathPoints(b, points)
                    MovementStarted.addStartTimeMs(b, e.startTimeMs.toULong())
                    MovementStarted.addSpeed(b, e.speed)
                    MovementStarted.addNavigationVersion(b, e.navigationVersion.toUInt())
                    SceneEventPayload.MovementStarted to MovementStarted.endMovementStarted(b).value
                }
            }
            SceneEvent.startSceneEvent(b)
            SceneEvent.addEventId(b, e.seq.toULong())
            SceneEvent.addStreamSeq(b, e.seq.toULong())
            SceneEvent.addServerTimeMs(b, serverTimeMs.toULong())
            SceneEvent.addCritical(b, e is Event.Presence)
            SceneEvent.addPayloadType(b, type)
            SceneEvent.addPayload(b, UnionOffset(union))
            SceneEvent.endSceneEvent(b)
        }
        SceneEventBatchEnvelope.startEventsVector(b, eventOffsets.size)
        for (o in eventOffsets.asReversed()) b.add(o)
        val eventsVec = b.endVector<SceneEvent>()
        val ref = privchat.mmorpg.scene.SceneRef.createSceneRef(
            b, if (sceneRef.kind == SceneRef.Kind.LINE) SceneKind.Line else SceneKind.Instance, sceneRef.id.toULong(), sceneRef.generation.toUInt(),
        )
        SceneEventBatchEnvelope.startSceneEventBatchEnvelope(b)
        SceneEventBatchEnvelope.addProtocolVersion(b, SceneHeartbeatCodec.PROTOCOL_VERSION.toUInt())
        SceneEventBatchEnvelope.addSceneRef(b, ref)
        SceneEventBatchEnvelope.addVisibility(b, Visibility.Public)
        SceneEventBatchEnvelope.addRecipientRoleId(b, 0UL)
        SceneEventBatchEnvelope.addBatchId(b, events.first().seq.toULong())
        SceneEventBatchEnvelope.addChunkIndex(b, 0u)
        SceneEventBatchEnvelope.addChunkCount(b, 1u)
        SceneEventBatchEnvelope.addFirstStreamSeq(b, events.first().seq.toULong())
        SceneEventBatchEnvelope.addLastStreamSeq(b, events.last().seq.toULong())
        SceneEventBatchEnvelope.addEvents(b, eventsVec)
        val root = SceneEventBatchEnvelope.endSceneEventBatchEnvelope(b)
        SceneEventBatchEnvelope.finishSceneEventBatchEnvelopeBuffer(b, root)
        return b.sizedByteArray()
    }

    /** 解码后的批次(测试与工具用;客户端有自己的反射 codec)。 */
    data class PublicBatch(val sceneRef: String, val firstSeq: Long, val lastSeq: Long, val events: List<Event>)

    fun decodePublicBatch(bytes: ByteArray): PublicBatch? = runCatching {
        if (identifierOf(bytes) != IDENT_EVENT_BATCH) return null
        val env = SceneEventBatchEnvelope.asRoot(ArrayReadWriteBuffer(bytes))
        val ref = env.sceneRef!!
        val sceneRef = SceneRef(if (ref.kind == SceneKind.Line) SceneRef.Kind.LINE else SceneRef.Kind.INSTANCE, ref.id.toLong(), ref.generation.toLong()).encode()
        val events = (0 until env.eventsLength).map { i ->
            val ev = env.events(i)!!
            when (ev.payloadType) {
                SceneEventPayload.RolePresence -> {
                    val p = ev.payload(RolePresence()) as RolePresence
                    val pos = p.position
                    Event.Presence(ev.streamSeq.toLong(), p.roleId.toLong(), p.roleName ?: "", p.entered, Vec2Fixed(pos?.x ?: 0, pos?.y ?: 0))
                }
                SceneEventPayload.MovementStarted -> {
                    val m = ev.payload(MovementStarted()) as MovementStarted
                    val s = m.authoritativeStartPosition
                    Event.Movement(
                        ev.streamSeq.toLong(), m.entityId.toLong(), m.movementSeq.toLong(), m.entityVersion.toLong(), m.pathId.toLong(),
                        Vec2Fixed(s?.x ?: 0, s?.y ?: 0), (0 until m.pathPointsLength).map { j -> m.pathPoints(j)!!.let { Vec2Fixed(it.x, it.y) } },
                        m.startTimeMs.toLong(), m.speed, m.navigationVersion.toInt(),
                    )
                }
                else -> error("unsupported payload ${ev.payloadType}")
            }
        }
        PublicBatch(sceneRef, env.firstStreamSeq.toLong(), env.lastStreamSeq.toLong(), events)
    }.getOrNull()

    private inline fun <T> guarded(fallback: Any, block: () -> T): Result<T> = runCatching(block).recoverCatching { e ->
        when (e) {
            is SceneHeartbeatCodec.DecodeFailure, is SceneInteractCodec.DecodeFailure -> throw e
            else -> throw when (fallback) {
                is SceneHeartbeatCodec.DecodeError -> SceneHeartbeatCodec.DecodeFailure(fallback)
                is SceneInteractCodec.DecodeError -> SceneInteractCodec.DecodeFailure(fallback)
                else -> e
            }
        }
    }
}
