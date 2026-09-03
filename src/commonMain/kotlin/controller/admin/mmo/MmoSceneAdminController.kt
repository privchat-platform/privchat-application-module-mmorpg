package controller.admin.mmo

import dto.AdminSceneOpenRequest
import dto.AdminSceneRow
import logic.MmoErrorCodes
import logic.scene.SceneChannelService
import logic.scene.SceneRef
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.http.HttpException

/**
 * 后台的场景管理（实际路径 `/admin/mmo/scenes/...`，包路径 `controller.admin.*` 归 admin 组）。
 *
 * 场景是运营内容：世界频道、地图分线、副本模板都由这里开出来；玩家的 enter
 * 只查不建，场景不存在就 `21600`。开场景是幂等的，重复提交拿到同一个 channel。
 *
 * ```text
 * GET  /admin/mmo/scenes                      列表            mmorpg:scene:read
 * POST /admin/mmo/scenes            {scene_ref} 开场景（幂等）  mmorpg:scene:manage
 * POST /admin/mmo/scenes/{sceneRef}/close      关闭            mmorpg:scene:manage
 * ```
 */
@Controller("/mmo/scenes")
class MmoSceneAdminController(
    private val channels: SceneChannelService,
) {

    @Get("")
    @Permission("mmorpg:scene:read")
    suspend fun list(): List<AdminSceneRow> =
        channels.list().map { AdminSceneRow(sceneRef = it.sceneRef, channelId = it.channelId, status = it.status) }

    @Post("")
    @Permission("mmorpg:scene:manage")
    suspend fun open(@Body request: AdminSceneOpenRequest): AdminSceneRow {
        val ref = SceneRef.parse(request.sceneRef)
            ?: throw HttpException(MmoErrorCodes.SCENE_GENERATION_MISMATCH, "malformed scene_ref: '${request.sceneRef}'")
        val channelId = channels.provision(ref)
        return AdminSceneRow(sceneRef = ref.encode(), channelId = channelId, status = 1)
    }

    @Post("/{sceneRef}/close")
    @Permission("mmorpg:scene:manage")
    suspend fun close(@PathVariable sceneRef: String): AdminSceneRow {
        val ref = SceneRef.parse(sceneRef)
            ?: throw HttpException(MmoErrorCodes.SCENE_GENERATION_MISMATCH, "malformed scene_ref: '$sceneRef'")
        val channelId = channels.find(ref)
            ?: throw HttpException(MmoErrorCodes.SCENE_NOT_FOUND, "scene ${ref.encode()} is not open")
        channels.close(ref)
        return AdminSceneRow(sceneRef = ref.encode(), channelId = channelId, status = 0)
    }
}
