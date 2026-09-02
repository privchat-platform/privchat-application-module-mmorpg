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

    @CreatedAt
    val createdAt: Long? = null,

    @UpdatedAt
    val updatedAt: Long? = null,
)
