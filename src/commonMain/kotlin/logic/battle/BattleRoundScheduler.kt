package logic.battle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import neton.core.component.NetonLifecycle
import neton.logging.Logger

/**
 * 回合截止驱动（§15.5 "回合驱动"）：周期性调用 [BattleService.tick]。
 *
 * 轮询而不是每场一个定时器：v1 单实例、场次少，轮询让"进程重启后到期的回合"自然
 * 恢复，不需要启动时重建定时器；tick 内按 `phase_version` 重读，与提交路径的
 * 提前结算互不重复。
 */
class BattleRoundScheduler(
    private val log: Logger,
    private val battles: BattleService,
    private val intervalMs: Long = 500,
) : NetonLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun start() {
        scope.launch {
            while (isActive) {
                runCatching { battles.tick() }.onFailure { log.warn("mmo.battle.scheduler.tick_failed err=${it.message}") }
                delay(intervalMs)
            }
        }
        log.info("mmo.battle.scheduler.started interval_ms=$intervalMs")
    }

    override suspend fun stop() = scope.cancel()
}
