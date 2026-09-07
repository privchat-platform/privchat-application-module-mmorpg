package logic.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logic.codec.BattleCodec
import logic.codec.BattleFlatCodec
import model.MmoBattleEvent

/** 战斗线格式(FlatBuffers)的编解码:指令 MBC1、ACK、即时操作、事件批 MBE1。 */
class BattleCodecTest {
    private fun envelope(payload: BattleCommand? = BattleCommand.Attack(9), requestId: String = "r1") = BattleFlatCodec.encodeCommand(
        BattleCodec.CommandEnvelope(requestId, 5, 2, 3, 11, 1, "COMMAND", 2, 1, payload, payload?.let { BattleCodec.encodeCommandPayload(it).toString() } ?: ""),
    )

    @Test
    fun decodesAnAttackAndNormalisesThePayload() {
        val env = BattleFlatCodec.decodeCommand(envelope()).getOrThrow()
        assertEquals(BattleCommand.Attack(9), env.payload)
        assertEquals("""{"attack":{"selected_target_id":9}}""", env.payloadJson)
        assertEquals(11L, env.commandSlotId)
        assertEquals("COMMAND", env.phase)
    }

    @Test
    fun nonePayloadIsNullNotAnError() {
        assertNull(BattleFlatCodec.decodeCommand(envelope(payload = null)).getOrThrow().payload)
    }

    @Test
    fun unsupportedKindsKeepTheirKind() {
        val env = BattleFlatCodec.decodeCommand(envelope(payload = BattleCommand.Unsupported(CommandKind.CAST_SKILL))).getOrThrow()
        assertEquals(BattleCommand.Unsupported(CommandKind.CAST_SKILL), env.payload)
    }

    @Test
    fun rejectsOversizedRequestIdAndForeignBuffers() {
        val r = BattleFlatCodec.decodeCommand(envelope(requestId = "x".repeat(65))).exceptionOrNull()
        assertIs<BattleCodec.DecodeError.TooLarge>((r as BattleCodec.DecodeFailure).error)
        // JSON 属于 HTTP 接口;transfer 上出现 JSON 是非法载荷。
        val j = BattleFlatCodec.decodeCommand("""{"protocol_version":1}""".encodeToByteArray()).exceptionOrNull()
        assertIs<BattleCodec.DecodeError.NotAnObject>((j as BattleCodec.DecodeFailure).error)
    }

    @Test
    fun ackAndInstantRoundTrip() {
        val ack = BattleCodec.Ack(5, 11, 3, true, 2, "COMMAND", 7, 99_000)
        assertEquals(ack, BattleFlatCodec.decodeAck(BattleFlatCodec.encodeAck(ack)))
        // 幂等回放存的是 JSON 形态,也要能回来。
        assertEquals(ack, BattleCodec.decodeAck(BattleCodec.encodeAck(ack).toString()))
        val req = BattleCodec.InstantRequest("s1", 5, 2, 9, BattleCodec.OP_SURRENDER)
        assertEquals(req, BattleFlatCodec.decodeInstant(BattleFlatCodec.encodeInstant(req)).getOrThrow())
    }

    @Test
    fun eventBatchCarriesVisibilityAndSeqRange() {
        val events = listOf(
            MmoBattleEvent(id = 1, battleId = 5, round = 1, visibility = "PUBLIC", streamSeq = 4, stateVersion = 10, serverTimeMs = 1, payload = """{"actor_died":{"actor_id":3}}"""),
            MmoBattleEvent(id = 2, battleId = 5, round = 1, visibility = "PUBLIC", streamSeq = 5, stateVersion = 11, serverTimeMs = 1, payload = """{"phase_changed":{"from":"RESOLVE","to":"SETTLE","round":1,"deadline_at_ms":0}}"""),
        )
        val batch = BattleFlatCodec.decodeEventBatch(BattleFlatCodec.encodeEventBatch(5, events))!!
        assertEquals("PUBLIC", batch["visibility"]!!.jsonPrimitive.content)
        assertEquals(4L, batch["first_stream_seq"]!!.jsonPrimitive.content.toLong())
        assertEquals(5L, batch["last_stream_seq"]!!.jsonPrimitive.content.toLong())
        val payloads = batch["events"]!!.jsonArray.map { it.jsonObject["payload"]!!.jsonObject.keys.single() }
        assertEquals(listOf("actor_died", "phase_changed"), payloads)
        assertEquals(3L, batch["events"]!!.jsonArray[0].jsonObject["payload"]!!.jsonObject["actor_died"]!!.jsonObject["actor_id"]!!.jsonPrimitive.content.toLong())
        val priv = BattleFlatCodec.decodeEventBatch(BattleFlatCodec.encodeEventBatch(5, listOf(events[0].copy(visibility = "PRIVATE", recipientRoleId = 2))))!!
        assertEquals("PRIVATE", priv["visibility"]!!.jsonPrimitive.content)
        assertTrue(priv["recipient_role_id"]!!.jsonPrimitive.content == "2")
    }
}
