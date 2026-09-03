package logic.codec

import logic.scene.Vec2Fixed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SceneMoveCodecTest {

    private fun decode(json: String) = SceneMoveCodec.decodeIntent(json.encodeToByteArray())

    @Test
    fun decodesAMoveTo() {
        val i = decode("""{"protocol_version":1,"scene_session_id":7,"request_id":"r1","movement_seq":3,
            "command":{"move_to":{"target_position":{"x":10,"y":-2}}},"client_time_ms":5}""").getOrThrow()
        assertEquals(SceneMoveCodec.Command.MoveTo(Vec2Fixed(10, -2)), i.command)
        assertEquals(3L, i.movementSeq)
    }

    @Test
    fun decodesStopAndCancelPath() {
        assertEquals(SceneMoveCodec.Command.Stop, decode("""{"protocol_version":1,"scene_session_id":7,"request_id":"r","movement_seq":1,"command":{"stop":{}}}""").getOrThrow().command)
        assertEquals(SceneMoveCodec.Command.CancelPath(9), decode("""{"protocol_version":1,"scene_session_id":7,"request_id":"r","movement_seq":1,"command":{"cancel_path":{"path_id":9}}}""").getOrThrow().command)
    }

    @Test
    fun aMissingCommandDecodesToNullForTheServiceToRefuse() {
        // V-I1 is semantic, not structural: the codec passes it through as null.
        assertNull(decode("""{"protocol_version":1,"scene_session_id":7,"request_id":"r","movement_seq":1}""").getOrThrow().command)
    }

    @Test
    fun rejectsAnOversizedRequestId() {
        val long = "x".repeat(65)
        val e = assertIs<SceneMoveCodec.DecodeFailure>(decode("""{"protocol_version":1,"scene_session_id":7,"request_id":"$long","movement_seq":1}""").exceptionOrNull())
        assertIs<SceneMoveCodec.DecodeError.RequestIdTooLong>(e.error)
    }

    @Test
    fun canonicalFormIgnoresTheDiagnosticClock() {
        val a = decode("""{"protocol_version":1,"scene_session_id":7,"request_id":"r","movement_seq":1,"command":{"stop":{}},"client_time_ms":1}""").getOrThrow()
        val b = decode("""{"protocol_version":1,"scene_session_id":7,"request_id":"r","movement_seq":1,"command":{"stop":{}},"client_time_ms":999}""").getOrThrow()
        assertEquals(a.canonical(), b.canonical())
    }
}
