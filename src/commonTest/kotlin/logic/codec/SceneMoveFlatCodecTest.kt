package logic.codec

import logic.scene.Vec2Fixed
import protocol.ProtocolFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FlatBuffers 移动编解码:golden fixture(与 Rust / Godot 共用同一份 .bin)+ 自往返 + 坏包。 */
class SceneMoveFlatCodecTest {
    @Test
    fun decodesTheGoldenMoveToFixture() {
        val intent = SceneMoveFlatCodec.decodeIntent(ProtocolFixtures.scene_v1_valid_intent_move_to_bin).getOrThrow()
        // fixture: protocol/fixtures/scene/v1/valid/intent/move_to.json
        assertEquals(1, intent.protocolVersion)
        assertEquals(42L, intent.sceneSessionId)
        assertEquals("req-1", intent.requestId)
        assertEquals(7L, intent.movementSeq)
        assertEquals(SceneMoveCodec.Command.MoveTo(Vec2Fixed(1500, -2300)), intent.command)
        assertEquals(1_700_000_000_000L, intent.clientTimeMs)
    }

    @Test
    fun decodesTheGoldenStopFixture() {
        val intent = SceneMoveFlatCodec.decodeIntent(ProtocolFixtures.scene_v1_valid_intent_stop_bin).getOrThrow()
        assertEquals("req-2", intent.requestId)
        assertEquals(SceneMoveCodec.Command.Stop, intent.command)
    }

    @Test
    fun invalidFixturesSurfaceAsTheSameSemanticErrorsAsJson() {
        // V-I1 command NONE:解码成功、command 为 null,由服务层报 21610(与 JSON 路径一致)。
        assertNull(SceneMoveFlatCodec.decodeIntent(ProtocolFixtures.scene_v1_invalid_intent_v_i1__command_none_bin).getOrThrow().command)
        // V-I2 空 request_id → MissingField。
        val e = SceneMoveFlatCodec.decodeIntent(ProtocolFixtures.scene_v1_invalid_intent_v_i2__empty_request_id_bin).exceptionOrNull()
        assertIs<SceneMoveCodec.DecodeError.MissingField>((e as SceneMoveCodec.DecodeFailure).error)
    }

    @Test
    fun roundTripsIntentAndAck() {
        val intent = SceneMoveCodec.Intent(1, 99, "gd-1", 3, SceneMoveCodec.Command.CancelPath(12), 5)
        val bytes = SceneMoveFlatCodec.encodeIntent(intent)
        assertTrue(SceneMoveFlatCodec.looksLikeIntent(bytes))
        assertEquals(intent, SceneMoveFlatCodec.decodeIntent(bytes).getOrThrow())
        val ack = SceneMoveCodec.Ack(99, "gd-1", 3, 4, true, 8)
        val ackBytes = SceneMoveFlatCodec.encodeAck(ack)
        assertEquals("MMA1", ackBytes.copyOfRange(4, 8).decodeToString())
        assertEquals(ack, SceneMoveFlatCodec.decodeAck(ackBytes))
    }

    @Test
    fun rejectsJsonAndTruncatedBuffers() {
        assertFalse(SceneMoveFlatCodec.looksLikeIntent("""{"protocol_version":1}""".encodeToByteArray()))
        val truncated = ProtocolFixtures.scene_v1_valid_intent_move_to_bin.copyOf(12)
        assertTrue(SceneMoveFlatCodec.decodeIntent(truncated).isFailure)
        val wrongVersion = SceneMoveFlatCodec.encodeIntent(SceneMoveCodec.Intent(2, 1, "r", 1, SceneMoveCodec.Command.Stop, 0))
        assertIs<SceneMoveCodec.DecodeError.UnsupportedVersion>((SceneMoveFlatCodec.decodeIntent(wrongVersion).exceptionOrNull() as SceneMoveCodec.DecodeFailure).error)
    }
}

class SceneMoveFlatCodecGodotFixtureTest {
    /** 由 privchat-godot 的通用反射 codec 编出的 MMI1(fixtures/.../godot_move_to.bin),Kotlin 必须解得出同一结构。 */
    @Test
    fun decodesTheGodotEncodedIntent() {
        val bytes = ProtocolFixtures.scene_v1_valid_intent_godot_move_to_bin
        assertTrue(SceneMoveFlatCodec.looksLikeIntent(bytes))
        val intent = SceneMoveFlatCodec.decodeIntent(bytes).getOrThrow()
        assertEquals(8L, intent.sceneSessionId)
        assertEquals("gd-fb-3985", intent.requestId)
        assertEquals(1L, intent.movementSeq)
        assertEquals(SceneMoveCodec.Command.MoveTo(Vec2Fixed(60_000, 40_000)), intent.command)
    }
}
