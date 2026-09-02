package table

import model.MmoSceneChannel
import model.MmoSceneChannelTableImpl
import neton.database.api.Table

/** `LMmo_LScene_LChannel` 的访问 Facade；实现由 KSP EntityTableProcessor 生成。 */
object MmoSceneChannelTable : Table<MmoSceneChannel, Long> by MmoSceneChannelTableImpl
