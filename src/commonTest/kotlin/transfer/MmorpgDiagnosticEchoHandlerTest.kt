package transfer

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import logic.transfer.MmorpgDiagnosticEchoHandler
import neton.logging.Fields
import neton.logging.Logger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object SilentLogger : Logger {
    override fun trace(msg: String, fields: Fields) {}
    override fun debug(msg: String, fields: Fields) {}
    override fun info(msg: String, fields: Fields) {}
    override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
    override fun error(msg: String, fields: Fields, cause: Throwable?) {}
}

private fun ctx(route: String, body: ByteArray) = PrivChatTransferContext(
    internalRequestId = "int-1",
    clientRequestId = "cli-1",
    traceId = "trace-1",
    channelId = 9001L,
    roomId = null,
    userId = 42L,
    serviceId = 9200L,
    serviceName = "mmorpg",
    businessRefId = null,
    businessRefType = null,
    route = route,
    body = body,
    receivedAtMs = 1_700_000_000_000L,
)

private fun handler() =
    MmorpgDiagnosticEchoHandler(log = SilentLogger, now = { 1_700_000_000_123L })

/** data = 摘要 JSON + 0x00 + 原始字节。 */
private fun split(data: ByteArray): Pair<JsonObject, ByteArray> {
    val sep = data.indexOf(0)
    check(sep >= 0) { "no NUL separator in response" }
    val head = Json.parseToJsonElement(data.copyOfRange(0, sep).decodeToString()) as JsonObject
    return head to data.copyOfRange(sep + 1, data.size)
}

private fun ByteArray.indexOf(b: Byte): Int {
    for (i in indices) if (this[i] == b) return i
    return -1
}

class MmorpgDiagnosticEchoHandlerTest {

    @Test
    fun theServiceNameIsTheRegistryBindingKey() {
        // dispatcher routes channel_id -> service_id -> service.name -> registry,
        // so this string is the whole contract with privchat_business_service.
        assertEquals("mmorpg", handler().serviceName)
    }

    @Test
    fun arbitraryBytesComeBackByteForByte() = runTest {
        // Deliberately not JSON: the demo must prove the channel is byte-clean,
        // otherwise it only proves JSON survives and says nothing about the
        // FlatBuffers payloads the real protocol will carry.
        val payload = byteArrayOf(0x01, 0x7F, -0x80, 0x00, 0x4D, 0x4D, 0x49, 0x31)
        val r = handler().handle(ctx(MmorpgDiagnosticEchoHandler.ROUTE_DIAGNOSTIC_ECHO, payload))
        assertEquals(0, r.code)
        val (head, echoed) = split(r.data)
        assertContentEquals(payload, echoed, "the payload did not survive the round trip")
        assertEquals(payload.size.toLong(), head["echo_bytes"]!!.jsonPrimitive.long)
    }

    @Test
    fun theCallersIdentityIsReportedBack() = runTest {
        // The point of the demo: identity must survive two-stage dispatch. A chain
        // that delivers bytes but loses who sent them is not usable.
        val r = handler().handle(
            ctx(MmorpgDiagnosticEchoHandler.ROUTE_DIAGNOSTIC_ECHO, "x".encodeToByteArray()),
        )
        val (head, _) = split(r.data)
        assertEquals(42L, head["user_id"]!!.jsonPrimitive.long)
        assertEquals(9001L, head["channel_id"]!!.jsonPrimitive.long)
    }

    @Test
    fun anEmptyBodyIsStillEchoed() = runTest {
        val r = handler().handle(ctx(MmorpgDiagnosticEchoHandler.ROUTE_DIAGNOSTIC_ECHO, ByteArray(0)))
        assertEquals(0, r.code)
        val (head, echoed) = split(r.data)
        assertEquals(0, echoed.size)
        assertEquals(0L, head["echo_bytes"]!!.jsonPrimitive.long)
    }

    @Test
    fun anUnknownRouteIsRejectedRatherThanSilentlyAccepted() = runTest {
        val r = handler().handle(ctx("mmorpg/scene/move", "{}".encodeToByteArray()))
        assertEquals(MmorpgDiagnosticEchoHandler.ERR_DEMO_ROUTE_UNKNOWN, r.code)
        assertTrue(r.message.contains("mmorpg/scene/move"), "the message should name the bad route")
    }

    @Test
    fun theFormalSceneRouteIsNotClaimedByThisDemo() = runTest {
        // Guards the boundary the review called out: occupying mmorpg/scene/move
        // would let "the chain works" be read as "the scene protocol is
        // implemented", which it is not — that contract needs scene_session_id,
        // movement_seq, MoveCommand and server-authoritative position.
        assertEquals("mmorpg/diagnostic/echo", MmorpgDiagnosticEchoHandler.ROUTE_DIAGNOSTIC_ECHO)
        val r = handler().handle(ctx("mmorpg/scene/move", ByteArray(0)))
        assertTrue(r.code != 0, "the demo must not answer the formal scene route")
    }

    @Test
    fun theDemoErrorCodeSitsAtTheEndOfThisModulesOwnSegment() {
        // registry/error_codes.toml owns 21600-21699. Putting the demo code at the
        // far end keeps it out of the range the scene protocol allocates upward
        // into, so nobody mistakes it for scene semantics.
        assertEquals(21699, MmorpgDiagnosticEchoHandler.ERR_DEMO_ROUTE_UNKNOWN)
    }
}
