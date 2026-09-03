package table

import model.MmoMap
import model.MmoMapTableImpl
import model.MmoNpc
import model.MmoNpcTableImpl
import neton.database.api.Table

/** `mmo_map` 的访问 Facade；实现由 KSP EntityTableProcessor 生成。 */
object MmoMapTable : Table<MmoMap, Long> by MmoMapTableImpl

/** `mmo_npc` 的访问 Facade；实现由 KSP EntityTableProcessor 生成。 */
object MmoNpcTable : Table<MmoNpc, Long> by MmoNpcTableImpl
