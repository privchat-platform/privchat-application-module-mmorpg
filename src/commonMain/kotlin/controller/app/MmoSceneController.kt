package controller.app

import dto.SceneEnterRequest
import dto.SceneEnterResponse
import dto.SceneLeaveRequest
import dto.SceneLeaveResponse
import dto.EntityStateDto
import dto.MovementDto
import dto.ScenePresentRole
import dto.Vec2FixedDto
import logic.scene.EntityState
import logic.map.MapRepository
import logic.map.SceneMap
import dto.MapResponse
import dto.NpcDto
import dto.ScenePrivateSnapshotResponse
import dto.ScenePublicSnapshotResponse
import logic.scene.SceneOutcome
import logic.scene.SceneService
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Post
import neton.core.http.HttpException
import neton.core.interfaces.Identity

/**
 * 场景的 HTTP 面（MMO_WORLD_SCENE_SPEC §9.0 冻结的端点）：
 *
 * ```text
 * POST /app/mmo/scene/{scene_ref}/enter
 * POST /app/mmo/scene/{scene_ref}/leave
 * GET  /app/mmo/scene/{scene_ref}/snapshot
 * GET  /app/mmo/scene/{scene_ref}/roles/{role_id}/private-snapshot
 * ```
 *
 * ### 为什么进入场景走 HTTP 而心跳走 Transfer
 *
 * enter 要先申请 Room 并签发 ticket，客户端此时**还没有**这个 channel 的订阅权，
 * 没法用 Transfer——Transfer 的前提就是已经在 channel 上。反过来，心跳量大且必须
 * 复用已建立的长连接，走 HTTP 等于每次心跳一次握手。
 *
 * `userId` 一律取自 [Identity]（认证态），不从请求体读。角色归属在 service 层再校验一次。
 */
@Controller("/mmo/scene")
class MmoSceneController(
    private val scenes: SceneService,
    private val maps: MapRepository,
) {

    /** 地图静态数据；客户端进场景后按 snapshot 的 `map_id` 拉一次。 */
    @Get("/maps/{mapId}")
    suspend fun map(@PathVariable mapId: Long): MapResponse {
        val map = maps.find(mapId) ?: throw HttpException(logic.MmoErrorCodes.SCENE_NOT_FOUND, "map $mapId does not exist")
        val d = map.data
        return MapResponse(
            mapId = d.id, name = d.name, widthCells = d.widthCells, heightCells = d.heightCells, cellSize = d.cellSize,
            rows = (0 until d.heightCells).map { r -> d.grid.substring(r * d.widthCells, (r + 1) * d.widthCells) },
            spawn = Vec2FixedDto(d.spawnX, d.spawnY),
            navigationVersion = SceneMap.NAVIGATION_VERSION,
        )
    }

    @Post("/{sceneRef}/enter")
    suspend fun enter(
        identity: Identity,
        @PathVariable sceneRef: String,
        @Body request: SceneEnterRequest,
    ): SceneEnterResponse {
        val result = scenes.enter(
            userId = identity.id.toLong(),
            roleId = request.roleId,
            rawSceneRef = sceneRef,
            deviceId = request.deviceId,
        ).orThrow()
        return SceneEnterResponse(
            sceneRef = result.sceneRef.encode(),
            channelId = result.channelId.toString(),
            sceneSessionId = result.sceneSessionId,
            sessionEpoch = result.sessionEpoch,
            ticket = result.ticket,
            exp = result.ticketExp,
        )
    }

    @Post("/{sceneRef}/leave")
    suspend fun leave(
        identity: Identity,
        @PathVariable sceneRef: String,
        @Body request: SceneLeaveRequest,
    ): SceneLeaveResponse {
        scenes.leave(
            userId = identity.id.toLong(),
            roleId = request.roleId,
            rawSceneRef = sceneRef,
        ).orThrow()
        return SceneLeaveResponse(sceneRef = sceneRef, roleId = request.roleId)
    }

    @Get("/{sceneRef}/snapshot")
    suspend fun publicSnapshot(@PathVariable sceneRef: String): ScenePublicSnapshotResponse {
        val snapshot = scenes.publicSnapshot(sceneRef).orThrow()
        return ScenePublicSnapshotResponse(
            sceneRef = snapshot.sceneRef.encode(),
            publicSceneSeq = snapshot.publicSceneSeq,
            serverTimeMs = snapshot.serverTimeMs,
            navigationVersion = SceneMap.NAVIGATION_VERSION,
            mapId = snapshot.mapId,
            roles = snapshot.roles.map { ScenePresentRole(it.roleId, it.roleName, it.state.toDto()) },
            npcs = snapshot.npcs.map { NpcDto(it.npcId, it.name, it.kind, Vec2FixedDto(it.position.x, it.position.y), it.interactRange) },
        )
    }

    @Get("/{sceneRef}/roles/{roleId}/private-snapshot")
    suspend fun privateSnapshot(
        identity: Identity,
        @PathVariable sceneRef: String,
        @PathVariable roleId: Long,
    ): ScenePrivateSnapshotResponse {
        val snapshot = scenes.privateSnapshot(
            userId = identity.id.toLong(),
            roleId = roleId,
            rawSceneRef = sceneRef,
        ).orThrow()
        return ScenePrivateSnapshotResponse(
            sceneRef = snapshot.sceneRef.encode(),
            roleId = snapshot.roleId,
            sceneSessionId = snapshot.sceneSessionId,
            sessionEpoch = snapshot.sessionEpoch,
            channelId = snapshot.channelId.toString(),
            lastSeenAt = snapshot.lastSeenAt,
            publicSceneSeq = snapshot.publicSceneSeq,
            self = snapshot.self.toDto(),
        )
    }
}

/**
 * 错误码原样透出，不映射成 HTTP 语义码。
 *
 * 同一个业务失败（比如 21601 会话失效）在 HTTP 和 Transfer 两条路上必须是同一个
 * 数字，否则客户端要为两条路各写一套错误处理，而这两套迟早会不一致。
 */
private fun <T> SceneOutcome<T>.orThrow(): T = when (this) {
    is SceneOutcome.Success -> value
    is SceneOutcome.Failure -> throw HttpException(code, message)
}

private fun EntityState.toDto() = EntityStateDto(
    entityId = entityId,
    entityVersion = entityVersion,
    movementSeq = movementSeq,
    position = Vec2FixedDto(position.x, position.y),
    movement = movement?.let {
        MovementDto(
            pathId = it.pathId,
            start = Vec2FixedDto(it.start.x, it.start.y),
            pathPoints = it.pathPoints.map { p -> Vec2FixedDto(p.x, p.y) },
            startTimeMs = it.startTimeMs,
            speed = it.speed,
        )
    },
)
