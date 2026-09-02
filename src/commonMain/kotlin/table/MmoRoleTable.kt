package table

import model.MmoRole
import model.MmoRoleTableImpl
import neton.database.api.Table

/** `mmo_role` 的访问 Facade；实现由 KSP EntityTableProcessor 生成。 */
object MmoRoleTable : Table<MmoRole, Long> by MmoRoleTableImpl
