package logic.map

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.MmoMap
import model.MmoNpc
import neton.database.dsl.*
import neton.logging.Logger
import table.MmoMapTable
import table.MmoNpcTable

/**
 * 地图与 NPC 的访问层，进程内缓存：地图数据是运营内容，改动走后台与缓存失效，
 * 不在每次寻路时读库。
 */
open class MapRepository(
    private val log: Logger,
) {
    private val mutex = Mutex()
    private val cache = HashMap<Long, SceneMap>()

    open suspend fun find(mapId: Long): SceneMap? {
        mutex.withLock { cache[mapId] }?.let { return it }
        val data = MmoMapTable.get(mapId)?.takeIf { it.status == 1 } ?: return null
        val npcs = MmoNpcTable.query {
            where { and(MmoNpc::mapId eq mapId, MmoNpc::status eq 1) }
            orderBy(MmoNpc::id.asc())
        }.list()
        val map = SceneMap(data, npcs)
        mutex.withLock { cache[mapId] = map }
        log.info("mmo.map.loaded map_id=$mapId name=${data.name} cells=${data.widthCells}x${data.heightCells} npcs=${npcs.size}")
        return map
    }

    open suspend fun invalidate(mapId: Long) {
        mutex.withLock { cache.remove(mapId) }
    }
}

@Suppress("unused")
private fun keepImport(m: MmoMap) = m
