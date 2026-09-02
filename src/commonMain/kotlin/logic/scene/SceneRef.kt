package logic.scene

/**
 * 场景引用。线格式由 MMO_WORLD_SCENE_SPEC §9.0 冻结为 `{kind}-{id}-{generation}`，
 * 例如 `l-10023-7`（分线）、`i-88401-1`（副本实例）。
 *
 * `generation` 是**分线/实例自身的代际**：一条线被回收后 ID 可以复用，代际让旧
 * 客户端拿着复用前的引用访问时能被识别出来，而不是静默落到新场景里。它和
 * [model.MmoSceneSession.sessionEpoch]（角色每次重进递增）不是一回事，两者都存在
 * 是因为"场景被重建"和"玩家重进"是两个独立事件。
 */
data class SceneRef(
    val kind: Kind,
    val id: Long,
    val generation: Long,
) {
    enum class Kind(val code: String) {
        /** 常驻分线：世界地图的一条线，长期存在。 */
        LINE("l"),

        /** 副本实例：一次性，打完即销毁。 */
        INSTANCE("i"),
    }

    /** URL / 数据库中使用的规范形式。同一个 SceneRef 只有这一种写法。 */
    fun encode(): String = "${kind.code}-$id-$generation"

    override fun toString(): String = encode()

    companion object {
        /**
         * 解析失败返回 `null`，由调用方翻译成 `21602 SceneGenerationMismatch`
         * （spec §9.0）。这里不抛异常：非法 scene_ref 是常规的客户端输入错误，
         * 不是程序缺陷。
         */
        fun parse(raw: String): SceneRef? {
            val parts = raw.split('-')
            if (parts.size != 3) return null
            val kind = Kind.entries.firstOrNull { it.code == parts[0] } ?: return null
            val id = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val generation = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return SceneRef(kind, id, generation)
        }
    }
}
