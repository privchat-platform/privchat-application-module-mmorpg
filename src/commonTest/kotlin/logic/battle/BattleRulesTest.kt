package logic.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BattleRulesTest {
    private fun player(id: Long = 1, hp: Long = 100, speed: Int = 10, defending: Boolean = false) =
        BattleActor(id, BattleRules.PLAYER_SIDE, ownerRoleId = 7, name = "p$id", hp = hp, maxHp = 100, mp = 0, maxMp = 0, atk = 16, def = 6, speed = speed, alive = hp > 0, defending = defending)
    private fun monster(id: Long, hp: Long = 30, speed: Int = 1, atk: Long = 5) =
        BattleActor(id, BattleRules.MONSTER_SIDE, ownerRoleId = 0, name = "m$id", hp = hp, maxHp = hp, mp = 0, maxMp = 0, atk = atk, def = 0, speed = speed, alive = hp > 0, defending = false)

    @Test
    fun rngIsReproducibleFromSeedAndCursor() {
        val a = SplitMix64(99); repeat(5) { a.nextLong() }
        val b = SplitMix64(99, cursor = 5)
        assertEquals(a.nextLong(), b.nextLong())
        assertEquals(a.cursor, b.cursor)
    }

    @Test
    fun initiativeIsSpeedDescendingAndDamageFollowsThePlaceholderFormula() {
        val res = BattleRules.resolveRound(listOf(player(speed = 10), monster(2, speed = 1)), mapOf(1L to BattleCommand.Attack(2)), SplitMix64(1))
        assertEquals(listOf(1L, 2L), res.order)
        val hit = assertIs<RoundEvent.Damage>(res.events.first())
        assertEquals(2L, hit.targetId)
        // base 16 - 0/2 = 16, ±10%
        assertTrue(hit.amount in 14L..18L, "amount=${hit.amount}")
        assertNull(res.outcome)
    }

    @Test
    fun deadTargetIsRetargetedToTheLowestLivingEnemy() {
        val actors = listOf(player(), monster(2, hp = 0), monster(3), monster(4))
        val res = BattleRules.resolveRound(actors, mapOf(1L to BattleCommand.Attack(2)), SplitMix64(1))
        val hit = assertIs<RoundEvent.Damage>(res.events.first())
        assertEquals(3L, hit.targetId)
        assertEquals(RetargetReason.TARGET_DEAD, hit.retarget)
    }

    @Test
    fun defendHalvesDamageTakenThisRoundOnly() {
        // 玩家先手 DEFEND，怪物随后打他。
        val res = BattleRules.resolveRound(listOf(player(speed = 10), monster(2, speed = 1, atk = 40)), mapOf(1L to BattleCommand.Defend), SplitMix64(3))
        val hit = assertIs<RoundEvent.Damage>(res.events.single())
        // base 40 - 6/2 = 37 ±10% → 33..41，减半 → 16..20
        assertTrue(hit.amount in 16L..21L, "amount=${hit.amount}")
        assertTrue(res.actors.first { it.id == 1L }.defending)
        // 下一回合不再防御。
        val next = BattleRules.resolveRound(res.actors, emptyMap(), SplitMix64(3, 10))
        assertTrue(next.actors.first { it.id == 1L }.defending, "default action for a player is DEFEND")
    }

    @Test
    fun wipingTheMonsterSideEndsTheBattleImmediately() {
        val res = BattleRules.resolveRound(listOf(player(), monster(2, hp = 1)), mapOf(1L to BattleCommand.Attack(2)), SplitMix64(1))
        assertEquals(BattleOutcome.PLAYER_WIN, res.outcome)
        assertIs<RoundEvent.Died>(res.events[1])
        // 结束后怪物不再行动：只有一次伤害。
        assertEquals(1, res.events.count { it is RoundEvent.Damage })
    }

    @Test
    fun escapeOutcomeMatchesItsEvent() {
        var seenSuccess = false; var seenFailure = false
        for (seed in 1L..40L) {
            val res = BattleRules.resolveRound(listOf(player(), monster(2)), mapOf(1L to BattleCommand.Escape), SplitMix64(seed))
            val attempt = assertIs<RoundEvent.EscapeAttempt>(res.events.first { it is RoundEvent.EscapeAttempt })
            if (attempt.success) { seenSuccess = true; assertEquals(BattleOutcome.ESCAPED, res.outcome) } else { seenFailure = true; assertNull(res.outcome) }
        }
        assertTrue(seenSuccess && seenFailure)
    }

    @Test
    fun encounterParsingRejectsGarbage() {
        assertNull(BattleRules.parseEncounter(""))
        assertNull(BattleRules.parseEncounter("not json"))
        assertNull(BattleRules.parseEncounter("""[{"name":"x"}]"""))
        assertEquals(1, BattleRules.parseEncounter("""[{"name":"x","hp":10,"atk":1}]""")!!.size)
    }
}
