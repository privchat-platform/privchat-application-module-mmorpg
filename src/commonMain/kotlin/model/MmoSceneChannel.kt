package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Column
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * `scene_ref` ↔ 真实 Room `channel_id` 的索引。
 *
 * 与 `privchat_business_channel` 的分工（同 module-game 的做法）：
 * - 本表 = mmorpg 自己的业务索引，按 scene_ref 找 channel
 * - `privchat_business_channel` = dispatch 路由表，按 channel 找 service
 *
 * 两者都要写，但 owner 不同：本表由本模块 migration 建，路由表只能通过
 * `PrivchatBusinessChannelResolver.bind()` 写，不手写跨 owner SQL。
 */
@Serializable
@Table("mmo_scene_channel")
data class MmoSceneChannel(
    @Id
    val id: Long = 0,

    @Column(name = "scene_ref")
    val sceneRef: String,

    @Column(name = "channel_id")
    val channelId: Long,

    /** 场景挂在哪张地图上（`mmo_map.id`）。 */
    @Column(name = "map_id")
    val mapId: Long = 1,

    val status: Int = 1,

    @CreatedAt
    val createdAt: Long? = null,

    @UpdatedAt
    val updatedAt: Long? = null,
)
