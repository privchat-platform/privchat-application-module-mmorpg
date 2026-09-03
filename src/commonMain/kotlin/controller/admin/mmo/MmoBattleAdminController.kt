package controller.admin.mmo

import dto.AdminBattleAbortRequest
import dto.AdminBattleAbortResponse
import logic.battle.BattleService
import logic.scene.SceneOutcome
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.PathVariable
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.http.HttpException

/**
 * 后台的战斗管理（`/admin/mmo/battles/...`）：
 *
 * ```text
 * POST /admin/mmo/battles/{battleId}/abort   强制中止（无胜者）   mmorpg:scene:manage
 * ```
 */
@Controller("/mmo/battles")
class MmoBattleAdminController(
    private val battles: BattleService,
) {
    @Post("/{battleId}/abort")
    @Permission("mmorpg:scene:manage")
    suspend fun abort(@PathVariable battleId: Long, @Body request: AdminBattleAbortRequest): AdminBattleAbortResponse {
        when (val outcome = battles.abort(battleId, request.reason)) {
            is SceneOutcome.Failure -> throw HttpException(outcome.code, outcome.message)
            is SceneOutcome.Success -> Unit
        }
        return AdminBattleAbortResponse(battleId = battleId, aborted = true)
    }
}
