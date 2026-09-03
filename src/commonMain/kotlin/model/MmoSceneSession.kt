package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Column
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * 一个角色在某个场景中的会话。
 *
 * 这是 Transfer 鉴权的依据：heartbeat 携带 `scene_session_id`，服务端据此确认
 * 「这个角色确实在这个场景里，而且这条 transfer 来自它所在的那个 channel」。
 * 没有它就只能相信客户端自报的场景身份，而 MMO_WORLD_SCENE_SPEC 明确禁止这点。
 *
 * `sessionEpoch` 用于让旧会话立即失效：角色重新 enter 同一场景时递增，断线前那条
 * 会话的 heartbeat 会被判定为过期，不会和新会话抢同一份状态。
 *
 * 它与 [logic.scene.SceneRef.generation] 不同名是刻意的——后者是「场景被重建」，
 * 前者是「玩家重进」。两者曾经共用 "generation" 一词，读代码时无法区分一次
 * mismatch 到底该报 21602 还是 21601。
 */
@Serializable
@Table("mmo_scene_session")
data class MmoSceneSession(
    /** session id 本身，签发给客户端并在 heartbeat 中回传。 */
    @Id
    val id: Long = 0,

    @Column(name = "role_id")
    val roleId: Long,

    /** 场景引用，如 "world-1" / "map-forest"。业务标识，不是 channel。 */
    @Column(name = "scene_ref")
    val sceneRef: String,

    /** 该场景对应的真实 Room channel，由 privchat-server 签发。 */
    @Column(name = "channel_id")
    val channelId: Long,

    /** 同一 (role, scene) 每次重新进入递增，旧会话据此失效。 */
    @Column(name = "session_epoch")
    val sessionEpoch: Long = 1,

    /** 1 = 在场，0 = 已离开。 */
    val status: Int = 1,

    @Column(name = "last_seen_at")
    val lastSeenAt: Long = 0,

    // ---- 移动的权威状态（V002，spec §4）----
    // 存"当前路径"而不是"当前坐标"：坐标每帧都在变，路径只在意图被受理时变。
    // 任意时刻的位置由 start/target/path_start_ms/speed 按定点数学推算，见
    // logic.scene.SceneMovement。

    /** 该 session 已受理的最大 movement_seq；Stop / Cancel 同样占用新值。 */
    @Column(name = "movement_seq")
    val movementSeq: Long = 0,

    /** 每次权威状态变化递增，下行事件靠它按版本覆盖而非连续重放。 */
    @Column(name = "entity_version")
    val entityVersion: Long = 0,

    @Column(name = "path_id")
    val pathId: Long = 0,

    @Column(name = "start_x") val startX: Int = 0,
    @Column(name = "start_y") val startY: Int = 0,
    @Column(name = "target_x") val targetX: Int = 0,
    @Column(name = "target_y") val targetY: Int = 0,

    /** 路径开始的服务端时刻（Unix ms）；0 = 从未移动，位置即 start。 */
    @Column(name = "path_start_ms")
    val pathStartMs: Long = 0,

    /** 定点：1 = 1/1000 世界单位每秒；0 = 静止。 */
    val speed: Int = 0,

    /**
     * 当前路径的点列（JSON `[{"x":..,"y":..},...]`，不含起点）。寻路绕障碍时是多段；
     * `target_x/y` 保留为最后一个点，便于查询。
     */
    @Column(name = "path_points")
    val pathPoints: String = "[]",

    @CreatedAt
    val createdAt: Long? = null,

    @UpdatedAt
    val updatedAt: Long? = null,
)
