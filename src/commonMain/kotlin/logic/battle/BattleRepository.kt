package logic.battle

import model.MmoBattle
import model.MmoBattleActor
import model.MmoBattleCommand
import model.MmoBattleEvent
import model.MmoBattleLease
import model.MmoBattleSlot
import model.MmoBattleTransition
import model.MmoRewardSettlement
import neton.database.dsl.*
import neton.logging.Logger
import table.MmoBattleActorTable
import table.MmoBattleCommandTable
import table.MmoBattleEventTable
import table.MmoBattleLeaseTable
import table.MmoBattleSlotTable
import table.MmoBattleTable
import table.MmoBattleTransitionTable
import table.MmoRewardSettlementTable

/**
 * 战斗各表的访问层（§15.3）。方法都是 `open`：测试用内存实现整体覆写，
 * 漏覆写一个会打到真实 Table 并以连接错误暴露，而不是静默通过。
 */
open class BattleRepository(
    @Suppress("unused") private val log: Logger,
) {
    // ---- battle ----
    open suspend fun insertBattle(battle: MmoBattle): MmoBattle = MmoBattleTable.insert(battle)
    open suspend fun getBattle(id: Long): MmoBattle? = MmoBattleTable.get(id)
    open suspend fun updateBattle(battle: MmoBattle) { MmoBattleTable.update(battle) }

    /** 到期要推进的战斗：COMMAND 截止、SETTLE 宽限结束。 */
    open suspend fun listDue(nowMs: Long, limit: Int = 100): List<MmoBattle> =
        MmoBattleTable.query {
            where {
                and(
                    MmoBattle::phase `in` listOf(BattlePhase.COMMAND.name, BattlePhase.SETTLE.name),
                    MmoBattle::deadlineAtMs le nowMs,
                )
            }
            orderBy(MmoBattle::deadlineAtMs.asc())
            limitOffset(limit, 0)
        }.list()

    /** 未 CLOSED 的战斗；调度器用来补投 outbox。 */
    open suspend fun listOpen(limit: Int = 200): List<MmoBattle> =
        MmoBattleTable.query {
            where { MmoBattle::phase `in` BattlePhase.entries.filter { it != BattlePhase.CLOSED }.map { it.name } }
            orderBy(MmoBattle::id.asc())
            limitOffset(limit, 0)
        }.list()

    // ---- transition ----
    open suspend fun insertTransition(t: MmoBattleTransition): MmoBattleTransition = MmoBattleTransitionTable.insert(t)
    open suspend fun getTransition(id: Long): MmoBattleTransition? = MmoBattleTransitionTable.get(id)
    open suspend fun updateTransition(t: MmoBattleTransition) { MmoBattleTransitionTable.update(t) }

    // ---- actors ----
    open suspend fun insertActor(actor: MmoBattleActor): MmoBattleActor = MmoBattleActorTable.insert(actor)
    open suspend fun listActors(battleId: Long): List<MmoBattleActor> =
        MmoBattleActorTable.query {
            where { MmoBattleActor::battleId eq battleId }
            orderBy(MmoBattleActor::id.asc())
        }.list()
    open suspend fun updateActor(actor: MmoBattleActor) { MmoBattleActorTable.update(actor) }

    // ---- slots ----
    open suspend fun insertSlot(slot: MmoBattleSlot): MmoBattleSlot = MmoBattleSlotTable.insert(slot)
    open suspend fun getSlot(id: Long): MmoBattleSlot? = MmoBattleSlotTable.get(id)
    open suspend fun listSlots(battleId: Long, round: Int): List<MmoBattleSlot> =
        MmoBattleSlotTable.query {
            where { and(MmoBattleSlot::battleId eq battleId, MmoBattleSlot::roundNo eq round) }
            orderBy(MmoBattleSlot::id.asc())
        }.list()
    open suspend fun updateSlot(slot: MmoBattleSlot) { MmoBattleSlotTable.update(slot) }

    // ---- commands（幂等真源）----
    open suspend fun insertCommand(command: MmoBattleCommand): MmoBattleCommand = MmoBattleCommandTable.insert(command)
    open suspend fun findCommandByRequest(battleId: Long, requestId: String): MmoBattleCommand? =
        MmoBattleCommandTable.oneWhere { and(MmoBattleCommand::battleId eq battleId, MmoBattleCommand::requestId eq requestId) }
    open suspend fun findCommandBySeq(battleId: Long, actorId: Long, actionSeq: Int): MmoBattleCommand? =
        MmoBattleCommandTable.oneWhere {
            and(MmoBattleCommand::battleId eq battleId, MmoBattleCommand::actorId eq actorId, MmoBattleCommand::actionSeq eq actionSeq)
        }

    // ---- events（outbox）----
    open suspend fun insertEvent(event: MmoBattleEvent): MmoBattleEvent = MmoBattleEventTable.insert(event)
    open suspend fun listUnpublished(battleId: Long): List<MmoBattleEvent> =
        MmoBattleEventTable.query {
            where { and(MmoBattleEvent::battleId eq battleId, MmoBattleEvent::publishedAt eq 0L) }
            orderBy(MmoBattleEvent::id.asc())
        }.list()
    open suspend fun markPublished(event: MmoBattleEvent, nowMs: Long) { MmoBattleEventTable.update(event.copy(publishedAt = nowMs)) }

    // ---- lease ----
    open suspend fun findLease(battleId: Long): MmoBattleLease? =
        MmoBattleLeaseTable.oneWhere { MmoBattleLease::battleId eq battleId }
    open suspend fun insertLease(lease: MmoBattleLease): MmoBattleLease = MmoBattleLeaseTable.insert(lease)
    open suspend fun updateLease(lease: MmoBattleLease) { MmoBattleLeaseTable.update(lease) }

    // ---- settlement ----
    open suspend fun insertSettlement(s: MmoRewardSettlement): MmoRewardSettlement = MmoRewardSettlementTable.insert(s)
}
