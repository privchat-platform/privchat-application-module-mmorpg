package logic.codec

import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlatBufferBuilder
import com.google.flatbuffers.kotlin.UnionOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logic.battle.BattleCommand
import logic.battle.BattlePhase
import logic.battle.CommandKind
import logic.battle.RetargetReason
import model.MmoBattleEvent
import privchat.mmorpg.battle.ActorDied
import privchat.mmorpg.battle.Attack
import privchat.mmorpg.battle.BattleCommandAck
import privchat.mmorpg.battle.BattleCommandEnvelope
import privchat.mmorpg.battle.BattleEvent
import privchat.mmorpg.battle.BattleEventBatchEnvelope
import privchat.mmorpg.battle.BattleInstantAck
import privchat.mmorpg.battle.BattleInstantRequest
import privchat.mmorpg.battle.BattleSettled
import privchat.mmorpg.battle.CommandAccepted
import privchat.mmorpg.battle.CommandPayload
import privchat.mmorpg.battle.DamageDealt
import privchat.mmorpg.battle.EventPayload
import privchat.mmorpg.battle.InitiativeResolved
import privchat.mmorpg.battle.InstantOp
import privchat.mmorpg.battle.Phase
import privchat.mmorpg.battle.PhaseChanged
import privchat.mmorpg.battle.SlotInfo
import privchat.mmorpg.battle.SlotKind
import privchat.mmorpg.battle.SlotsOffered
import privchat.mmorpg.battle.Visibility

/**
 * 战斗协议的 **FlatBuffers** 线格式(`battle_*.fbs`,MMO_ARCHITECTURE_SPEC §10.6):
 * 指令 MBC1 → ACK MBA1、即时操作 MBQ1 → MBR1、事件批 MBE1。
 *
 * 领域对象与 [BattleCodec](JSON 镜像)完全相同,`BattleService` 不感知编码。outbox
 * (`mmo_battle_event.payload`)里存的是 JSON 单键对象,投递时在这里落成 union;
 * 这样 outbox 与业务代码不必认识 FlatBuffers,换 schema 只动本文件。
 */
object BattleFlatCodec {
    const val IDENT_COMMAND: String = "MBC1"
    const val IDENT_INSTANT: String = "MBQ1"
    const val IDENT_EVENT_BATCH: String = "MBE1"
    const val MAX_BYTES: Int = 64 * 1024

    private val phaseNames = BattlePhase.entries.map { it.name }

    private fun phaseOf(p: Phase): String = phaseNames.getOrElse(p.value.toInt()) { p.value.toString() }
    private fun phaseTo(name: String): Phase = Phase(BattlePhase.entries.firstOrNull { it.name == name }?.ordinal?.toUByte() ?: 0u)

    // ---------------- 指令 ----------------

    fun decodeCommand(bytes: ByteArray): Result<BattleCodec.CommandEnvelope> = runCatching {
        if (bytes.size > MAX_BYTES) throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.TooLarge("body"))
        if (SceneFlatCodec.identifierOf(bytes) != IDENT_COMMAND) throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.NotAnObject)
        val env = BattleCommandEnvelope.asRoot(ArrayReadWriteBuffer(bytes))
        val version = env.protocolVersion.toInt()
        if (version != BattleCodec.PROTOCOL_VERSION) throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.UnsupportedVersion(version))
        val requestId = env.requestId?.takeIf { it.isNotEmpty() } ?: throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.MissingField("request_id"))
        if (requestId.encodeToByteArray().size > BattleCodec.MAX_REQUEST_ID_BYTES) throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.TooLarge("request_id"))
        val payload: BattleCommand? = when (env.payloadType) {
            CommandPayload.Attack -> BattleCommand.Attack((env.payload(Attack()) as? Attack)?.selectedTargetId?.toLong() ?: 0)
            CommandPayload.Defend -> BattleCommand.Defend
            CommandPayload.Escape -> BattleCommand.Escape
            CommandPayload.Wait -> BattleCommand.Wait
            CommandPayload.CastSkill -> BattleCommand.Unsupported(CommandKind.CAST_SKILL)
            CommandPayload.UseItem -> BattleCommand.Unsupported(CommandKind.USE_ITEM)
            CommandPayload.Protect -> BattleCommand.Unsupported(CommandKind.PROTECT)
            CommandPayload.Summon -> BattleCommand.Unsupported(CommandKind.SUMMON)
            CommandPayload.Recall -> BattleCommand.Unsupported(CommandKind.RECALL)
            CommandPayload.Capture -> BattleCommand.Unsupported(CommandKind.CAPTURE)
            else -> null
        }
        BattleCodec.CommandEnvelope(
            requestId = requestId, battleId = env.battleId.toLong(), roleId = env.roleId.toLong(), actorId = env.actorId.toLong(),
            commandSlotId = env.commandSlotId.toLong(), round = env.round.toInt(), phase = phaseOf(env.phase), phaseVersion = env.phaseVersion.toLong(),
            actionSeq = env.actionSeq.toInt(), payload = payload, payloadJson = payload?.let { BattleCodec.encodeCommandPayload(it).toString() } ?: "",
        )
    }.recoverCatching { e -> if (e is BattleCodec.DecodeFailure) throw e else throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.NotAnObject) }

    fun encodeAck(a: BattleCodec.Ack): ByteArray {
        val b = FlatBufferBuilder(96)
        val root = BattleCommandAck.createBattleCommandAck(
            b, BattleCodec.PROTOCOL_VERSION.toUInt(), a.battleId.toULong(), a.commandSlotId.toULong(), a.acceptedActionSeq.toUInt(),
            a.replacedPrevious, a.round.toUInt(), phaseTo(a.phase), a.phaseVersion.toUInt(), a.slotDeadlineAtMs.toULong(),
        )
        BattleCommandAck.finishBattleCommandAckBuffer(b, root)
        return b.sizedByteArray()
    }

    fun decodeAck(bytes: ByteArray): BattleCodec.Ack? = runCatching {
        val a = BattleCommandAck.asRoot(ArrayReadWriteBuffer(bytes))
        BattleCodec.Ack(a.battleId.toLong(), a.commandSlotId.toLong(), a.acceptedActionSeq.toInt(), a.replacedPrevious, a.round.toInt(), phaseOf(a.phase), a.phaseVersion.toLong(), a.slotDeadlineAtMs.toLong())
    }.getOrNull()

    /** 测试与工具用:客户端才发指令,服务端只解。 */
    fun encodeCommand(env: BattleCodec.CommandEnvelope): ByteArray {
        val b = FlatBufferBuilder(128)
        val requestId = b.createString(env.requestId)
        var type = CommandPayload.None
        var union = 0
        when (val c = env.payload) {
            is BattleCommand.Attack -> { union = Attack.createAttack(b, c.targetId.toULong()).value; type = CommandPayload.Attack }
            BattleCommand.Defend -> { privchat.mmorpg.battle.Defend.startDefend(b); union = privchat.mmorpg.battle.Defend.endDefend(b).value; type = CommandPayload.Defend }
            BattleCommand.Escape -> { privchat.mmorpg.battle.Escape.startEscape(b); union = privchat.mmorpg.battle.Escape.endEscape(b).value; type = CommandPayload.Escape }
            BattleCommand.Wait -> { privchat.mmorpg.battle.Wait.startWait(b); union = privchat.mmorpg.battle.Wait.endWait(b).value; type = CommandPayload.Wait }
            is BattleCommand.Unsupported -> when (c.kind) {
                CommandKind.CAST_SKILL -> { union = privchat.mmorpg.battle.CastSkill.createCastSkill(b, 0u, 0UL).value; type = CommandPayload.CastSkill }
                else -> { union = privchat.mmorpg.battle.Capture.createCapture(b, 0UL).value; type = CommandPayload.Capture }
            }
            null -> Unit
        }
        BattleCommandEnvelope.startBattleCommandEnvelope(b)
        BattleCommandEnvelope.addProtocolVersion(b, BattleCodec.PROTOCOL_VERSION.toUInt())
        BattleCommandEnvelope.addRequestId(b, requestId)
        BattleCommandEnvelope.addBattleId(b, env.battleId.toULong())
        BattleCommandEnvelope.addRoleId(b, env.roleId.toULong())
        BattleCommandEnvelope.addActorId(b, env.actorId.toULong())
        BattleCommandEnvelope.addCommandSlotId(b, env.commandSlotId.toULong())
        BattleCommandEnvelope.addRound(b, env.round.toUInt())
        BattleCommandEnvelope.addPhase(b, phaseTo(env.phase))
        BattleCommandEnvelope.addPhaseVersion(b, env.phaseVersion.toUInt())
        BattleCommandEnvelope.addActionSeq(b, env.actionSeq.toUInt())
        BattleCommandEnvelope.addPayloadType(b, type)
        if (union != 0) BattleCommandEnvelope.addPayload(b, UnionOffset(union))
        val root = BattleCommandEnvelope.endBattleCommandEnvelope(b)
        BattleCommandEnvelope.finishBattleCommandEnvelopeBuffer(b, root)
        return b.sizedByteArray()
    }

    // ---------------- 即时操作 ----------------

    fun decodeInstant(bytes: ByteArray): Result<BattleCodec.InstantRequest> = runCatching {
        if (bytes.size > MAX_BYTES) throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.TooLarge("body"))
        if (SceneFlatCodec.identifierOf(bytes) != IDENT_INSTANT) throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.NotAnObject)
        val r = BattleInstantRequest.asRoot(ArrayReadWriteBuffer(bytes))
        val version = r.protocolVersion.toInt()
        if (version != BattleCodec.PROTOCOL_VERSION) throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.UnsupportedVersion(version))
        val requestId = r.requestId?.takeIf { it.isNotEmpty() } ?: throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.MissingField("request_id"))
        val op = if (r.op == InstantOp.Surrender) BattleCodec.OP_SURRENDER else "UNKNOWN_${r.op.value}"
        BattleCodec.InstantRequest(requestId, r.battleId.toLong(), r.roleId.toLong(), r.stateVersion.toLong(), op)
    }.recoverCatching { e -> if (e is BattleCodec.DecodeFailure) throw e else throw BattleCodec.DecodeFailure(BattleCodec.DecodeError.NotAnObject) }

    fun encodeInstantAck(battleId: Long, stateVersion: Long, phase: String): ByteArray {
        val b = FlatBufferBuilder(64)
        val root = BattleInstantAck.createBattleInstantAck(b, BattleCodec.PROTOCOL_VERSION.toUInt(), battleId.toULong(), stateVersion.toULong(), phaseTo(phase))
        BattleInstantAck.finishBattleInstantAckBuffer(b, root)
        return b.sizedByteArray()
    }

    fun encodeInstant(r: BattleCodec.InstantRequest): ByteArray {
        val b = FlatBufferBuilder(64)
        val id = b.createString(r.requestId)
        val root = BattleInstantRequest.createBattleInstantRequest(b, BattleCodec.PROTOCOL_VERSION.toUInt(), id, r.battleId.toULong(), r.roleId.toULong(), r.stateVersion.toULong(), InstantOp.Surrender)
        BattleInstantRequest.finishBattleInstantRequestBuffer(b, root)
        return b.sizedByteArray()
    }

    // ---------------- 事件批(MBE1)----------------

    /**
     * 把 outbox 里同 visibility / 同接收者的事件编成一批。payload 是 JSON 单键对象
     * (与 [BattleCodec] 的镜像同名),这里逐个落成 `EventPayload` union。
     */
    fun encodeEventBatch(battleId: Long, events: List<MmoBattleEvent>): ByteArray {
        require(events.isNotEmpty())
        val b = FlatBufferBuilder(1024)
        val offsets = events.map { e ->
            val obj = Json.parseToJsonElement(e.payload).jsonObject
            val (key, body) = obj.entries.first().let { it.key to it.value.jsonObject }
            val (type, union) = when (key) {
                "damage_dealt" -> {
                    val targets = body["resolved_target_ids"]!!.jsonArray.map { it.jsonPrimitive.long.toULong() }.toULongArray()
                    val amounts = body["amounts"]!!.jsonArray.map { it.jsonPrimitive.long }.toLongArray()
                    val reason = RetargetReason.entries.firstOrNull { it.name == body["retarget_reason"]?.jsonPrimitive?.content }?.ordinal ?: 0
                    EventPayload.DamageDealt to DamageDealt.createDamageDealt(
                        b, body["source_actor_id"]!!.jsonPrimitive.long.toULong(), DamageDealt.createResolvedTargetIdsVector(b, targets),
                        DamageDealt.createAmountsVector(b, amounts), privchat.mmorpg.battle.RetargetReason(reason.toUByte()),
                    ).value
                }
                "phase_changed" -> EventPayload.PhaseChanged to PhaseChanged.createPhaseChanged(
                    b, phaseTo(body["from"]!!.jsonPrimitive.content), phaseTo(body["to"]!!.jsonPrimitive.content),
                    body["round"]!!.jsonPrimitive.int.toUInt(), body["deadline_at_ms"]!!.jsonPrimitive.long.toULong(),
                ).value
                "command_accepted" -> EventPayload.CommandAccepted to CommandAccepted.createCommandAccepted(
                    b, body["command_slot_id"]!!.jsonPrimitive.long.toULong(), body["accepted_action_seq"]!!.jsonPrimitive.int.toUInt(),
                ).value
                "slots_offered" -> {
                    val slots = body["slots"]!!.jsonArray.map { s -> encodeSlot(b, s.jsonObject) }
                    SlotsOffered.startSlotsVector(b, slots.size)
                    for (s in slots.asReversed()) b.add(s)
                    val vec = b.endVector<SlotInfo>()
                    EventPayload.SlotsOffered to SlotsOffered.createSlotsOffered(b, vec).value
                }
                "actor_died" -> EventPayload.ActorDied to ActorDied.createActorDied(b, body["actor_id"]!!.jsonPrimitive.long.toULong()).value
                "initiative_resolved" -> EventPayload.InitiativeResolved to InitiativeResolved.createInitiativeResolved(
                    b, InitiativeResolved.createOrderVector(b, body["order"]!!.jsonArray.map { it.jsonPrimitive.long.toULong() }.toULongArray()),
                    body["rng_algorithm_version"]!!.jsonPrimitive.int.toUInt(), body["rng_cursor"]!!.jsonPrimitive.long.toULong(),
                ).value
                "battle_settled" -> {
                    val sid = b.createString(body["settlement_request_id"]!!.jsonPrimitive.content)
                    EventPayload.BattleSettled to BattleSettled.createBattleSettled(b, sid, body["winner_side"]!!.jsonPrimitive.int.toUByte()).value
                }
                else -> error("unknown battle event payload '$key'")
            }
            val requestId = b.createString(e.requestId)
            BattleEvent.startBattleEvent(b)
            BattleEvent.addEventId(b, e.id.toULong())
            BattleEvent.addStreamSeq(b, e.streamSeq.toULong())
            BattleEvent.addResultingStateVersion(b, e.stateVersion.toULong())
            BattleEvent.addCritical(b, e.critical == 1)
            BattleEvent.addRequestId(b, requestId)
            BattleEvent.addServerTimeMs(b, e.serverTimeMs.toULong())
            BattleEvent.addDefaultActionApplied(b, e.defaultActionApplied == 1)
            BattleEvent.addAutoPlayed(b, false)
            BattleEvent.addControlState(b, privchat.mmorpg.battle.ControlState.Manual)
            BattleEvent.addPayloadType(b, type)
            BattleEvent.addPayload(b, UnionOffset(union))
            BattleEvent.endBattleEvent(b)
        }
        BattleEventBatchEnvelope.startEventsVector(b, offsets.size)
        for (o in offsets.asReversed()) b.add(o)
        val vec = b.endVector<BattleEvent>()
        val first = events.first()
        BattleEventBatchEnvelope.startBattleEventBatchEnvelope(b)
        BattleEventBatchEnvelope.addProtocolVersion(b, BattleCodec.PROTOCOL_VERSION.toUInt())
        BattleEventBatchEnvelope.addBattleId(b, battleId.toULong())
        BattleEventBatchEnvelope.addRound(b, events.last().round.toUInt())
        BattleEventBatchEnvelope.addVisibility(b, if (first.visibility == BattleCodec.VISIBILITY_PUBLIC) Visibility.Public else Visibility.Private)
        BattleEventBatchEnvelope.addRecipientRoleId(b, first.recipientRoleId.toULong())
        BattleEventBatchEnvelope.addBatchId(b, first.id.toULong())
        BattleEventBatchEnvelope.addChunkIndex(b, 0u)
        BattleEventBatchEnvelope.addChunkCount(b, 1u)
        BattleEventBatchEnvelope.addFirstStreamSeq(b, first.streamSeq.toULong())
        BattleEventBatchEnvelope.addLastStreamSeq(b, events.last().streamSeq.toULong())
        BattleEventBatchEnvelope.addEvents(b, vec)
        val root = BattleEventBatchEnvelope.endBattleEventBatchEnvelope(b)
        BattleEventBatchEnvelope.finishBattleEventBatchEnvelopeBuffer(b, root)
        return b.sizedByteArray()
    }

    private fun encodeSlot(b: FlatBufferBuilder, s: JsonObject): com.google.flatbuffers.kotlin.Offset<SlotInfo> {
        val kinds = s["allowed_commands"]!!.jsonArray.map { CommandKind.entries.first { k -> k.name == it.jsonPrimitive.content }.ordinal.toUByte() }.toUByteArray()
        SlotInfo.startAllowedCommandsVector(b, kinds.size)
        for (k in kinds.reversed()) b.add(k)
        val allowed = b.endVector<UByte>()
        val slotKind = SlotKind(SlotKind.names.indexOf(s["slot_kind"]!!.jsonPrimitive.content).coerceAtLeast(0).toUByte())
        SlotInfo.startSlotInfo(b)
        SlotInfo.addCommandSlotId(b, s["command_slot_id"]!!.jsonPrimitive.long.toULong())
        SlotInfo.addActorId(b, s["actor_id"]!!.jsonPrimitive.long.toULong())
        SlotInfo.addSlotKind(b, slotKind)
        SlotInfo.addAllowedCommands(b, allowed)
        SlotInfo.addRequired(b, s["required"]?.jsonPrimitive?.booleanOrNull ?: true)
        SlotInfo.addDeadlineAtMs(b, s["deadline_at_ms"]!!.jsonPrimitive.long.toULong())
        SlotInfo.addAcceptedActionSeq(b, (s["accepted_action_seq"]?.jsonPrimitive?.intOrNull ?: 0).toUInt())
        return SlotInfo.endSlotInfo(b)
    }

    /** 解码成与 [BattleCodec.encodeEvent] 同形的 JSON(测试与工具用),便于按 payload 键断言。 */
    fun decodeEventBatch(bytes: ByteArray): JsonObject? = runCatching {
        if (SceneFlatCodec.identifierOf(bytes) != IDENT_EVENT_BATCH) return null
        val env = BattleEventBatchEnvelope.asRoot(ArrayReadWriteBuffer(bytes))
        buildJsonObject {
            put("battle_id", env.battleId.toLong())
            put("round", env.round.toInt())
            put("visibility", if (env.visibility == Visibility.Public) "PUBLIC" else "PRIVATE")
            put("recipient_role_id", env.recipientRoleId.toLong())
            put("first_stream_seq", env.firstStreamSeq.toLong())
            put("last_stream_seq", env.lastStreamSeq.toLong())
            put("events", buildJsonArray {
                for (i in 0 until env.eventsLength) {
                    val ev = env.events(i)!!
                    add(buildJsonObject {
                        put("event_id", ev.eventId.toLong()); put("stream_seq", ev.streamSeq.toLong()); put("resulting_state_version", ev.resultingStateVersion.toLong())
                        put("critical", ev.critical); put("request_id", ev.requestId ?: ""); put("server_time_ms", ev.serverTimeMs.toLong())
                        put("default_action_applied", ev.defaultActionApplied)
                        put("payload", decodePayload(ev))
                    })
                }
            })
        }
    }.getOrNull()

    private fun decodePayload(ev: BattleEvent): JsonObject = when (ev.payloadType) {
        EventPayload.DamageDealt -> (ev.payload(DamageDealt()) as DamageDealt).let { d ->
            BattleCodec.damageDealt(d.sourceActorId.toLong(), (0 until d.resolvedTargetIdsLength).map { d.resolvedTargetIds(it).toLong() }, (0 until d.amountsLength).map { d.amounts(it) }, RetargetReason.entries[d.retargetReason.value.toInt()])
        }
        EventPayload.PhaseChanged -> (ev.payload(PhaseChanged()) as PhaseChanged).let { p ->
            BattleCodec.phaseChanged(BattlePhase.entries[p.from.value.toInt()], BattlePhase.entries[p.to.value.toInt()], p.round.toInt(), p.deadlineAtMs.toLong())
        }
        EventPayload.CommandAccepted -> (ev.payload(CommandAccepted()) as CommandAccepted).let { BattleCodec.commandAccepted(it.commandSlotId.toLong(), it.acceptedActionSeq.toInt()) }
        EventPayload.SlotsOffered -> (ev.payload(SlotsOffered()) as SlotsOffered).let { so ->
            buildJsonObject {
                put("slots_offered", buildJsonObject {
                    put("slots", buildJsonArray {
                        for (i in 0 until so.slotsLength) {
                            val s = so.slots(i)!!
                            add(buildJsonObject {
                                put("command_slot_id", s.commandSlotId.toLong()); put("actor_id", s.actorId.toLong())
                                put("slot_kind", SlotKind.name(s.slotKind))
                                put("allowed_commands", buildJsonArray { for (j in 0 until s.allowedCommandsLength) add(kotlinx.serialization.json.JsonPrimitive(CommandKind.entries[s.allowedCommands(j).value.toInt()].name)) })
                                put("required", s.required); put("deadline_at_ms", s.deadlineAtMs.toLong()); put("accepted_action_seq", s.acceptedActionSeq.toInt())
                            })
                        }
                    })
                })
            }
        }
        EventPayload.ActorDied -> BattleCodec.actorDied((ev.payload(ActorDied()) as ActorDied).actorId.toLong())
        EventPayload.InitiativeResolved -> (ev.payload(InitiativeResolved()) as InitiativeResolved).let { r ->
            BattleCodec.initiativeResolved((0 until r.orderLength).map { r.order(it).toLong() }, r.rngAlgorithmVersion.toInt(), r.rngCursor.toLong())
        }
        EventPayload.BattleSettled -> (ev.payload(BattleSettled()) as BattleSettled).let { BattleCodec.battleSettled(it.settlementRequestId ?: "", it.winnerSide.toInt()) }
        else -> buildJsonObject { put("_unknown", buildJsonObject { put("type", ev.payloadType.value.toInt()) }) }
    }
}
