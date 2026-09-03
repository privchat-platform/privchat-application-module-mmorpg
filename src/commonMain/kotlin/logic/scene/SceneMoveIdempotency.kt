package logic.scene

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logic.codec.SceneMoveCodec

/**
 * 移动意图的幂等窗口（spec §4.1.1 / VALIDATION V-I5 的前置）。
 *
 * 键是 (scene_session_id, request_id)：request_id 只需在会话内唯一。
 * 命中且规范化载荷相同 → 回放首次 ACK；命中但载荷不同 → 21606；未命中才进入
 * 序号比较。**顺序不能反**：先比序号会把合法的重试当成乱序迟到拒掉。
 *
 * 进程内、有界、带 TTL：它挡的是网络抖动造成的短期重复，不是持久审计。
 */
class SceneMoveIdempotency(
    private val ttlMs: Long = 5 * 60_000,
    private val maxEntries: Int = 4096,
) {
    private class Entry(val canonical: String, val ack: SceneMoveCodec.Ack, val at: Long)

    sealed interface Lookup {
        data object Miss : Lookup
        data class Replay(val ack: SceneMoveCodec.Ack) : Lookup
        data object Conflict : Lookup
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun lookup(sessionId: Long, intent: SceneMoveCodec.Intent, nowMs: Long): Lookup = mutex.withLock {
        evict(nowMs)
        val e = entries[key(sessionId, intent.requestId)] ?: return Lookup.Miss
        if (e.canonical == intent.canonical()) Lookup.Replay(e.ack.copy(replayed = true)) else Lookup.Conflict
    }

    suspend fun remember(sessionId: Long, intent: SceneMoveCodec.Intent, ack: SceneMoveCodec.Ack, nowMs: Long) = mutex.withLock {
        entries[key(sessionId, intent.requestId)] = Entry(intent.canonical(), ack, nowMs)
        evict(nowMs)
    }

    private fun evict(nowMs: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs - e.value.at > ttlMs) it.remove() else break
        }
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    private fun key(sessionId: Long, requestId: String) = "$sessionId|$requestId"
}
