package logic.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import logic.codec.BattleCodec
import model.MmoBattleEvent

class BattleCodecTest {
    private fun envelope(payload: String = """{"attack":{"selected_target_id":9}}""", version: Int = 1, requestId: String = "r1") = """
        {"protocol_version":$version,"request_id":"$requestId","battle_id":5,"role_id":2,"actor_id":3,"command_slot_id":11,
         "round":1,"phase":"COMMAND","phase_version":2,"action_seq":1,"payload":$payload}
    """.trimIndent().encodeToByteArray()

    @Test
    fun decodesAnAttackAndNormalisesThePayload() {
        val env = BattleCodec.decodeCommand(envelope()).getOrThrow()
        assertEquals(BattleCommand.Attack(9), env.payload)
        assertEquals("""{"attack":{"selected_target_id":9}}""", env.payloadJson)
        assertEquals(11L, env.commandSlotId)
    }

    @Test
    fun emptyPayloadIsNoneNotAnError() {
        assertNull(BattleCodec.decodeCommand(envelope(payload = "{}")).getOrThrow().payload)
    }

    @Test
    fun unsupportedKindsKeepTheirKind() {
        val env = BattleCodec.decodeCommand(envelope(payload = """{"cast_skill":{"skill_id":1}}""")).getOrThrow()
        assertEquals(BattleCommand.Unsupported(CommandKind.CAST_SKILL), env.payload)
    }

    @Test
    fun rejectsWrongVersionAndOversizedRequestId() {
        val v = BattleCodec.decodeCommand(envelope(version = 2)).exceptionOrNull()
        assertIs<BattleCodec.DecodeError.UnsupportedVersion>((v as BattleCodec.DecodeFailure).error)
        val r = BattleCodec.decodeCommand(envelope(requestId = "x".repeat(65))).exceptionOrNull()
        assertIs<BattleCodec.DecodeError.TooLarge>((r as BattleCodec.DecodeFailure).error)
    }

    @Test
    fun ackRoundTrips() {
        val ack = BattleCodec.Ack(5, 11, 3, true, 2, "COMMAND", 7, 99_000)
        assertEquals(ack, BattleCodec.decodeAck(BattleCodec.encodeAck(ack).toString()))
    }

    @Test
    fun publicBatchCarriesTopicAndSeqRange() {
        val events = listOf(
            MmoBattleEvent(id = 1, battleId = 5, round = 1, visibility = "PUBLIC", streamSeq = 4, stateVersion = 10, serverTimeMs = 1, payload = """{"actor_died":{"actor_id":3}}"""),
            MmoBattleEvent(id = 2, battleId = 5, round = 1, visibility = "PUBLIC", streamSeq = 5, stateVersion = 11, serverTimeMs = 1, payload = """{"phase_changed":{"from":"RESOLVE","to":"SETTLE","round":1,"deadline_at_ms":0}}"""),
        )
        val json = BattleCodec.encodeEventBatch(5, events)
        assertTrue(""""topic":"mmorpg.battle.public"""" in json)
        assertTrue(""""first_stream_seq":4""" in json && """"last_stream_seq":5""" in json)
        assertTrue(""""batch_id":1""" in json)
        assertTrue(""""actor_died":{"actor_id":3}""" in json)
        val priv = BattleCodec.encodeEventBatch(5, listOf(events[0].copy(visibility = "PRIVATE", recipientRoleId = 2)))
        assertTrue("topic" !in priv && """"recipient_role_id":2""" in priv)
    }
}
