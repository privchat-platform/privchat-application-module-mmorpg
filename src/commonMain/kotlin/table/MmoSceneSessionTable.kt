package table

import model.MmoSceneSession
import model.MmoSceneSessionTableImpl
import neton.database.api.Table

/** `LMmo_LScene_LSession` 的访问 Facade；实现由 KSP EntityTableProcessor 生成。 */
object MmoSceneSessionTable : Table<MmoSceneSession, Long> by MmoSceneSessionTableImpl
