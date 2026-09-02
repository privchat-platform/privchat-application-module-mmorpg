package init

import logic.transfer.MmorpgDiagnosticEchoHandler
import neton.core.component.NetonContext
import neton.logging.LoggerFactory
import transfer.PrivChatTransferServiceRegistry

/**
 * MMORPG 模块的装配入口。
 *
 * **当前是可运行的链路验证 demo，不是 MMORPG 业务实现**，只注册一个诊断 echo
 * handler。正式的 scene / battle 协议见 privchat-docs 下的 MMO_* spec。
 *
 * **注册发生在这里，不在 `PrivchatRuntimeBootstrap`。** module-privchat 是基础
 * 设施，它提供 [PrivChatTransferServiceRegistry] 但不认识任何业务模块；反过来
 * 让它 import mmorpg 会造成基础模块反向依赖业务模块，也意味着每加一个游戏模块
 * 都要改动 privchat 的启动代码。业务模块自己来取 registry 并登记自己。
 *
 * 数据侧的对应声明（由运维/迁移负责，不写死在代码里）：
 *   - `privchat_business_service(name='mmorpg', callback_url=NULL, status=1)`
 *   - `privchat_business_channel` 把 scene channel 绑到该 service_id
 *
 * `service_id` 刻意不出现在代码中：dispatcher 走
 * `channel_id → service_id → service.name → registry.find(name)`，
 * 代码只认 name，部署侧改 ID 不需要重编。
 */
object MmorpgRuntimeBootstrap {

    fun initialize(ctx: NetonContext) {
        val log = ctx.get(LoggerFactory::class).get("mmorpg.module")

        val registry = ctx.get(PrivChatTransferServiceRegistry::class)
        registry.register(
            MmorpgDiagnosticEchoHandler(
                log = ctx.get(LoggerFactory::class).get("mmorpg.transfer"),
            ),
        )
        log.info(
            "mmorpg.transfer_handler.registered service=mmorpg " +
                "routes=[${MmorpgDiagnosticEchoHandler.ROUTE_DIAGNOSTIC_ECHO}]",
        )
    }
}
