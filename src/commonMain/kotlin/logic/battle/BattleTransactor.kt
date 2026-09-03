package logic.battle

import neton.database.dbContext

/**
 * 战斗服务的事务边界。
 *
 * 状态、slot、指令与事件 outbox 必须同事务提交（MMO_ARCHITECTURE_SPEC §7.5）；
 * 抽成接口是为了让测试用内存仓储时直接执行、生产走 `DbContext.transaction`。
 */
interface BattleTransactor {
    suspend fun <R> run(block: suspend () -> R): R
}

/** 生产实现：`DbContext.transaction { }`；嵌套时并入外层事务。 */
class DbBattleTransactor : BattleTransactor {
    override suspend fun <R> run(block: suspend () -> R): R = dbContext().transaction { block() }
}

/** 测试 / 无事务实现：直接执行。 */
object DirectBattleTransactor : BattleTransactor {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}
