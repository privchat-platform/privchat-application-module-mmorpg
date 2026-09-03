package dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HTTP 侧的场景 DTO。
 *
 * 与 `logic.scene` 的结果类型刻意分开：那些是领域对象，字段名跟着代码走；这些是
 * 对外契约，字段名跟着协议走（snake_case）。合并成一套的话，一次内部重命名就会
 * 悄悄改掉线格式。
 */

@Serializable
data class SceneEnterRequest(
    @SerialName("role_id") val roleId: Long,
    /** ticket 绑定到具体设备，换设备必须重新签发。 */
    @SerialName("device_id") val deviceId: String,
)

/**
 * 64 位 id 的线格式规则：可能超过 2^53 的 id（snowflake：`channel_id`）以**字符串**
 * 传输——JavaScript / GDScript 的 JSON 数字是 double，超过 2^53 就静默丢精度。
 * 会话、角色、路径这类自增计数 id 保持数字。
 */
@Serializable
data class SceneEnterResponse(
    @SerialName("scene_ref") val sceneRef: String,
    @SerialName("channel_id") val channelId: String,
    /** 回传给 heartbeat 的会话标识。 */
    @SerialName("scene_session_id") val sceneSessionId: Long,
    @SerialName("session_epoch") val sessionEpoch: Long,
    /** Room subscribe ticket，客户端用作 `SubscribeRequest.param`。 */
    val ticket: String,
    /** ticket 绝对过期时间（Unix 秒），客户端据此调度 refresh。 */
    val exp: Long,
)

@Serializable
data class SceneLeaveRequest(
    @SerialName("role_id") val roleId: Long,
)

@Serializable
data class SceneLeaveResponse(
    @SerialName("scene_ref") val sceneRef: String,
    @SerialName("role_id") val roleId: Long,
)

@Serializable
data class Vec2FixedDto(val x: Int, val y: Int)

@Serializable
data class MovementDto(
    @SerialName("path_id") val pathId: Long,
    @SerialName("authoritative_start_position") val start: Vec2FixedDto,
    @SerialName("path_points") val pathPoints: List<Vec2FixedDto>,
    @SerialName("start_time_ms") val startTimeMs: Long,
    val speed: Int,
)

/** `EntityState` 的 JSON 镜像 + 在途路径。 */
@Serializable
data class EntityStateDto(
    @SerialName("entity_id") val entityId: Long,
    @SerialName("entity_version") val entityVersion: Long,
    @SerialName("movement_seq") val movementSeq: Long,
    val position: Vec2FixedDto,
    val movement: MovementDto? = null,
)

@Serializable
data class ScenePresentRole(
    @SerialName("role_id") val roleId: Long,
    @SerialName("role_name") val roleName: String,
    val state: EntityStateDto,
)

@Serializable
data class NpcDto(
    @SerialName("npc_id") val npcId: Long,
    val name: String,
    val kind: String,
    val position: Vec2FixedDto,
    @SerialName("interact_range") val interactRange: Int,
)

@Serializable
data class ScenePublicSnapshotResponse(
    @SerialName("scene_ref") val sceneRef: String,
    @SerialName("public_scene_seq") val publicSceneSeq: Long,
    @SerialName("server_time_ms") val serverTimeMs: Long,
    @SerialName("navigation_version") val navigationVersion: Int,
    @SerialName("map_id") val mapId: Long,
    val roles: List<ScenePresentRole>,
    val npcs: List<NpcDto>,
)

/** `GET /app/mmo/maps/{map_id}`：地图静态数据，客户端拉一次缓存。 */
@Serializable
data class MapResponse(
    @SerialName("map_id") val mapId: Long,
    val name: String,
    @SerialName("width_cells") val widthCells: Int,
    @SerialName("height_cells") val heightCells: Int,
    @SerialName("cell_size") val cellSize: Int,
    /** 每行一个字符串，'.' 可走 '#' 阻挡。 */
    val rows: List<String>,
    val spawn: Vec2FixedDto,
    @SerialName("navigation_version") val navigationVersion: Int,
)

@Serializable
data class ScenePrivateSnapshotResponse(
    @SerialName("scene_ref") val sceneRef: String,
    @SerialName("role_id") val roleId: Long,
    @SerialName("scene_session_id") val sceneSessionId: Long,
    @SerialName("session_epoch") val sessionEpoch: Long,
    @SerialName("channel_id") val channelId: String,
    @SerialName("last_seen_at") val lastSeenAt: Long,
    @SerialName("public_scene_seq") val publicSceneSeq: Long,
    @SerialName("self_entity") val self: EntityStateDto,
)

@Serializable
data class RoleCreateRequest(
    val name: String,
)

@Serializable
data class RoleResponse(
    @SerialName("role_id") val roleId: Long,
    val name: String,
)

// ---- 后台 ----

@Serializable
data class AdminSceneOpenRequest(
    @SerialName("scene_ref") val sceneRef: String,
    @SerialName("map_id") val mapId: Long = 1,
)

@Serializable
data class AdminSceneRow(
    @SerialName("scene_ref") val sceneRef: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("map_id") val mapId: Long,
    /** 1 = 开放，0 = 已关闭。 */
    val status: Int,
)
