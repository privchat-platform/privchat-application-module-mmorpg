package logic.codec

import logic.battle.BattleCommand
import protocol.ProtocolFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 战斗 golden fixtures(protocol/fixtures/battle/v1/**)在 Kotlin 侧的解码结果必须与
 * validate.py 的判定一致:合法样本解出来的字段等于 JSON 源;负向样本要么被 codec 拒,
 * 要么解成 service 会拒的形态(V-BC1 的 payload=null 由 service 判 21407)。
 */
class BattleFlatCodecFixtureTest {
    @Test
    fun decodesTheAttackCommandFixture() {
        val env = BattleFlatCodec.decodeCommand(ProtocolFixtures.battle_v1_valid_command_attack_bin).getOrThrow()
        assertEquals("gd-1", env.requestId)
        assertEquals(7L, env.battleId)
        assertEquals(11L, env.roleId)
        assertEquals(101L, env.actorId)
        assertEquals(5001L, env.commandSlotId)
        assertEquals("COMMAND", env.phase)
        assertEquals(1, env.actionSeq)
        val attack = assertIs<BattleCommand.Attack>(env.payload)
        assertEquals(202L, attack.targetId)
    }

    @Test
    fun decodesTheDefendCommandFixture() {
        val env = BattleFlatCodec.decodeCommand(ProtocolFixtures.battle_v1_valid_command_defend_bin).getOrThrow()
        assertEquals(BattleCommand.Defend, env.payload)
    }

    @Test
    fun payloadNoneDecodesToNullForTheServiceToReject() {
        // V-BC1 属 service(21407);codec 只把 NONE 表示成 null,不自己拒。
        val env = BattleFlatCodec.decodeCommand(ProtocolFixtures.battle_v1_invalid_command_v_bc1__payload_none_bin).getOrThrow()
        assertNull(env.payload)
    }

    @Test
    fun emptyRequestIdIsRejectedByTheCodec() {
        // V-BC2 在 codec 层(21410 由 handler 映射)。
        val e = BattleFlatCodec.decodeCommand(ProtocolFixtures.battle_v1_invalid_command_v_bc2__empty_request_id_bin).exceptionOrNull()
        val failure = assertIs<BattleCodec.DecodeFailure>(e)
        assertIs<BattleCodec.DecodeError.MissingField>(failure.error)
    }

    @Test
    fun phaseMismatchIsLeftToTheService() {
        // V-BC3 依赖服务端当前阶段:codec 原样交出 phase,由 service 判 21401。
        val env = BattleFlatCodec.decodeCommand(ProtocolFixtures.battle_v1_invalid_command_v_bc3__phase_locked_bin).getOrThrow()
        assertEquals("LOCKED", env.phase)
    }

    @Test
    fun decodesTheSurrenderFixture() {
        val r = BattleFlatCodec.decodeInstant(ProtocolFixtures.battle_v1_valid_instant_surrender_bin).getOrThrow()
        assertEquals("gd-3", r.requestId)
        assertEquals(3L, r.stateVersion)
        assertEquals(BattleCodec.OP_SURRENDER, r.op)
    }

    @Test
    fun instantWithEmptyRequestIdIsRejected() {
        val e = BattleFlatCodec.decodeInstant(ProtocolFixtures.battle_v1_invalid_instant_v_bq1__empty_request_id_bin).exceptionOrNull()
        assertIs<BattleCodec.DecodeFailure>(e)
    }

    @Test
    fun decodesThePublicAndPrivateEventBatchFixtures() {
        val pub = assertNotNull(BattleFlatCodec.decodeEventBatch(ProtocolFixtures.battle_v1_valid_event_public_batch_bin))
        assertEquals("PUBLIC", pub["visibility"].toString().trim('"'))
        assertEquals(2, pub["events"]!!.let { (it as kotlinx.serialization.json.JsonArray).size })
        val priv = assertNotNull(BattleFlatCodec.decodeEventBatch(ProtocolFixtures.battle_v1_valid_event_private_batch_bin))
        assertEquals("11", priv["recipient_role_id"].toString())
        val payloads = (priv["events"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonObject)["payload"].toString() }
        assertTrue(payloads[0].contains("slots_offered"), payloads[0])
        assertTrue(payloads[1].contains("command_accepted"), payloads[1])
    }

    @Test
    fun wrongIdentifierIsNotABattleCommand() {
        // 场景意图(MMI1)喂给战斗指令解码器:identifier 门禁在解析之前。
        val e = BattleFlatCodec.decodeCommand(ProtocolFixtures.scene_v1_valid_intent_move_to_bin).exceptionOrNull()
        val failure = assertIs<BattleCodec.DecodeFailure>(e)
        assertIs<BattleCodec.DecodeError.NotAnObject>(failure.error)
    }
}
