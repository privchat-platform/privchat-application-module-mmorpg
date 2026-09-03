package init

import com.netonstream.privchat.application.module.privchat.client.PrivchatServiceClient
import logic.map.MapRepository
import logic.scene.MmoRoleRepository
import logic.scene.MmoSceneSessionRepository
import logic.scene.PrivchatSceneRoomGateway
import logic.scene.SceneChannelService
import logic.scene.SceneRoomGateway
import logic.scene.SceneSequencer
import logic.scene.SceneService
import logic.transfer.MmorpgTransferHandler
import neton.core.component.NetonContext
import neton.logging.LoggerFactory
import transfer.PrivChatTransferServiceRegistry
import transfer.PrivchatBusinessChannelResolver
import transfer.PrivchatBusinessServiceRepository

/**
 * MMORPG 模块的装配入口。
 *
 * **Transfer handler 的注册发生在这里，不在 `PrivchatRuntimeBootstrap`。**
 * module-privchat 是基础设施，它提供 [PrivChatTransferServiceRegistry] 但不认识
 * 任何业务模块；反过来让它 import mmorpg 会造成基础模块反向依赖业务模块，
 * 也意味着每加一个游戏模块都要改动 privchat 的启动代码。业务模块自己来取
 * registry 并登记自己。
 *
 * `service_id` 刻意不出现在代码中：dispatcher 走
 * `channel_id → service_id → service.name → registry.find(name)`，
 * 代码只认 name，部署侧改 ID 不需要重编。对应的
 * `privchat_business_service(name='mmorpg')` 声明由 privchat 侧 migration 负责——
 * 跨模块直写他人的表违反数据所有权纪律。
 */
object MmorpgRuntimeBootstrap {

    fun initialize(ctx: NetonContext) {
        val loggers = ctx.get(LoggerFactory::class)
        val log = loggers.get("mmorpg.module")

        val roles = MmoRoleRepository(loggers.get("mmorpg.role.repo"))
        ctx.bind(MmoRoleRepository::class, roles)

        val sessions = MmoSceneSessionRepository(loggers.get("mmorpg.scene.session.repo"))
        ctx.bind(MmoSceneSessionRepository::class, sessions)

        val rooms: SceneRoomGateway =
            PrivchatSceneRoomGateway(ctx.get(PrivchatServiceClient::class))
        ctx.bind(SceneRoomGateway::class, rooms)

        val channels = SceneChannelService(
            log = loggers.get("mmorpg.scene.channel"),
            rooms = rooms,
            businessChannelResolver = ctx.get(PrivchatBusinessChannelResolver::class),
            businessServiceRepository = ctx.get(PrivchatBusinessServiceRepository::class),
        )
        ctx.bind(SceneChannelService::class, channels)

        val sequencer = SceneSequencer()
        ctx.bind(SceneSequencer::class, sequencer)

        val maps = MapRepository(loggers.get("mmorpg.map"))
        ctx.bind(MapRepository::class, maps)

        val scenes = SceneService(
            log = loggers.get("mmorpg.scene"),
            roles = roles,
            sessions = sessions,
            channels = channels,
            sequencer = sequencer,
            rooms = rooms,
            maps = maps,
        )
        ctx.bind(SceneService::class, scenes)

        ctx.get(PrivChatTransferServiceRegistry::class).register(
            MmorpgTransferHandler(
                log = loggers.get("mmorpg.transfer"),
                scenes = scenes,
            ),
        )
        log.info(
            "mmorpg.transfer_handler.registered service=${MmorpgTransferHandler.SERVICE_NAME} " +
                "routes=[${MmorpgTransferHandler.ROUTE_SCENE_HEARTBEAT}, ${MmorpgTransferHandler.ROUTE_SCENE_MOVE}, ${MmorpgTransferHandler.ROUTE_SCENE_INTERACT}]",
        )
    }
}
