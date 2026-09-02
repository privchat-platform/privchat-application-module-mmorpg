package transfer

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import logic.transfer.MmorpgTransferHandler
import neton.logging.Fields
import neton.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object SilentLogger : Logger {
    override fun trace(msg: String, fields: Fields) {}
    override fun debug(msg: String, fields: Fields) {}
    override fun info(msg: String, fields: Fields) {}
    override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
    override fun error(msg: String, fields: Fields, cause: Throwable?) {}
}

private fun ctx(route: String, body: String) = PrivChatTransferContext(
    internalRequestId = "int-1",
    clientRequestId = "cli-1",
    traceId = "trace-1",
    channelId = 9001L,
    roomId = null,
    userId = 42L,
    serviceId = 1001L,
    serviceName = "mmorpg",
    businessRefId = null,
    businessRefType = null,
    route = route,
    body = body.encodeToByteArray(),
    receivedAtMs = 1_700_000_000_000L,
)

private fun handler() = MmorpgTransferHandler(log = SilentLogger, now = { 1_700_000_000_123L })

class MmorpgTransferHandlerTest {

    @Test
    fun theServiceNameIsTheRegistryBindingKey() {
        // dispatcher routes by channel_id -> service_id -> service.name -> registry,
        // so this string is the whole contract with privchat_business_service.
        assertEquals("mmorpg", handler().serviceName)
    }

    @Test
    fun aSceneMoveIsAcceptedAndEchoesTheIdentityItArrivedWith() = runTest {
        val r = handler().handle(
            ctx(MmorpgTransferHandler.ROUTE_SCENE_MOVE, """{"scene_id":7,"x":10,"y":20,"seq":3}"""),
        )
        assertEquals(0, r.code)
        val body = Json.parseToJsonElement(r.data.decodeToString()) as JsonObject
        assertTrue(body["accepted"]!!.jsonPrimitive.boolean)
        assertEquals(7L, body["scene_id"]!!.jsonPrimitive.long)
        assertEquals(3L, body["seq"]!!.jsonPrimitive.long)
        // The point of the round trip: the caller's identity survived the two-stage
        // dispatch. If user_id were lost the chain would still "work" and be useless.
        assertEquals(42L, body["user_id"]!!.jsonPrimitive.long)
    }

    @Test
    fun anUnknownRouteIsRejectedRatherThanSilentlyAccepted() = runTest {
        val r = handler().handle(ctx("mmorpg/scene/teleport", """{"scene_id":1}"""))
        assertEquals(MmorpgTransferHandler.ERR_UNKNOWN_ROUTE, r.code)
        assertTrue(r.message.contains("teleport"), "the message should name the bad route")
    }

    @Test
    fun aBodyThatIsNotJsonIsRejected() = runTest {
        val r = handler().handle(ctx(MmorpgTransferHandler.ROUTE_SCENE_MOVE, "not json at all"))
        assertEquals(MmorpgTransferHandler.ERR_BAD_BODY, r.code)
    }

    @Test
    fun missingFieldsAreRejectedIndividually() = runTest {
        // Each field is required; a partial body must not be silently defaulted to
        // zero, which would move an avatar to the origin instead of failing.
        for (partial in listOf(
            """{"x":1,"y":2,"seq":3}""",
            """{"scene_id":1,"y":2,"seq":3}""",
            """{"scene_id":1,"x":1,"seq":3}""",
            """{"scene_id":1,"x":1,"y":2}""",
        )) {
            val r = handler().handle(ctx(MmorpgTransferHandler.ROUTE_SCENE_MOVE, partial))
            assertEquals(MmorpgTransferHandler.ERR_BAD_BODY, r.code, "accepted partial body: $partial")
        }
    }

    @Test
    fun errorCodesComeFromTheRegisteredScopeNotAdHocNumbers() = runTest {
        // registry/error_codes.toml owns 21600-21699 for mmo-scene. Inventing codes
        // outside it is what caused the 20900 / 20920 collisions in the core table.
        for (code in listOf(MmorpgTransferHandler.ERR_UNKNOWN_ROUTE, MmorpgTransferHandler.ERR_BAD_BODY)) {
            assertTrue(code in 21600..21699, "$code is outside the mmo-scene segment")
        }
    }
}
