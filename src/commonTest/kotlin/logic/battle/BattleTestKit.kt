package logic.battle

import logic.scene.FakeChannelService
import logic.scene.FakeMapRepository
import logic.scene.FakeRoleRepository
import logic.scene.FakeRoomGateway
import logic.scene.FakeSessionRepository
import logic.scene.NoopLogger
import model.MmoBattle
import model.MmoBattleActor
import model.MmoBattleCommand
import model.MmoBattleEvent
import model.MmoBattleLease
import model.MmoBattleSlot
import model.MmoBattleTransition
import model.MmoRewardSettlement

/** 内存版战斗仓储。全部覆写，基类的 Table 一次都不会被触碰。 */
class FakeBattleRepository : BattleRepository(NoopLogger) {
    val battles = mutableMapOf<Long, MmoBattle>()
    val transitions = mutableMapOf<Long, MmoBattleTransition>()
    val actors = mutableMapOf<Long, MmoBattleActor>()
    val slots = mutableMapOf<Long, MmoBattleSlot>()
    val commands = mutableMapOf<Long, MmoBattleCommand>()
    val events = mutableMapOf<Long, MmoBattleEvent>()
    val leases = mutableMapOf<Long, MmoBattleLease>()
    val settlements = mutableMapOf<Long, MmoRewardSettlement>()
    private var next = 1L
    private fun id() = next++

    override suspend fun insertBattle(battle: MmoBattle) = battle.copy(id = id()).also { battles[it.id] = it }
    override suspend fun getBattle(id: Long) = battles[id]
    override suspend fun updateBattle(battle: MmoBattle) { battles[battle.id] = battle }
    override suspend fun listDue(nowMs: Long, limit: Int) =
        battles.values.filter { (it.phase == "COMMAND" || it.phase == "SETTLE") && it.deadlineAtMs <= nowMs }.sortedBy { it.deadlineAtMs }.take(limit)
    override suspend fun listOpen(limit: Int) = battles.values.filter { it.phase != "CLOSED" }.sortedBy { it.id }.take(limit)

    override suspend fun insertTransition(t: MmoBattleTransition) = t.copy(id = id()).also { transitions[it.id] = it }
    override suspend fun getTransition(id: Long) = transitions[id]
    override suspend fun updateTransition(t: MmoBattleTransition) { transitions[t.id] = t }

    override suspend fun insertActor(actor: MmoBattleActor) = actor.copy(id = id()).also { actors[it.id] = it }
    override suspend fun listActors(battleId: Long) = actors.values.filter { it.battleId == battleId }.sortedBy { it.id }
    override suspend fun updateActor(actor: MmoBattleActor) { actors[actor.id] = actor }

    override suspend fun insertSlot(slot: MmoBattleSlot) = slot.copy(id = id()).also { slots[it.id] = it }
    override suspend fun getSlot(id: Long) = slots[id]
    override suspend fun listSlots(battleId: Long, round: Int) = slots.values.filter { it.battleId == battleId && it.roundNo == round }.sortedBy { it.id }
    override suspend fun updateSlot(slot: MmoBattleSlot) { slots[slot.id] = slot }

    override suspend fun insertCommand(command: MmoBattleCommand) = command.copy(id = id()).also { commands[it.id] = it }
    override suspend fun findCommandByRequest(battleId: Long, requestId: String) =
        commands.values.firstOrNull { it.battleId == battleId && it.requestId == requestId }
    override suspend fun findCommandBySeq(battleId: Long, actorId: Long, actionSeq: Int) =
        commands.values.firstOrNull { it.battleId == battleId && it.actorId == actorId && it.actionSeq == actionSeq }

    override suspend fun insertEvent(event: MmoBattleEvent) = event.copy(id = id()).also { events[it.id] = it }
    override suspend fun listUnpublished(battleId: Long) = events.values.filter { it.battleId == battleId && it.publishedAt == 0L }.sortedBy { it.id }
    override suspend fun markPublished(event: MmoBattleEvent, nowMs: Long) { events[event.id] = event.copy(publishedAt = nowMs) }

    override suspend fun findLease(battleId: Long) = leases.values.firstOrNull { it.battleId == battleId }
    override suspend fun insertLease(lease: MmoBattleLease) = lease.copy(id = id()).also { leases[it.id] = it }
    override suspend fun updateLease(lease: MmoBattleLease) { leases[lease.id] = lease }

    override suspend fun insertSettlement(s: MmoRewardSettlement) = s.copy(id = id()).also { settlements[it.id] = it }
}

object BattleTestKit {
    fun service(
        roles: FakeRoleRepository,
        sessions: FakeSessionRepository,
        rooms: FakeRoomGateway,
        channels: FakeChannelService,
        repo: FakeBattleRepository = FakeBattleRepository(),
        clock: () -> Long = { 12_345L },
        seed: Long = 42L,
    ): BattleService = BattleService(
        log = NoopLogger, roles = roles, sessions = sessions, channels = channels, maps = FakeMapRepository(),
        rooms = rooms, repo = repo, tx = DirectBattleTransactor, clock = clock, seeds = { seed },
    )
}
