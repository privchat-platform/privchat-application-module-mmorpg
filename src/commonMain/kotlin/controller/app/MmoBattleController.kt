package controller.app

import dto.ActorPrivateStateDto
import dto.ActorPublicStateDto
import dto.BattleEntryResponse
import dto.BattleSnapshotResponse
import dto.BattleStartRequest
import dto.SlotInfoDto
import dto.SubmittedCommandDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import logic.battle.BattleEntry
import logic.battle.BattleRules
import logic.battle.BattleService
import logic.battle.BattleSnapshot
import logic.codec.BattleCodec
import logic.scene.SceneOutcome
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Post
import neton.core.http.HttpException
import neton.core.interfaces.Identity

/**
 * 战斗的 HTTP 面（MMO_BATTLE_PROTOCOL_SPEC §15.1）：
 *
 * ```text
 * POST /app/mmo/scene/{scene_ref}/roles/{role_id}/battles          发起（PvE，对 NPC）
 * GET  /app/mmo/battles/transitions/{transition_id}                 续接
 * GET  /app/mmo/battle/{battle_id}/snapshot                          public
 * GET  /app/mmo/battle/{battle_id}/roles/{role_id}/private-snapshot  private
 * ```
 *
 * 发起走 HTTP 而不是 Transfer，理由同场景 enter：要建 Room、签 ticket，客户端此时
 * 还没有战斗频道的订阅权。
 */
@Controller("/mmo")
class MmoBattleController(
    private val battles: BattleService,
) {
    @Post("/scene/{sceneRef}/roles/{roleId}/battles")
    suspend fun start(
        identity: Identity,
        @PathVariable sceneRef: String,
        @PathVariable roleId: Long,
        @Body request: BattleStartRequest,
    ): BattleEntryResponse =
        battles.start(identity.id.toLong(), roleId, sceneRef, request.npcId, request.deviceId).orThrow().toDto()

    @Get("/battles/transitions/{transitionId}")
    suspend fun transition(identity: Identity, @PathVariable transitionId: Long): BattleEntryResponse =
        battles.transition(identity.id.toLong(), transitionId).orThrow().toDto()

    @Get("/battle/{battleId}/snapshot")
    suspend fun publicSnapshot(@PathVariable battleId: Long): BattleSnapshotResponse =
        battles.publicSnapshot(battleId).orThrow().toDto()

    @Get("/battle/{battleId}/roles/{roleId}/private-snapshot")
    suspend fun privateSnapshot(identity: Identity, @PathVariable battleId: Long, @PathVariable roleId: Long): BattleSnapshotResponse =
        battles.privateSnapshot(identity.id.toLong(), battleId, roleId).orThrow().toDto()
}

private fun <T> SceneOutcome<T>.orThrow(): T = when (this) {
    is SceneOutcome.Success -> value
    is SceneOutcome.Failure -> throw HttpException(code, message)
}

private fun BattleEntry.toDto() = BattleEntryResponse(
    transitionId = transitionId, status = status, battleId = battleId, channelId = channelId.toString(),
    ticket = ticket, exp = ticketExp, sceneSessionId = sceneSessionId, reason = reason,
)

/** public 视角不含 slot 与精确资源（V-BS1）；private 视角只含接收者自己的。 */
internal fun BattleSnapshot.toDto(): BattleSnapshotResponse {
    val b = battle
    val mine = actors.filter { recipientRoleId != 0L && it.ownerRoleId == recipientRoleId }
    val enemies = actors.filter { it.alive == 1 && it.side != BattleRules.PLAYER_SIDE }.map { it.id }
    return BattleSnapshotResponse(
        protocolVersion = BattleCodec.PROTOCOL_VERSION,
        supportedProtocolVersions = listOf(BattleCodec.PROTOCOL_VERSION),
        stateVersion = b.stateVersion,
        publicEventSeq = b.publicEventSeq,
        battleId = b.id,
        round = b.round,
        phase = b.phase,
        phaseVersion = b.phaseVersion,
        roundDeadlineAtMs = b.deadlineAtMs,
        actors = actors.map {
            ActorPublicStateDto(
                actorId = it.id, ownerRoleId = it.ownerRoleId, alive = it.alive == 1, position = it.position, side = it.side,
                hpPercent = percent(it.hp, it.maxHp), mpPercent = percent(it.mp, it.maxMp),
            )
        },
        initiativeOrder = runCatching { (Json.parseToJsonElement(b.initiativeOrder) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.longOrNull } }.getOrDefault(emptyList()),
        rngAlgorithmVersion = BattleRules.RNG_ALGORITHM_VERSION,
        privateEventSeq = if (recipientRoleId == 0L) 0 else b.privateEventSeq,
        recipientRoleId = recipientRoleId,
        openSlots = openSlots.map {
            SlotInfoDto(
                commandSlotId = it.id, actorId = it.actorId, slotKind = it.slotKind,
                allowedCommands = runCatching { (Json.parseToJsonElement(it.allowedCommands) as JsonArray).map { e -> (e as JsonPrimitive).content } }.getOrDefault(emptyList()),
                required = it.isRequired == 1, deadlineAtMs = it.deadlineAtMs, acceptedActionSeq = it.acceptedActionSeq,
            )
        },
        submittedCommands = openSlots.filter { it.acceptedActionSeq > 0 }.map {
            SubmittedCommandDto(commandSlotId = it.id, actorId = it.actorId, acceptedActionSeq = it.acceptedActionSeq, payload = it.payload)
        },
        privateActorStates = mine.map {
            ActorPrivateStateDto(actorId = it.id, exactHp = it.hp, exactMp = it.mp, maxHp = it.maxHp, maxMp = it.maxMp, selectableTargetIds = enemies)
        },
        actorNames = actors.associate { it.id.toString() to it.name },
        winnerSide = b.winnerSide,
    )
}

private fun percent(value: Long, max: Long): Int = if (max <= 0) 0 else (value * 100 / max).toInt().coerceIn(0, 100)
