package logic.scene

import model.MmoRole
import neton.database.dsl.*
import neton.logging.Logger
import table.MmoRoleTable

/** `mmo_role` 的访问层。所有权限判定读的是这里，不读客户端自报的角色身份。 */
open class MmoRoleRepository(
    private val log: Logger,
) {

    open suspend fun findById(roleId: Long): MmoRole? = MmoRoleTable.get(roleId)

    open suspend fun listByUser(userId: Long): List<MmoRole> =
        MmoRoleTable.query {
            where {
                and(
                    MmoRole::userId eq userId,
                    MmoRole::status eq 1,
                )
            }
            orderBy(MmoRole::id.asc())
        }.list()

    open suspend fun findByName(name: String): MmoRole? =
        MmoRoleTable.oneWhere { MmoRole::name eq name }

    open suspend fun create(userId: Long, name: String): MmoRole {
        val created = MmoRoleTable.insert(MmoRole(userId = userId, name = name))
        log.info("mmo.role.created role_id=${created.id} user_id=$userId name=$name")
        return created
    }
}
