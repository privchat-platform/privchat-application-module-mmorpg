package logic.codec

/**
 * PUBLIC 场景事件的常量。线格式是 `SceneEventBatchEnvelope`(MSE1,FlatBuffers),
 * 编码在 [SceneFlatCodec];这里只剩 topic 与事件名(SceneService 用它区分进/出)。
 */
object ScenePublicEventCodec {
    /** Room publish 的 topic(MMO_WORLD_SCENE_SPEC §9.0)。 */
    const val TOPIC: String = "mmorpg.scene.public"
    const val EVENT_ROLE_ENTERED: String = "scene.role_entered"
    const val EVENT_ROLE_LEFT: String = "scene.role_left"
    const val EVENT_MOVEMENT_STARTED: String = "scene.movement_started"
}
