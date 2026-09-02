package logic.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SceneHeartbeatCodecTest {

    private fun body(json: String) = json.encodeToByteArray()

    @Test
    fun decodesAWellFormedHeartbeat() {
        val r = SceneHeartbeatCodec.decodeRequest(
            body("""{"protocol_version":1,"scene_session_id":42,"request_id":"r-1","client_time_ms":1000}"""),
        ).getOrThrow()
        assertEquals(42L, r.sceneSessionId)
        assertEquals("r-1", r.requestId)
        assertEquals(1000L, r.clientTimeMs)
    }

    @Test
    fun rejectsAnUnknownProtocolVersionRatherThanBestEffortParsing() {
        // 静默兼容会让客户端以为服务端在按新语义处理，而实际是旧语义。
        val e = failureOf("""{"protocol_version":2,"scene_session_id":1,"request_id":"r","client_time_ms":1}""")
        val err = assertIs<SceneHeartbeatCodec.DecodeError.UnsupportedVersion>(e.error)
        assertEquals(2, err.got)
    }

    @Test
    fun namesTheMissingFieldSoClientBugsAreDiagnosable() {
        val e = failureOf("""{"protocol_version":1,"request_id":"r","client_time_ms":1}""")
        val err = assertIs<SceneHeartbeatCodec.DecodeError.MissingField>(e.error)
        assertEquals("scene_session_id", err.name)
        assertTrue("scene_session_id" in e.message!!)
    }

    @Test
    fun rejectsNonObjectBodies() {
        assertIs<SceneHeartbeatCodec.DecodeError.NotAnObject>(failureOf("[]").error)
        assertIs<SceneHeartbeatCodec.DecodeError.NotAnObject>(failureOf("not json").error)
    }

    @Test
    fun rejectsAnEmptyRequestIdBecauseItIsTheIdempotencyKey() {
        val err = assertIs<SceneHeartbeatCodec.DecodeError.MissingField>(
            failureOf("""{"protocol_version":1,"scene_session_id":1,"request_id":"","client_time_ms":1}""").error,
        )
        assertEquals("request_id", err.name)
    }

    @Test
    fun encodesResponseWithTheSameVersionAxis() {
        val encoded = SceneHeartbeatCodec.encodeResponse(
            SceneHeartbeatCodec.Response(sceneSessionId = 7, serverTimeMs = 99, publicSceneSeq = 3),
        ).decodeToString()
        assertTrue(""""protocol_version":1""" in encoded)
        assertTrue(""""scene_session_id":7""" in encoded)
        assertTrue(""""public_scene_seq":3""" in encoded)
    }

    private fun failureOf(json: String): SceneHeartbeatCodec.DecodeFailure =
        assertIs<SceneHeartbeatCodec.DecodeFailure>(
            SceneHeartbeatCodec.decodeRequest(body(json)).exceptionOrNull(),
        )
}
