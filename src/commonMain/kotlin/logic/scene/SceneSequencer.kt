package logic.scene

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import neton.database.dbContext

/**
 * 场景公共事件流的序号来源(`stream_seq` / `public_scene_seq`)。
 *
 * 序号必须**跨进程重启单调**:Room 会向新订阅者回放最近 30 条广播,若计数器重启归 1,
 * 回放里上一轮的高序号会让客户端把本轮的真事件当成重复丢掉(或误判为"序列重置")。
 * 所以计数器落在 `mmo_scene_channel.public_scene_seq`:每次发号先读一次(冷启动),
 * 之后内存自增并回写;多实例部署时各自读到的基线一致,但同时发号会撞——那属 lease
 * 的范畴(SCENE §2),v1 单实例。
 */
class SceneSequencer(private val store: Store = Store.InMemory()) {
    /** 序号的持久层;测试用内存实现。 */
    interface Store {
        suspend fun load(sceneRef: String): Long
        suspend fun save(sceneRef: String, seq: Long)

        class InMemory : Store {
            private val values = mutableMapOf<String, Long>()
            override suspend fun load(sceneRef: String): Long = values[sceneRef] ?: 0L
            override suspend fun save(sceneRef: String, seq: Long) { values[sceneRef] = seq }
        }

        /** `mmo_scene_channel.public_scene_seq`;场景行不存在时读 0、写为空操作。 */
        class Database : Store {
            override suspend fun load(sceneRef: String): Long =
                dbContext().fetchAll(
                    "SELECT public_scene_seq FROM mmo_scene_channel WHERE scene_ref = :ref",
                    mapOf("ref" to sceneRef),
                ).firstOrNull()?.long("public_scene_seq") ?: 0L

            override suspend fun save(sceneRef: String, seq: Long) {
                // GREATEST:并发或重放不会把序号往回写。
                dbContext().execute(
                    "UPDATE mmo_scene_channel SET public_scene_seq = GREATEST(public_scene_seq, :seq) WHERE scene_ref = :ref",
                    mapOf("seq" to seq, "ref" to sceneRef),
                )
            }
        }
    }

    private val mutex = Mutex()
    private val counters = mutableMapOf<String, Long>()

    suspend fun next(sceneRef: String): Long = mutex.withLock {
        val v = (counters[sceneRef] ?: store.load(sceneRef)) + 1
        counters[sceneRef] = v
        store.save(sceneRef, v)
        v
    }

    suspend fun current(sceneRef: String): Long = mutex.withLock {
        counters[sceneRef] ?: store.load(sceneRef).also { counters[sceneRef] = it }
    }
}
