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

@Serializable
data class SceneEnterResponse(
    @SerialName("scene_ref") val sceneRef: String,
    @SerialName("channel_id") val channelId: Long,
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
data class ScenePresentRole(
    @SerialName("role_id") val roleId: Long,
    @SerialName("role_name") val roleName: String,
)

@Serializable
data class ScenePublicSnapshotResponse(
    @SerialName("scene_ref") val sceneRef: String,
    @SerialName("public_scene_seq") val publicSceneSeq: Long,
    val roles: List<ScenePresentRole>,
)

@Serializable
data class ScenePrivateSnapshotResponse(
    @SerialName("scene_ref") val sceneRef: String,
    @SerialName("role_id") val roleId: Long,
    @SerialName("scene_session_id") val sceneSessionId: Long,
    @SerialName("session_epoch") val sessionEpoch: Long,
    @SerialName("channel_id") val channelId: Long,
    @SerialName("last_seen_at") val lastSeenAt: Long,
    @SerialName("public_scene_seq") val publicSceneSeq: Long,
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
