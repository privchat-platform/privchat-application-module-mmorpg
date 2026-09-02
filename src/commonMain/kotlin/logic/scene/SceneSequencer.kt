package logic.scene

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 每场景单调递增的公共事件序号。
 *
 * ### 当前是进程内的
 *
 * 序号存在内存里，进程重启后从 0 重来。这在单实例部署下是正确的，但**多实例或
 * 重启后会出现 seq 回退**。客户端约定：看到 seq 不大于上一次收到的值，就当作
 * 序列重置，丢弃本地增量并拉一次 snapshot——回退因此是"多一次全量"，不是状态错乱。
 *
 * 真正做多实例时这里要换成 Redis `INCR` 或场景 owner 节点的 lease-fencing
 * （MMO_WORLD_SCENE_SPEC §2）。留在这里的原因是接口不变：调用方只见 [next]。
 */
class SceneSequencer {
    private val mutex = Mutex()
    private val counters = mutableMapOf<String, Long>()

    suspend fun next(sceneRef: String): Long = mutex.withLock {
        val v = (counters[sceneRef] ?: 0L) + 1
        counters[sceneRef] = v
        v
    }

    suspend fun current(sceneRef: String): Long = mutex.withLock { counters[sceneRef] ?: 0L }
}
