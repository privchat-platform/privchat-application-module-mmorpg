package logic.battle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** `Phase`（battle_common.fbs）。 */
enum class BattlePhase { CREATED, PREPARE, COMMAND, LOCKED, RESOLVE, ROUND_END, SETTLE, CLOSED }

/** `CommandKind`。协议全集；v1 只受理 [BattleRules.ALLOWED_V1]。 */
enum class CommandKind { ATTACK, CAST_SKILL, USE_ITEM, DEFEND, PROTECT, SUMMON, RECALL, CAPTURE, ESCAPE, WAIT }

enum class RetargetReason { NONE, TARGET_DEAD, TARGET_LEFT, SKILL_RULE }

/** 已解码的回合指令。协议里有、v1 不支持的种类保留为 [Unsupported]，让 21415 而不是"解码失败"出场。 */
sealed interface BattleCommand {
    val kind: CommandKind

    /** `selected_target_id = 0` 表示"随便"（怪物 AI 用；玩家提交 0 会被 21407）。 */
    data class Attack(val targetId: Long) : BattleCommand { override val kind: CommandKind get() = CommandKind.ATTACK }
    data object Defend : BattleCommand { override val kind: CommandKind get() = CommandKind.DEFEND }
    data object Escape : BattleCommand { override val kind: CommandKind get() = CommandKind.ESCAPE }
    data object Wait : BattleCommand { override val kind: CommandKind get() = CommandKind.WAIT }
    data class Unsupported(override val kind: CommandKind) : BattleCommand
}

/**
 * 权威 RNG（`rng_algorithm_version = 1`）：SplitMix64。
 *
 * 状态是 `seed + cursor * GAMMA`，所以从任意 cursor 恢复是 O(1)——回合结算只需要把
 * `rng_cursor` 落库，重放同一 seed + cursor 得到同一序列（spec §5.1 可审计）。
 */
class SplitMix64(private val seed: Long, cursor: Long = 0) {
    var cursor: Long = cursor
        private set

    fun nextLong(): Long {
        cursor += 1
        var z = seed + cursor * GAMMA
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** `[0, bound)`。 */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound).toInt()
    }

    private companion object {
        const val GAMMA: Long = -0x61C8864680B583EBL
        const val MIX1: Long = -0x40A7B892E31B1A47L
        const val MIX2: Long = -0x6B2FB644ECCEEE15L
    }
}

/** 规则引擎看到的单位；与 `mmo_battle_actor` 一一对应但不带持久化字段。 */
data class BattleActor(
    val id: Long,
    val side: Int,
    val ownerRoleId: Long,
    val name: String,
    val hp: Long,
    val maxHp: Long,
    val mp: Long,
    val maxMp: Long,
    val atk: Long,
    val def: Long,
    val speed: Int,
    val alive: Boolean,
    val defending: Boolean,
)

/** 结算结果。`winnerSide`：0 玩家、1 怪物、2 无胜者（逃跑 / 中止）。 */
enum class BattleOutcome(val winnerSide: Int) { PLAYER_WIN(0), MONSTER_WIN(1), ESCAPED(2) }

sealed interface RoundEvent {
    data class Damage(val sourceId: Long, val targetId: Long, val amount: Long, val retarget: RetargetReason) : RoundEvent
    data class Died(val actorId: Long) : RoundEvent
    data class EscapeAttempt(val actorId: Long, val success: Boolean) : RoundEvent
}

data class RoundResolution(
    val actors: List<BattleActor>,
    val order: List<Long>,
    val events: List<RoundEvent>,
    val outcome: BattleOutcome?,
)

/** 遭遇配置里的一个怪物模板（`mmo_npc.encounter`）。 */
data class MonsterTemplate(val name: String, val hp: Long, val mp: Long, val atk: Long, val def: Long, val speed: Int)

/**
 * v1 最小规则集（MMO_BATTLE_PROTOCOL_SPEC §15.5）。**纯函数**：输入单位、指令与 RNG，
 * 输出新单位状态与事件。它不知道数据库、不知道协议编码；数值公式是占位，替换它
 * 不动状态机与协议。
 */
object BattleRules {
    const val PLAYER_SIDE: Int = 0
    const val MONSTER_SIDE: Int = 1

    const val RNG_ALGORITHM_VERSION: Int = 1
    const val ROUND_MS: Long = 30_000
    /** SETTLE → CLOSED 的宽限：让客户端看完结算再切回场景。 */
    const val EXIT_GRACE_MS: Long = 3_000
    const val ESCAPE_CHANCE_PERCENT: Int = 50
    const val MAX_PLAYER_ACTORS: Int = 5
    const val MAX_MONSTERS: Int = 3

    val ALLOWED_V1: List<CommandKind> = listOf(CommandKind.ATTACK, CommandKind.DEFEND, CommandKind.ESCAPE, CommandKind.WAIT)

    /** 玩家单位的占位属性：v1 没有角色成长，人人一样。 */
    val PLAYER_TEMPLATE: MonsterTemplate = MonsterTemplate(name = "", hp = 120, mp = 30, atk = 16, def = 6, speed = 10)

    /** 解析 `mmo_npc.encounter`；格式错误或为空返回 null（= 不可战）。 */
    fun parseEncounter(json: String): List<MonsterTemplate>? {
        if (json.isBlank()) return null
        val array = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return null
        val templates = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return@mapNotNull null
            MonsterTemplate(
                name = name,
                hp = obj["hp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                mp = obj["mp"]?.jsonPrimitive?.longOrNull ?: 0,
                atk = obj["atk"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                def = obj["def"]?.jsonPrimitive?.longOrNull ?: 0,
                speed = obj["speed"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }
        return templates.takeIf { it.isNotEmpty() && it.size <= MAX_MONSTERS && it.all { t -> t.hp > 0 } }
    }

    /**
     * 结算一回合。
     *
     * - 行动顺序：`speed` 降序，同速按权威 RNG，再同按 `actor_id`（§5.1）。
     * - 没有指令的玩家单位默认 `DEFEND`；怪物随机攻击一个存活的对方单位。
     * - 原目标已死 → 改选对方存活单位中 `actor_id` 最小者，`retarget = TARGET_DEAD`。
     * - 一方全灭或玩家逃跑成功即结束；之后的行动不再执行。
     */
    fun resolveRound(actors: List<BattleActor>, commands: Map<Long, BattleCommand>, rng: SplitMix64): RoundResolution {
        // DEFEND 只保护声明它的那一回合。
        val state = actors.map { it.copy(defending = false) }.associateBy { it.id }.toMutableMap()
        val living = actors.filter { it.alive }
        val jitter = living.associate { it.id to rng.nextInt(1_000) }
        val order = living
            .sortedWith(compareByDescending<BattleActor> { it.speed }.thenByDescending { jitter.getValue(it.id) }.thenBy { it.id })
            .map { it.id }
        val events = mutableListOf<RoundEvent>()
        var outcome: BattleOutcome? = null

        for (id in order) {
            if (outcome != null) break
            val actor = state.getValue(id)
            if (!actor.alive) continue
            val command = commands[id] ?: if (actor.side == PLAYER_SIDE) BattleCommand.Defend else BattleCommand.Attack(0)
            when (command) {
                is BattleCommand.Attack -> {
                    val (target, reason) = pickTarget(state, actor, command.targetId, rng) ?: continue
                    val amount = damage(actor, target, rng)
                    val hp = (target.hp - amount).coerceAtLeast(0)
                    val hit = target.copy(hp = hp, alive = hp > 0)
                    state[hit.id] = hit
                    events += RoundEvent.Damage(actor.id, hit.id, amount, reason)
                    if (!hit.alive) events += RoundEvent.Died(hit.id)
                    outcome = wipeOutcome(state)
                }
                BattleCommand.Defend -> state[id] = actor.copy(defending = true)
                BattleCommand.Escape -> {
                    val success = rng.nextInt(100) < ESCAPE_CHANCE_PERCENT
                    events += RoundEvent.EscapeAttempt(id, success)
                    if (success && actor.side == PLAYER_SIDE) outcome = BattleOutcome.ESCAPED
                }
                BattleCommand.Wait, is BattleCommand.Unsupported -> Unit
            }
        }
        return RoundResolution(actors.map { state.getValue(it.id) }, order, events, outcome)
    }

    /** 占位公式：`max(1, atk - def/2) ± 10%`，防御中减半，定点整数。 */
    fun damage(source: BattleActor, target: BattleActor, rng: SplitMix64): Long {
        val base = (source.atk - target.def / 2).coerceAtLeast(1)
        val jitterPercent = rng.nextInt(21) - 10
        var amount = (base + base * jitterPercent / 100).coerceAtLeast(1)
        if (target.defending) amount = (amount / 2).coerceAtLeast(1)
        return amount
    }

    private fun pickTarget(
        state: Map<Long, BattleActor>,
        source: BattleActor,
        wanted: Long,
        rng: SplitMix64,
    ): Pair<BattleActor, RetargetReason>? {
        val enemies = state.values.filter { it.alive && it.side != source.side }.sortedBy { it.id }
        if (enemies.isEmpty()) return null
        if (wanted == 0L) return enemies[rng.nextInt(enemies.size)] to RetargetReason.NONE
        val chosen = state[wanted]
        return if (chosen != null && chosen.alive && chosen.side != source.side) chosen to RetargetReason.NONE
        else enemies.first() to RetargetReason.TARGET_DEAD
    }

    private fun wipeOutcome(state: Map<Long, BattleActor>): BattleOutcome? {
        val playersAlive = state.values.any { it.alive && it.side == PLAYER_SIDE }
        val monstersAlive = state.values.any { it.alive && it.side == MONSTER_SIDE }
        return when {
            !monstersAlive -> BattleOutcome.PLAYER_WIN
            !playersAlive -> BattleOutcome.MONSTER_WIN
            else -> null
        }
    }
}
