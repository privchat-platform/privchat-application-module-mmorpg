package dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HTTP 侧的战斗 DTO（MMO_BATTLE_PROTOCOL_SPEC §15.1）。字段名跟协议走（snake_case）。 */
@Serializable
data class BattleStartRequest(
    @SerialName("npc_id") val npcId: Long,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class BattleEntryResponse(
    @SerialName("transition_id") val transitionId: Long,
    /** PENDING / READY / FAILED */
    val status: String,
    @SerialName("battle_id") val battleId: Long,
    /** snowflake，字符串传输（同场景 channel_id）。 */
    @SerialName("channel_id") val channelId: String,
    val ticket: String,
    val exp: Long,
    @SerialName("scene_session_id") val sceneSessionId: Long,
    val reason: String = "",
)

/** `BattleSnapshotEnvelope`（battle_snapshot.fbs）的 JSON 镜像。 */
@Serializable
data class BattleSnapshotResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("supported_protocol_versions") val supportedProtocolVersions: List<Int>,
    @SerialName("state_version") val stateVersion: Long,
    @SerialName("public_event_seq") val publicEventSeq: Long,
    @SerialName("battle_id") val battleId: Long,
    val round: Int,
    val phase: String,
    @SerialName("phase_version") val phaseVersion: Long,
    @SerialName("round_deadline_at_ms") val roundDeadlineAtMs: Long,
    val actors: List<ActorPublicStateDto>,
    @SerialName("initiative_order") val initiativeOrder: List<Long>,
    @SerialName("rng_algorithm_version") val rngAlgorithmVersion: Int,
    @SerialName("private_event_seq") val privateEventSeq: Long,
    @SerialName("recipient_role_id") val recipientRoleId: Long,
    @SerialName("open_slots") val openSlots: List<SlotInfoDto>,
    @SerialName("submitted_commands") val submittedCommands: List<SubmittedCommandDto>,
    @SerialName("private_actor_states") val privateActorStates: List<ActorPrivateStateDto>,
    /** 镜像之外的便利字段：单位名字与阵营，客户端画战斗界面用。 */
    @SerialName("actor_names") val actorNames: Map<String, String>,
    @SerialName("winner_side") val winnerSide: Int,
)

@Serializable
data class ActorPublicStateDto(
    @SerialName("actor_id") val actorId: Long,
    @SerialName("owner_role_id") val ownerRoleId: Long,
    val alive: Boolean,
    val position: Int,
    val side: Int,
    @SerialName("hp_percent") val hpPercent: Int,
    @SerialName("mp_percent") val mpPercent: Int,
)

@Serializable
data class SlotInfoDto(
    @SerialName("command_slot_id") val commandSlotId: Long,
    @SerialName("actor_id") val actorId: Long,
    @SerialName("slot_kind") val slotKind: String,
    @SerialName("allowed_commands") val allowedCommands: List<String>,
    val required: Boolean,
    @SerialName("deadline_at_ms") val deadlineAtMs: Long,
    @SerialName("accepted_action_seq") val acceptedActionSeq: Int,
)

@Serializable
data class SubmittedCommandDto(
    @SerialName("command_slot_id") val commandSlotId: Long,
    @SerialName("actor_id") val actorId: Long,
    @SerialName("accepted_action_seq") val acceptedActionSeq: Int,
    /** 规范化载荷的 JSON 文本（单键对象）。 */
    val payload: String,
)

@Serializable
data class ActorPrivateStateDto(
    @SerialName("actor_id") val actorId: Long,
    @SerialName("exact_hp") val exactHp: Long,
    @SerialName("exact_mp") val exactMp: Long,
    @SerialName("max_hp") val maxHp: Long,
    @SerialName("max_mp") val maxMp: Long,
    @SerialName("selectable_target_ids") val selectableTargetIds: List<Long>,
)

@Serializable
data class AdminBattleAbortRequest(val reason: String = "")

@Serializable
data class AdminBattleAbortResponse(@SerialName("battle_id") val battleId: Long, val aborted: Boolean)
