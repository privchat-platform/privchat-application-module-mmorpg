package logic.codec

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PUBLIC 场景事件的线格式（下行，Room Publish，topic `mmorpg.scene.public`）。
 *
 * 与 [SceneHeartbeatCodec] 同一条版本轴：换 FlatBuffers 时两边一起换，不允许
 * 上下行分别演进——那会出现客户端能发新协议却读不懂回包的半吊子状态。
 *
 * `seq` 是**每场景单调递增**的公共事件序号。客户端靠它发现自己漏收了广播：
 * 收到不连续的 seq 就去拉 snapshot，而不是假装没事继续跑。缺了它，一次丢包
 * 会让客户端的场景状态永久性地和服务端错开，且双方都不知情。
 */
object ScenePublicEventCodec {

    /** Room publish 的 topic（MMO_WORLD_SCENE_SPEC §9.0）。 */
    const val TOPIC: String = "mmorpg.scene.public"

    /** 角色进入场景。 */
    const val EVENT_ROLE_ENTERED: String = "scene.role_entered"

    /** 角色离开场景。 */
    const val EVENT_ROLE_LEFT: String = "scene.role_left"

    /** 一段权威移动开始（`MovementStarted`）；客户端沿同一路径本地插值。 */
    const val EVENT_MOVEMENT_STARTED: String = "scene.movement_started"

    fun encodePresence(
        event: String,
        sceneRef: String,
        seq: Long,
        roleId: Long,
        roleName: String,
        serverTimeMs: Long,
    ): String = buildJsonObject {
        put("protocol_version", SceneHeartbeatCodec.PROTOCOL_VERSION)
        put("topic", TOPIC)
        put("event", event)
        put("scene_ref", sceneRef)
        put("seq", seq)
        put("role_id", roleId)
        put("role_name", roleName)
        put("server_time_ms", serverTimeMs)
    }.toString()
}
