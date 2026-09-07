package logic.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logic.battle.BattleCommand
import logic.battle.BattlePhase
import logic.battle.CommandKind
import logic.battle.RetargetReason
import model.MmoBattleEvent
import model.MmoBattleSlot

/**
 * 战斗 Transfer 线格式：`battle_*.fbs` 的 JSON 镜像（MMO_BATTLE_PROTOCOL_SPEC §15.4）。
 *
 * 字段名与 IDL 一一对应；union 以单键对象表达（`{"attack":{"selected_target_id":9}}`），
 * 枚举以 schema 里的名字传输。切 FlatBuffers 时只换这一层，VALIDATION.md 的 V-B*
 * 规则与判定顺序不变——它们在 [logic.battle.BattleService] 里，不在这里。
 */
object BattleCodec {
    const val PROTOCOL_VERSION: Int = 1
    const val TOPIC_PUBLIC: String = "mmorpg.battle.public"
    const val MAX_REQUEST_ID_BYTES: Int = 64

    // ---------------- 上行：BattleCommandEnvelope（MBC1）----------------

    data class CommandEnvelope(
        val requestId: String,
        val battleId: Long,
        val roleId: Long,
        val actorId: Long,
        val commandSlotId: Long,
        val round: Int,
        val phase: String,
        val phaseVersion: Long,
        val actionSeq: Int,
        /** null = `CommandPayload_NONE`（V-BC1，由 service 拒 21407）。 */
        val payload: BattleCommand?,
        /** 规范化载荷（幂等比较用）。 */
        val payloadJson: String,
    )

    sealed interface DecodeError {
        data object NotAnObject : DecodeError
        data class UnsupportedVersion(val got: Int) : DecodeError
        data class MissingField(val name: String) : DecodeError
        data class TooLarge(val what: String) : DecodeError
    }

    class DecodeFailure(val error: DecodeError) : Exception(error.toString())

    /** `CommandPayload` union 的单键对象 → 指令；未知键 / 空对象 → null。 */
    fun decodeCommandPayload(obj: JsonObject): BattleCommand? {
        val (key, value) = obj.entries.firstOrNull() ?: return null
        val body = value as? JsonObject ?: JsonObject(emptyMap())
        return when (key) {
            "attack" -> BattleCommand.Attack(body.long("selected_target_id") ?: 0)
            "defend" -> BattleCommand.Defend
            "escape" -> BattleCommand.Escape
            "wait" -> BattleCommand.Wait
            "cast_skill" -> BattleCommand.Unsupported(CommandKind.CAST_SKILL)
            "use_item" -> BattleCommand.Unsupported(CommandKind.USE_ITEM)
            "protect" -> BattleCommand.Unsupported(CommandKind.PROTECT)
            "summon" -> BattleCommand.Unsupported(CommandKind.SUMMON)
            "recall" -> BattleCommand.Unsupported(CommandKind.RECALL)
            "capture" -> BattleCommand.Unsupported(CommandKind.CAPTURE)
            else -> null
        }
    }

    fun decodeCommandPayload(json: String): BattleCommand? =
        runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()?.let { decodeCommandPayload(it) }

    fun encodeCommandPayload(command: BattleCommand): JsonObject = buildJsonObject {
        when (command) {
            is BattleCommand.Attack -> put("attack", buildJsonObject { put("selected_target_id", command.targetId) })
            BattleCommand.Defend -> put("defend", JsonObject(emptyMap()))
            BattleCommand.Escape -> put("escape", JsonObject(emptyMap()))
            BattleCommand.Wait -> put("wait", JsonObject(emptyMap()))
            is BattleCommand.Unsupported -> put(command.kind.name.lowercase(), JsonObject(emptyMap()))
        }
    }

    // ---------------- 下行：BattleCommandAck（MBA1）----------------

    data class Ack(
        val battleId: Long,
        val commandSlotId: Long,
        val acceptedActionSeq: Int,
        val replacedPrevious: Boolean,
        val round: Int,
        val phase: String,
        val phaseVersion: Long,
        val slotDeadlineAtMs: Long,
    )

    fun encodeAck(ack: Ack): JsonObject = buildJsonObject {
        put("protocol_version", PROTOCOL_VERSION)
        put("battle_id", ack.battleId)
        put("command_slot_id", ack.commandSlotId)
        put("accepted_action_seq", ack.acceptedActionSeq)
        put("replaced_previous", ack.replacedPrevious)
        put("round", ack.round)
        put("phase", ack.phase)
        put("phase_version", ack.phaseVersion)
        put("slot_deadline_at_ms", ack.slotDeadlineAtMs)
    }

    fun decodeAck(json: String): Ack? {
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        return Ack(
            battleId = obj.long("battle_id") ?: return null,
            commandSlotId = obj.long("command_slot_id") ?: return null,
            acceptedActionSeq = obj.int("accepted_action_seq") ?: return null,
            replacedPrevious = obj["replaced_previous"]?.jsonPrimitive?.booleanOrNull ?: false,
            round = obj.int("round") ?: return null,
            phase = obj.string("phase") ?: return null,
            phaseVersion = obj.long("phase_version") ?: return null,
            slotDeadlineAtMs = obj.long("slot_deadline_at_ms") ?: 0,
        )
    }

    // ---------------- 即时权威操作（§7 / §15.1 `mmorpg/battle/instant`）----------------

    data class InstantRequest(val requestId: String, val battleId: Long, val roleId: Long, val stateVersion: Long, val op: String)

    const val OP_SURRENDER: String = "SURRENDER"

    // ---------------- 下行：BattleEventBatchEnvelope（MBE1）----------------

    /** `EventPayload` 的各成员（单键对象）。 */
    fun phaseChanged(from: BattlePhase, to: BattlePhase, round: Int, deadlineAtMs: Long): JsonObject = buildJsonObject {
        put("phase_changed", buildJsonObject { put("from", from.name); put("to", to.name); put("round", round); put("deadline_at_ms", deadlineAtMs) })
    }

    fun initiativeResolved(order: List<Long>, rngVersion: Int, rngCursor: Long): JsonObject = buildJsonObject {
        put("initiative_resolved", buildJsonObject {
            put("order", longs(order)); put("rng_algorithm_version", rngVersion); put("rng_cursor", rngCursor)
        })
    }

    fun damageDealt(source: Long, targets: List<Long>, amounts: List<Long>, reason: RetargetReason): JsonObject = buildJsonObject {
        put("damage_dealt", buildJsonObject {
            put("source_actor_id", source); put("resolved_target_ids", longs(targets)); put("amounts", longs(amounts)); put("retarget_reason", reason.name)
        })
    }

    fun actorDied(actorId: Long): JsonObject = buildJsonObject { put("actor_died", buildJsonObject { put("actor_id", actorId) }) }

    fun commandAccepted(slotId: Long, acceptedSeq: Int): JsonObject = buildJsonObject {
        put("command_accepted", buildJsonObject { put("command_slot_id", slotId); put("accepted_action_seq", acceptedSeq) })
    }

    fun slotsOffered(slots: List<MmoBattleSlot>): JsonObject = buildJsonObject {
        put("slots_offered", buildJsonObject { put("slots", JsonArray(slots.map { slotInfo(it) })) })
    }

    fun battleSettled(settlementRequestId: String, winnerSide: Int): JsonObject = buildJsonObject {
        put("battle_settled", buildJsonObject { put("settlement_request_id", settlementRequestId); put("winner_side", winnerSide) })
    }

    fun slotInfo(slot: MmoBattleSlot): JsonObject = buildJsonObject {
        put("command_slot_id", slot.id)
        put("actor_id", slot.actorId)
        put("slot_kind", slot.slotKind)
        put("allowed_commands", runCatching { Json.parseToJsonElement(slot.allowedCommands) }.getOrDefault(JsonArray(emptyList())))
        put("required", slot.isRequired == 1)
        put("deadline_at_ms", slot.deadlineAtMs)
        put("accepted_action_seq", slot.acceptedActionSeq)
    }

    const val VISIBILITY_PUBLIC: String = "PUBLIC"
    const val VISIBILITY_PRIVATE: String = "PRIVATE"

    // ---------------- 内部 ----------------


    private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.let { it.longOrNull ?: it.content.toLongOrNull() }
    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    private fun longs(values: List<Long>): JsonElement = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
