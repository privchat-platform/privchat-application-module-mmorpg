package controller.app

import dto.RoleCreateRequest
import dto.RoleResponse
import logic.scene.MmoRoleRepository
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Post
import neton.core.http.HttpException
import neton.core.interfaces.Identity
import neton.core.http.NetonErrorCode

/**
 * 角色管理。场景相关的一切都以 `role_id` 为主体，所以进场景之前必须先有角色。
 *
 * 账号与角色是一对多：这里不提供"取我的唯一角色"这种便捷接口，因为它会诱导
 * 调用方假设一对一，等到真的要支持多角色时所有调用点都得改。
 */
@Controller("/mmo/roles")
class MmoRoleController(
    private val roles: MmoRoleRepository,
) {

    @Get("")
    suspend fun listMine(identity: Identity): List<RoleResponse> =
        roles.listByUser(identity.id.toLong()).map { RoleResponse(it.id, it.name) }

    @Post("")
    suspend fun create(
        identity: Identity,
        @Body request: RoleCreateRequest,
    ): RoleResponse {
        val name = request.name.trim()
        if (name.length !in NAME_MIN..NAME_MAX) {
            throw HttpException(
                NetonErrorCode.INVALID_PARAMS,
                "role name length must be in [$NAME_MIN, $NAME_MAX]; got ${name.length}",
            )
        }
        // 先查一次只是为了给出可读的错误。真正的唯一性由 idx_mmo_role_name 保证——
        // 并发同名时数据库仍会拒掉第二个，这里的检查不是防线，只是体验。
        if (roles.findByName(name) != null) {
            throw HttpException(NetonErrorCode.OPERATION_CONFLICT, "role name '$name' is taken")
        }
        val role = roles.create(userId = identity.id.toLong(), name = name)
        return RoleResponse(role.id, role.name)
    }

    private companion object {
        const val NAME_MIN = 2
        const val NAME_MAX = 24
    }
}
