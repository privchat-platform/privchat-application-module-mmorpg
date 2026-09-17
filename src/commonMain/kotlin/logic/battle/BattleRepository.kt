package logic.battle

import model.MmoBattle
import model.MmoBattleActor
import model.MmoBattleCommand
import model.MmoBattleEvent
import model.MmoBattleLease
import model.MmoBattleSlot
import model.MmoBattleTransition
import model.MmoRewardSettlement
import neton.database.dbContext
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

    /**
     * 乐观锁写回:只有当行上的 `state_version` 仍是调用方读到的那个值时才写。
     * 每次提交都至少 emit 一个事件、`state_version` 严格递增,所以它就是版本列。
     * 返回 false = 别人先提交了(调度器 vs 提交路径),调用方必须放弃本次结算而不是覆盖。
     */
    open suspend fun updateBattleIfVersion(battle: MmoBattle, expectedStateVersion: Long): Boolean {
        val affected = dbContext().execute(
            """
            UPDATE mmo_battle SET
                channel_id = :channelId, phase = :phase, round_no = :round, phase_version = :phaseVersion,
                state_version = :stateVersion, public_event_seq = :publicSeq, private_event_seq = :privateSeq,
                rng_cursor = :rngCursor, winner_side = :winnerSide, deadline_at_ms = :deadline,
                initiative_order = :initiative, updated_at = :updatedAt
            WHERE id = :id AND state_version = :expected
            """.trimIndent(),
            mapOf(
                "channelId" to battle.channelId, "phase" to battle.phase, "round" to battle.round, "phaseVersion" to battle.phaseVersion,
                "stateVersion" to battle.stateVersion, "publicSeq" to battle.publicEventSeq, "privateSeq" to battle.privateEventSeq,
                "rngCursor" to battle.rngCursor, "winnerSide" to battle.winnerSide, "deadline" to battle.deadlineAtMs,
                "initiative" to battle.initiativeOrder, "updatedAt" to kotlin.time.Clock.System.now().toEpochMilliseconds(),
                "id" to battle.id, "expected" to expectedStateVersion,
            ),
        )
        return affected == 1L
    }

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

    /**
     * 有待投递事件的战斗 id。调度器只补投这些,而不是每 500ms 扫一遍所有未关闭的战斗:
     * 稳态下 outbox 是空的,原来那种"2×N 次空查询"纯属浪费。
     */
    open suspend fun listBattleIdsWithPending(limit: Int = 200): List<Long> =
        dbContext().fetchAll(
            "SELECT DISTINCT battle_id FROM mmo_battle_event WHERE published_at = 0 ORDER BY battle_id LIMIT :limit",
            mapOf("limit" to limit),
        ).map { it.long("battle_id") }

    /** 卡在 CREATED 的僵尸(第二段事务失败又没走补偿),超过 [olderThanMs] 就该关掉。 */
    open suspend fun listStaleCreated(olderThanMs: Long, limit: Int = 50): List<MmoBattle> =
        MmoBattleTable.query {
            where { and(MmoBattle::phase eq BattlePhase.CREATED.name, MmoBattle::createdAt le olderThanMs) }
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

    /**
     * 已投递且早于 [olderThanMs] 的 outbox 行。表只是投递缓冲,不是战斗日志
     * (日志在 `mmo_battle_transition` / 结算),留着只会让 `idx_mmo_battle_event_pending`
     * 越扫越慢。分批删,避免一条大 DELETE 长时间持锁。
     */
    open suspend fun deletePublishedBefore(olderThanMs: Long, limit: Int = 1_000): Long =
        dbContext().execute(
            """DELETE FROM mmo_battle_event WHERE id IN (
                 SELECT id FROM mmo_battle_event WHERE published_at > 0 AND published_at < :before LIMIT :limit)""",
            mapOf("before" to olderThanMs, "limit" to limit),
        )

    // ---- lease ----
    open suspend fun findLease(battleId: Long): MmoBattleLease? =
        MmoBattleLeaseTable.oneWhere { MmoBattleLease::battleId eq battleId }
    open suspend fun insertLease(lease: MmoBattleLease): MmoBattleLease = MmoBattleLeaseTable.insert(lease)
    open suspend fun updateLease(lease: MmoBattleLease) { MmoBattleLeaseTable.update(lease) }

    // ---- settlement ----
    open suspend fun insertSettlement(s: MmoRewardSettlement): MmoRewardSettlement = MmoRewardSettlementTable.insert(s)
}
