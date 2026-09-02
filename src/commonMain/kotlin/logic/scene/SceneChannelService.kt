package logic.scene

import model.MmoSceneChannel
import neton.database.dsl.*
import neton.logging.Logger
import table.MmoSceneChannelTable
import transfer.PrivchatBusinessChannelResolver
import transfer.PrivchatBusinessServiceRepository

/**
 * `SceneRef` → 真实 Room `channel_id` 的解析与provision。
 *
 * ### 幂等是硬要求
 *
 * 同一个场景必须始终解析到同一个 channel。如果重启或并发进入各建了一个 Room，
 * 同场景的玩家会被分到两个广播域——两边都收得到自己的事件、收不到对方的，
 * 表现出来是"玩家互相看不见"，而每一层日志看上去都正常。
 *
 * ### 双写的边界
 *
 * - `mmo_scene_channel`：本模块自持的正向索引（scene_ref → channel）。
 * - `privchat_business_channel`：dispatch 路由表（channel → service），
 *   owner 是 privchat 模块，只能经 [PrivchatBusinessChannelResolver.bind] 写。
 *
 * 业务代码只跟本 service 打交道，不直接碰这两张表中的任何一张。
 */
open class SceneChannelService(
    private val log: Logger,
    private val rooms: SceneRoomGateway,
    private val businessChannelResolver: PrivchatBusinessChannelResolver,
    private val businessServiceRepository: PrivchatBusinessServiceRepository,
) {

    /** 已存在则直接返回；否则申请 Room、写索引、登记路由。 */
    open suspend fun getOrCreate(sceneRef: SceneRef): Long {
        val encoded = sceneRef.encode()
        findChannel(encoded)?.let { return it.channelId }

        // service_id 是运维分配的，代码只认 name（PrivChatTransferHandler 的契约）。
        // 缺这行说明 privchat 侧的 service 声明没跑到，此时建 Room 也没意义：
        // channel 会存在但永远 dispatch 不到本模块，排查成本远高于启动即失败。
        val service = businessServiceRepository.findByName(SERVICE_NAME)
            ?: error(
                "privchat_business_service(name='$SERVICE_NAME') is missing. " +
                    "Scene channels cannot be routed to this module until it is declared.",
            )

        val channelId = rooms.createRoom("mmo_scene:$encoded")
        require(channelId > 0) { "createRoom returned a non-positive channel_id: $channelId" }

        // 并发下可能有人先落了库。此时丢掉自己刚建的 Room 用先到的那个：多出来的
        // Room 是 server 侧纯内存对象，没人订阅就自然消亡，而"一场景一 channel"
        // 这条不变式必须守住。
        findChannel(encoded)?.let { winner ->
            log.info(
                "mmo.scene.channel.race_lost scene_ref=$encoded " +
                    "discarded=$channelId kept=${winner.channelId}",
            )
            return winner.channelId
        }

        MmoSceneChannelTable.insert(
            MmoSceneChannel(sceneRef = encoded, channelId = channelId),
        )
        businessChannelResolver.bind(
            channelId = channelId,
            serviceId = service.id,
            businessRefId = sceneRef.id,
            businessRefType = BUSINESS_REF_TYPE,
            dispatchTransferEnabled = 1,
            dispatchMessageEnabled = 0,
        )
        log.info(
            "mmo.scene.channel.created scene_ref=$encoded channel_id=$channelId " +
                "service_id=${service.id}",
        )
        return channelId
    }

    /** 只查不建。channel 尚未 provision 时返回 `null`。 */
    open suspend fun find(sceneRef: SceneRef): Long? = findChannel(sceneRef.encode())?.channelId

    private suspend fun findChannel(encoded: String): MmoSceneChannel? =
        MmoSceneChannelTable.oneWhere {
            and(
                MmoSceneChannel::sceneRef eq encoded,
                MmoSceneChannel::status eq 1,
            )
        }

    companion object {
        /** 必须与 `privchat_business_service.name` 一致（dispatch 靠它找 handler）。 */
        const val SERVICE_NAME: String = "mmorpg"

        /** 写进 `privchat_business_channel.business_ref_type`，便于运维反查。 */
        const val BUSINESS_REF_TYPE: String = "mmo_scene"
    }
}
