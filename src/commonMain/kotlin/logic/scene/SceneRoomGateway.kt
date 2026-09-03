package logic.scene

import com.netonstream.privchat.application.module.privchat.client.PrivchatServiceClient
import com.netonstream.privchat.application.module.privchat.client.dto.CreateRoomRequest
import com.netonstream.privchat.application.module.privchat.client.dto.IssueRoomTicketRequest
import com.netonstream.privchat.application.module.privchat.client.dto.TransferSendRequest

/**
 * 场景需要 privchat-server 提供的三件事，仅此三件。
 *
 * 直接依赖 `PrivchatServiceClient` 也能跑，但那是个近百方法的接口，场景逻辑对它
 * 的真实需求只有这三个。收窄之后，读代码的人一眼能看出场景到底会对 server 做
 * 什么（建房、发票、广播），而且测试不必去实现那近百个方法。
 */
interface SceneRoomGateway {

    /** 为场景申请一个 Room channel，返回 `channel_id`。 */
    suspend fun createRoom(name: String): Long

    /** 签发 Room subscribe ticket。返回 (ticket, 绝对过期 Unix 秒)。 */
    suspend fun issueTicket(
        channelId: Long,
        userId: Long,
        deviceId: String,
        scope: String,
    ): Ticket

    /** 向 Room 广播一条公共事件。 */
    suspend fun broadcast(channelId: Long, payload: String)

    /**
     * 向 channel 上的一个用户定向投递（PRIVATE 事件）。`payload` 是 UTF-8 文本；
     * base64 是传输层的事，在这里包掉。
     */
    suspend fun sendTransfer(channelId: Long, userId: Long, route: String, requestId: String, payload: String)

    data class Ticket(val ticket: String, val exp: Long)
}

/** 生产实现：转调 privchat-server 的 service API。 */
class PrivchatSceneRoomGateway(
    private val client: PrivchatServiceClient,
) : SceneRoomGateway {

    override suspend fun createRoom(name: String): Long =
        client.createRoom(CreateRoomRequest(name = name)).channelId

    override suspend fun issueTicket(
        channelId: Long,
        userId: Long,
        deviceId: String,
        scope: String,
    ): SceneRoomGateway.Ticket {
        val resp = client.issueRoomTicket(
            IssueRoomTicketRequest(
                channelId = channelId,
                userId = userId,
                deviceId = deviceId,
                scope = scope,
            ),
        )
        return SceneRoomGateway.Ticket(resp.ticket, resp.exp)
    }

    override suspend fun broadcast(channelId: Long, payload: String) {
        client.broadcastRoom(channelId, payload)
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    override suspend fun sendTransfer(channelId: Long, userId: Long, route: String, requestId: String, payload: String) {
        client.sendTransfer(
            TransferSendRequest(
                requestId = requestId,
                channelId = channelId,
                targetUserId = userId,
                route = route,
                body = kotlin.io.encoding.Base64.encode(payload.encodeToByteArray()),
            ),
        )
    }
}
