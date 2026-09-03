package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Column
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * 一场战斗的权威头（MMO_BATTLE_PROTOCOL_SPEC §15.3）。
 *
 * 阶段、回合、版本号与两条事件序号都在这一行上，所以一次回合结算只需要对这一行
 * 做一次乐观更新；actor / slot 的变更跟它同事务。`rngSeed` 只存不发。
 */
@Serializable
@Table("mmo_battle")
data class MmoBattle(
    @Id val id: Long = 0,
    @Column(name = "scene_ref") val sceneRef: String,
    @Column(name = "scene_session_id") val sceneSessionId: Long,
    /** v1 单玩家：发起者。 */
    @Column(name = "role_id") val roleId: Long,
    @Column(name = "channel_id") val channelId: Long = 0,
    val mode: String = "pve_basic",
    val phase: String = "CREATED",
    @Column(name = "round_no") val round: Int = 0,
    @Column(name = "phase_version") val phaseVersion: Long = 0,
    @Column(name = "state_version") val stateVersion: Long = 0,
    @Column(name = "public_event_seq") val publicEventSeq: Long = 0,
    @Column(name = "private_event_seq") val privateEventSeq: Long = 0,
    @Column(name = "rng_seed") val rngSeed: Long,
    @Column(name = "rng_cursor") val rngCursor: Long = 0,
    /** -1 未定；0 玩家；1 怪物；2 逃跑 / 中止，无胜者。 */
    @Column(name = "winner_side") val winnerSide: Int = -1,
    @Column(name = "deadline_at_ms") val deadlineAtMs: Long = 0,
    @Column(name = "initiative_order") val initiativeOrder: String = "[]",
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

/** 场景 → 战斗的切换记录；客户端断线后凭 id 续接（§15.2）。 */
@Serializable
@Table("mmo_battle_transition")
data class MmoBattleTransition(
    @Id val id: Long = 0,
    @Column(name = "role_id") val roleId: Long,
    @Column(name = "scene_session_id") val sceneSessionId: Long,
    @Column(name = "battle_id") val battleId: Long,
    val status: String = "PENDING",
    @Column(name = "channel_id") val channelId: Long = 0,
    val ticket: String = "",
    @Column(name = "ticket_exp") val ticketExp: Long = 0,
    val reason: String = "",
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

@Serializable
@Table("mmo_battle_actor")
data class MmoBattleActor(
    @Id val id: Long = 0,
    @Column(name = "battle_id") val battleId: Long,
    val side: Int,
    @Column(name = "owner_role_id") val ownerRoleId: Long = 0,
    val name: String,
    /** ROLE / MONSTER */
    val kind: String,
    val position: Int = 0,
    val hp: Long,
    @Column(name = "max_hp") val maxHp: Long,
    val mp: Long = 0,
    @Column(name = "max_mp") val maxMp: Long = 0,
    val atk: Long,
    val defense: Long,
    val speed: Int,
    val alive: Int = 1,
    @Column(name = "control_state") val controlState: String = "MANUAL",
    val defending: Int = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

/** 一个行动机会；`id` 即协议里的 `command_slot_id`。 */
@Serializable
@Table("mmo_battle_slot")
data class MmoBattleSlot(
    @Id val id: Long = 0,
    @Column(name = "battle_id") val battleId: Long,
    // 属性名与列名一致(round_no):DSL 的 KProperty 谓词按属性名推列名,不看 @Column。
    @Column(name = "round_no") val roundNo: Int,
    @Column(name = "actor_id") val actorId: Long,
    @Column(name = "slot_kind") val slotKind: String = "PRIMARY",
    @Column(name = "allowed_commands") val allowedCommands: String = "[]",
    @Column(name = "is_required") val isRequired: Int = 1,
    @Column(name = "deadline_at_ms") val deadlineAtMs: Long,
    @Column(name = "accepted_action_seq") val acceptedActionSeq: Int = 0,
    val payload: String = "",
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

@Serializable
@Table("mmo_battle_command")
data class MmoBattleCommand(
    @Id val id: Long = 0,
    @Column(name = "battle_id") val battleId: Long,
    @Column(name = "actor_id") val actorId: Long,
    @Column(name = "action_seq") val actionSeq: Int,
    @Column(name = "request_id") val requestId: String,
    @Column(name = "command_slot_id") val commandSlotId: Long,
    val payload: String,
    /** 首次 ACK 的 JSON，同 request_id 重发时原样回放。 */
    val ack: String,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

/** 事件 outbox；`id` 即协议里的 `event_id`。 */
@Serializable
@Table("mmo_battle_event")
data class MmoBattleEvent(
    @Id val id: Long = 0,
    @Column(name = "battle_id") val battleId: Long,
    @Column(name = "round_no") val round: Int,
    val visibility: String,
    @Column(name = "recipient_role_id") val recipientRoleId: Long = 0,
    @Column(name = "stream_seq") val streamSeq: Long,
    @Column(name = "state_version") val stateVersion: Long,
    val critical: Int = 1,
    @Column(name = "default_action_applied") val defaultActionApplied: Int = 0,
    @Column(name = "request_id") val requestId: String = "",
    @Column(name = "server_time_ms") val serverTimeMs: Long,
    /** `EventPayload` 的 JSON 镜像（单键对象）。 */
    val payload: String,
    @Column(name = "published_at") val publishedAt: Long = 0,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

@Serializable
@Table("mmo_battle_lease")
data class MmoBattleLease(
    @Id val id: Long = 0,
    @Column(name = "battle_id") val battleId: Long,
    @Column(name = "owner_node") val ownerNode: String,
    @Column(name = "owner_epoch") val ownerEpoch: Long = 1,
    @Column(name = "lease_until") val leaseUntil: Long,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

@Serializable
@Table("mmo_reward_settlement")
data class MmoRewardSettlement(
    @Id val id: Long = 0,
    @Column(name = "settlement_request_id") val settlementRequestId: String,
    @Column(name = "battle_id") val battleId: Long,
    @Column(name = "role_id") val roleId: Long,
    @Column(name = "winner_side") val winnerSide: Int,
    val status: String = "PENDING",
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
