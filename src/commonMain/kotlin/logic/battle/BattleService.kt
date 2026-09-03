package logic.battle

import kotlin.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import logic.MmoErrorCodes
import logic.codec.BattleCodec
import logic.map.MapRepository
import logic.scene.MmoRoleRepository
import logic.scene.MmoSceneSessionRepository
import logic.scene.SceneChannelService
import logic.scene.SceneMovement
import logic.scene.SceneOutcome
import logic.scene.SceneRef
import logic.scene.SceneRoomGateway
import logic.scene.SceneService
import logic.scene.SceneSessionState
import logic.scene.Vec2Fixed
import model.MmoBattle
import model.MmoBattleActor
import model.MmoBattleCommand
import model.MmoBattleEvent
import model.MmoBattleSlot
import model.MmoBattleTransition
import model.MmoRewardSettlement
import model.MmoSceneSession
import neton.logging.Logger

/**
 * 回合制战斗 v1（MMO_BATTLE_PROTOCOL_SPEC §4 状态机、§15 落地契约）。
 *
 * ### 事务与 outbox
 *
 * 每次状态推进（发起、受理指令、回合结算、结算、关闭）都在一个 [BattleTransactor.run]
 * 里完成：战斗头、单位、slot、指令与事件 outbox 同事务落库，提交后再 [flush] 投递。
 * 投递失败不回滚状态——事件已经在 outbox 里，下一次 tick 补投；客户端按 `stream_seq`
 * 去重。反过来"先投递再落库"会在崩溃时给客户端一个服务端不承认的世界。
 *
 * ### 校验顺序是契约
 *
 * [submit] 的判定顺序按 VALIDATION.md 冻结：幂等命中必须早于阶段与序号判定，否则
 * 合法重试会在 LOCKED 之后被当成非法提交。
 */
class BattleService(
    private val log: Logger,
    private val roles: MmoRoleRepository,
    private val sessions: MmoSceneSessionRepository,
    private val channels: SceneChannelService,
    private val maps: MapRepository,
    private val rooms: SceneRoomGateway,
    private val repo: BattleRepository,
    private val tx: BattleTransactor,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val seeds: () -> Long = { Clock.System.now().toEpochMilliseconds() xor 0x5DEECE66DL },
) {

    // ---------------- 发起（§15.2 saga）----------------

    suspend fun start(userId: Long, roleId: Long, rawSceneRef: String, npcId: Long, deviceId: String): SceneOutcome<BattleEntry> {
        val sceneRef = SceneRef.parse(rawSceneRef)
            ?: return SceneOutcome.Failure(MmoErrorCodes.SCENE_GENERATION_MISMATCH, "malformed scene_ref: '$rawSceneRef'")
        val role = roles.findById(roleId)?.takeIf { it.userId == userId && it.status == 1 }
            ?: return SceneOutcome.Failure(MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE, "role $roleId is not controllable by user $userId")
        val session = sessions.findActiveByRole(roleId)
        if (session == null || session.sceneRef != sceneRef.encode()) {
            return SceneOutcome.Failure(MmoErrorCodes.SCENE_SESSION_INVALID, "role $roleId has no active session in ${sceneRef.encode()}")
        }
        if (session.state != SceneSessionState.ACTIVE) {
            return SceneOutcome.Failure(MmoErrorCodes.SCENE_STATE_NOT_ALLOWED, "session ${session.id} is ${session.state}; cannot start a battle")
        }
        val scene = channels.findScene(sceneRef) ?: return SceneOutcome.Failure(MmoErrorCodes.SCENE_NOT_FOUND, "scene ${sceneRef.encode()} is not open")
        val map = maps.find(scene.mapId) ?: return SceneOutcome.Failure(MmoErrorCodes.SCENE_NOT_FOUND, "scene ${sceneRef.encode()} refers to a missing map")
        val npc = map.npc(npcId) ?: return SceneOutcome.Failure(MmoErrorCodes.SCENE_INTERACT_TARGET_NOT_FOUND, "npc $npcId is not on map ${map.id}")
        val now = nowMs()
        // 与 interact 同一把尺：权威位置，不信客户端。
        val gap = SceneMovement.distance(SceneMovement.positionAt(session, now), Vec2Fixed(npc.x, npc.y))
        if (gap > npc.interactRange) {
            return SceneOutcome.Failure(MmoErrorCodes.SCENE_INTERACT_OUT_OF_RANGE, "npc ${npc.id} is $gap away; interact range is ${npc.interactRange}")
        }
        val monsters = BattleRules.parseEncounter(npc.encounter)
            ?: return SceneOutcome.Failure(MmoErrorCodes.SCENE_COMMAND_INVALID, "npc ${npc.id} has no encounter; it cannot be fought")

        // 第一段：PENDING + CREATED + BATTLE_ENTERING，同事务。
        val (battle, transition) = tx.run {
            val created = repo.insertBattle(
                MmoBattle(sceneRef = session.sceneRef, sceneSessionId = session.id, roleId = roleId, rngSeed = seeds()),
            )
            val t = BattleRules.PLAYER_TEMPLATE
            repo.insertActor(
                MmoBattleActor(
                    battleId = created.id, side = BattleRules.PLAYER_SIDE, ownerRoleId = roleId, name = role.name, kind = "ROLE",
                    position = 0, hp = t.hp, maxHp = t.hp, mp = t.mp, maxMp = t.mp, atk = t.atk, defense = t.def, speed = t.speed,
                ),
            )
            monsters.forEachIndexed { i, m ->
                repo.insertActor(
                    MmoBattleActor(
                        battleId = created.id, side = BattleRules.MONSTER_SIDE, name = m.name, kind = "MONSTER", position = i,
                        hp = m.hp, maxHp = m.hp, mp = m.mp, maxMp = m.mp, atk = m.atk, defense = m.def, speed = m.speed,
                    ),
                )
            }
            val transition = repo.insertTransition(MmoBattleTransition(roleId = roleId, sceneSessionId = session.id, battleId = created.id))
            sessions.updateState(session, SceneSessionState.BATTLE_ENTERING)
            created to transition
        }

        // 外部副作用：建 Room、绑路由、签 ticket。任一失败 → 补偿回 ACTIVE。
        val prepared = runCatching {
            val channelId = channels.provisionBattleChannel(battle.id)
            val ticket = rooms.issueTicket(channelId, userId, deviceId, SceneService.TICKET_SCOPE)
            channelId to ticket
        }
        val (channelId, ticket) = prepared.getOrElse { failure ->
            log.warn("mmo.battle.start.failed battle_id=${battle.id} role_id=$roleId err=${failure.message}")
            tx.run {
                repo.updateTransition(transition.copy(status = TRANSITION_FAILED, reason = failure.message ?: "provision failed"))
                repo.updateBattle(battle.copy(phase = BattlePhase.CLOSED.name, winnerSide = 2))
                sessions.findById(session.id)?.let { sessions.updateState(it, SceneSessionState.ACTIVE) }
            }
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_NOT_FOUND, "battle could not be provisioned: ${failure.message}")
        }

        // 第二段：全部就绪才 READY / IN_BATTLE，并开第 1 回合。
        val ready = tx.run {
            val live = Live(battle.copy(channelId = channelId), repo.listActors(battle.id).toMutableList())
            live.emitPublic(BattleCodec.phaseChanged(BattlePhase.CREATED, BattlePhase.COMMAND, 1, now + BattleRules.ROUND_MS), now)
            openRound(live, 1, now)
            val t = transition.copy(status = TRANSITION_READY, channelId = channelId, ticket = ticket.ticket, ticketExp = ticket.exp)
            repo.updateTransition(t)
            sessions.findById(session.id)?.let { sessions.updateState(it, SceneSessionState.IN_BATTLE) }
            commit(live)
            t
        }
        flush(battle.id)
        log.info("mmo.battle.started battle_id=${battle.id} role_id=$roleId npc_id=$npcId channel_id=$channelId")
        return SceneOutcome.Success(entryOf(ready))
    }

    /** 断线后凭 transition_id 续接（§15.1）。 */
    suspend fun transition(userId: Long, transitionId: Long): SceneOutcome<BattleEntry> {
        val t = repo.getTransition(transitionId) ?: return SceneOutcome.Failure(MmoErrorCodes.BATTLE_NOT_FOUND, "transition $transitionId does not exist")
        roles.findById(t.roleId)?.takeIf { it.userId == userId }
            ?: return SceneOutcome.Failure(MmoErrorCodes.SCENE_ENTITY_NOT_CONTROLLABLE, "transition $transitionId is not yours")
        return SceneOutcome.Success(entryOf(t))
    }

    // ---------------- 指令提交（§4.3 / V-BC*）----------------

    suspend fun submit(userId: Long, channelId: Long, env: BattleCodec.CommandEnvelope): SceneOutcome<BattleCodec.Ack> {
        val battle = repo.getBattle(env.battleId)
        if (battle == null || battle.phase == BattlePhase.CLOSED.name || battle.channelId != channelId) {
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_NOT_FOUND, "battle ${env.battleId} is not open on channel $channelId")
        }
        // 身份链：user 拥有 role，role 控制 actor，actor 在本场且能行动。
        roles.findById(env.roleId)?.takeIf { it.userId == userId && it.status == 1 }
            ?: return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ACTOR_NOT_CONTROLLABLE, "role ${env.roleId} is not controllable by user $userId")
        val actors = repo.listActors(battle.id)
        val actor = actors.firstOrNull { it.id == env.actorId }
            ?: return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ACTOR_NOT_IN_BATTLE, "actor ${env.actorId} is not in battle ${battle.id}")
        if (actor.ownerRoleId != env.roleId) {
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ACTOR_NOT_CONTROLLABLE, "actor ${actor.id} is not controlled by role ${env.roleId}")
        }
        if (actor.alive != 1) return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ACTOR_CANNOT_ACT, "actor ${actor.id} is dead")

        // 幂等矩阵（§4.3）——必须早于阶段 / 序号判定。
        repo.findCommandByRequest(battle.id, env.requestId)?.let { seen ->
            return if (seen.payload == env.payloadJson && seen.actorId == actor.id) {
                BattleCodec.decodeAck(seen.ack)?.let { SceneOutcome.Success(it) }
                    ?: SceneOutcome.Failure(MmoErrorCodes.BATTLE_COMMAND_REJECTED, "stored ack for '${env.requestId}' is unreadable")
            } else {
                SceneOutcome.Failure(MmoErrorCodes.BATTLE_IDEMPOTENCY_KEY_REUSE, "request_id '${env.requestId}' was already used with a different payload")
            }
        }
        repo.findCommandBySeq(battle.id, actor.id, env.actionSeq)?.let {
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ACTION_SEQ_REUSE, "action_seq ${env.actionSeq} was already used by actor ${actor.id}")
        }

        if (battle.phase != BattlePhase.COMMAND.name || env.phase != BattlePhase.COMMAND.name) {
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_PHASE_MISMATCH, "battle ${battle.id} is in ${battle.phase}, not COMMAND")
        }
        if (env.round != battle.round) return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ROUND_MISMATCH, "round ${env.round} != current ${battle.round}")
        if (env.phaseVersion != battle.phaseVersion) {
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_PHASE_VERSION_STALE, "phase_version ${env.phaseVersion} != current ${battle.phaseVersion}")
        }
        val slot = repo.getSlot(env.commandSlotId)?.takeIf { it.battleId == battle.id && it.roundNo == battle.round }
            ?: return SceneOutcome.Failure(MmoErrorCodes.BATTLE_SLOT_NOT_FOUND, "command_slot_id ${env.commandSlotId} is not open in round ${battle.round}")
        if (slot.actorId != actor.id) return SceneOutcome.Failure(MmoErrorCodes.BATTLE_SLOT_NOT_OWNED, "slot ${slot.id} belongs to actor ${slot.actorId}")
        val command = env.payload ?: return SceneOutcome.Failure(MmoErrorCodes.BATTLE_COMMAND_REJECTED, "payload is NONE")
        if (command.kind.name !in allowedOf(slot)) {
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_COMMAND_NOT_ALLOWED_IN_SLOT, "${command.kind} is not allowed in slot ${slot.id}")
        }
        if (env.actionSeq <= slot.acceptedActionSeq) {
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ACTION_SEQ_STALE, "action_seq ${env.actionSeq} is not above accepted ${slot.acceptedActionSeq}")
        }
        if (command is BattleCommand.Attack) {
            val target = actors.firstOrNull { it.id == command.targetId }
            if (target == null || target.alive != 1 || target.side == actor.side) {
                return SceneOutcome.Failure(MmoErrorCodes.BATTLE_COMMAND_REJECTED, "target ${command.targetId} is not a living enemy")
            }
        }

        val now = nowMs()
        val ack = BattleCodec.Ack(
            battleId = battle.id, commandSlotId = slot.id, acceptedActionSeq = env.actionSeq,
            replacedPrevious = slot.acceptedActionSeq > 0, round = battle.round, phase = battle.phase,
            phaseVersion = battle.phaseVersion, slotDeadlineAtMs = slot.deadlineAtMs,
        )
        tx.run {
            repo.updateSlot(slot.copy(acceptedActionSeq = env.actionSeq, payload = env.payloadJson))
            repo.insertCommand(
                MmoBattleCommand(
                    battleId = battle.id, actorId = actor.id, actionSeq = env.actionSeq, requestId = env.requestId,
                    commandSlotId = slot.id, payload = env.payloadJson, ack = BattleCodec.encodeAck(ack).toString(),
                ),
            )
            val live = Live(battle, actors.toMutableList())
            live.emitPrivate(env.roleId, BattleCodec.commandAccepted(slot.id, env.actionSeq), now, requestId = env.requestId)
            // 全员提交即提前结算，不等截止。
            val slots = repo.listSlots(battle.id, battle.round)
            if (slots.all { it.isRequired == 0 || it.acceptedActionSeq > 0 }) resolveRound(live, now)
            commit(live)
        }
        flush(battle.id)
        return SceneOutcome.Success(ack)
    }

    // ---------------- 即时权威操作 ----------------

    suspend fun instant(userId: Long, channelId: Long, req: BattleCodec.InstantRequest): SceneOutcome<JsonObject> {
        val battle = repo.getBattle(req.battleId)
        if (battle == null || battle.phase == BattlePhase.CLOSED.name || battle.channelId != channelId) {
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_NOT_FOUND, "battle ${req.battleId} is not open on channel $channelId")
        }
        roles.findById(req.roleId)?.takeIf { it.userId == userId && it.status == 1 && it.id == battle.roleId }
            ?: return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ACTOR_NOT_CONTROLLABLE, "role ${req.roleId} is not a party of battle ${battle.id}")
        if (req.stateVersion != battle.stateVersion) {
            return SceneOutcome.Failure(MmoErrorCodes.BATTLE_STATE_VERSION_CONFLICT, "state_version ${req.stateVersion} != current ${battle.stateVersion}")
        }
        if (req.op != BattleCodec.OP_SURRENDER) return SceneOutcome.Failure(MmoErrorCodes.BATTLE_COMMAND_REJECTED, "unknown instant op '${req.op}'")
        if (battle.phase != BattlePhase.COMMAND.name) return SceneOutcome.Failure(MmoErrorCodes.BATTLE_PHASE_MISMATCH, "cannot surrender in ${battle.phase}")
        val now = nowMs()
        val closedVersion = tx.run {
            val live = Live(battle, repo.listActors(battle.id).toMutableList())
            settle(live, BattleOutcome.MONSTER_WIN, now)
            commit(live)
            live.battle.stateVersion
        }
        flush(battle.id)
        return SceneOutcome.Success(BattleCodec.encodeInstantAck(battle.id, closedVersion, BattlePhase.SETTLE.name))
    }

    /** 运营强制中止：无胜者，立即进入 SETTLE。 */
    suspend fun abort(battleId: Long, reason: String): SceneOutcome<Unit> {
        val battle = repo.getBattle(battleId)
        if (battle == null || battle.phase == BattlePhase.CLOSED.name) return SceneOutcome.Failure(MmoErrorCodes.BATTLE_NOT_FOUND, "battle $battleId is not open")
        if (battle.phase == BattlePhase.SETTLE.name) return SceneOutcome.Success(Unit)
        val now = nowMs()
        tx.run {
            val live = Live(battle, repo.listActors(battle.id).toMutableList())
            settle(live, BattleOutcome.ESCAPED, now)
            commit(live)
        }
        flush(battleId)
        log.info("mmo.battle.aborted battle_id=$battleId reason=$reason")
        return SceneOutcome.Success(Unit)
    }

    // ---------------- 调度：截止推进 + outbox 补投 ----------------

    /** 推进所有到期战斗；返回推进数。由 [BattleRoundScheduler] 周期调用。 */
    suspend fun tick(now: Long = nowMs()): Int {
        var advanced = 0
        for (due in repo.listDue(now)) {
            runCatching {
                tx.run {
                    // 事务内重读：调度器与提交路径可能并发推进同一场。
                    val fresh = repo.getBattle(due.id) ?: return@run
                    if (fresh.phase != due.phase || fresh.phaseVersion != due.phaseVersion) return@run
                    val live = Live(fresh, repo.listActors(fresh.id).toMutableList())
                    when (fresh.phase) {
                        BattlePhase.COMMAND.name -> resolveRound(live, now)
                        BattlePhase.SETTLE.name -> close(live, now)
                    }
                    commit(live)
                    advanced += 1
                }
                flush(due.id)
            }.onFailure { log.warn("mmo.battle.tick.failed battle_id=${due.id} err=${it.message}") }
        }
        // 上次投递失败的事件在这里补投。
        for (open in repo.listOpen()) runCatching { flush(open.id) }
        return advanced
    }

    // ---------------- 快照 ----------------

    suspend fun publicSnapshot(battleId: Long): SceneOutcome<BattleSnapshot> {
        val battle = repo.getBattle(battleId)
        if (battle == null || battle.phase == BattlePhase.CLOSED.name) return SceneOutcome.Failure(MmoErrorCodes.BATTLE_NOT_FOUND, "battle $battleId is not open")
        return SceneOutcome.Success(BattleSnapshot(battle, repo.listActors(battleId), recipientRoleId = 0, openSlots = emptyList()))
    }

    suspend fun privateSnapshot(userId: Long, battleId: Long, roleId: Long): SceneOutcome<BattleSnapshot> {
        val battle = repo.getBattle(battleId)
        if (battle == null || battle.phase == BattlePhase.CLOSED.name) return SceneOutcome.Failure(MmoErrorCodes.BATTLE_NOT_FOUND, "battle $battleId is not open")
        roles.findById(roleId)?.takeIf { it.userId == userId && it.status == 1 }
            ?: return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ACTOR_NOT_CONTROLLABLE, "role $roleId is not controllable by user $userId")
        val actors = repo.listActors(battleId)
        val mine = actors.filter { it.ownerRoleId == roleId }.map { it.id }.toSet()
        if (mine.isEmpty()) return SceneOutcome.Failure(MmoErrorCodes.BATTLE_ACTOR_NOT_IN_BATTLE, "role $roleId has no actor in battle $battleId")
        val open = if (battle.phase == BattlePhase.COMMAND.name) repo.listSlots(battleId, battle.round).filter { it.actorId in mine } else emptyList()
        return SceneOutcome.Success(BattleSnapshot(battle, actors, recipientRoleId = roleId, openSlots = open))
    }

    // ---------------- 内部：状态机 ----------------

    /** 一次事务内的工作集：战斗头 + 单位 + 待落库事件。序号在内存里推进，commit 时一并写回。 */
    private class Live(var battle: MmoBattle, val actors: MutableList<MmoBattleActor>) {
        val pending = mutableListOf<MmoBattleEvent>()
    }

    private fun Live.emitPublic(payload: JsonObject, now: Long, defaultApplied: Boolean = false) {
        battle = battle.copy(publicEventSeq = battle.publicEventSeq + 1, stateVersion = battle.stateVersion + 1)
        pending += MmoBattleEvent(
            battleId = battle.id, round = battle.round, visibility = BattleCodec.VISIBILITY_PUBLIC, streamSeq = battle.publicEventSeq,
            stateVersion = battle.stateVersion, defaultActionApplied = if (defaultApplied) 1 else 0, serverTimeMs = now, payload = payload.toString(),
        )
    }

    private fun Live.emitPrivate(roleId: Long, payload: JsonObject, now: Long, requestId: String = "") {
        battle = battle.copy(privateEventSeq = battle.privateEventSeq + 1, stateVersion = battle.stateVersion + 1)
        pending += MmoBattleEvent(
            battleId = battle.id, round = battle.round, visibility = BattleCodec.VISIBILITY_PRIVATE, recipientRoleId = roleId,
            streamSeq = battle.privateEventSeq, stateVersion = battle.stateVersion, requestId = requestId, serverTimeMs = now, payload = payload.toString(),
        )
    }

    /** 开一个 COMMAND 回合：签发 slot，私发 SlotsOffered。 */
    private suspend fun Live.openRoundInternal(round: Int, now: Long) {
        val deadline = now + BattleRules.ROUND_MS
        battle = battle.copy(phase = BattlePhase.COMMAND.name, round = round, phaseVersion = battle.phaseVersion + 1, deadlineAtMs = deadline)
        val allowed = buildJsonArray { BattleRules.ALLOWED_V1.forEach { add(JsonPrimitive(it.name)) } }.toString()
        val byRole = mutableMapOf<Long, MutableList<MmoBattleSlot>>()
        for (actor in actors) {
            if (actor.alive != 1 || actor.side != BattleRules.PLAYER_SIDE) continue
            val slot = repo.insertSlot(
                MmoBattleSlot(battleId = battle.id, roundNo = round, actorId = actor.id, allowedCommands = allowed, deadlineAtMs = deadline),
            )
            byRole.getOrPut(actor.ownerRoleId) { mutableListOf() } += slot
        }
        for ((roleId, slots) in byRole) emitPrivate(roleId, BattleCodec.slotsOffered(slots), now)
    }

    private suspend fun openRound(live: Live, round: Int, now: Long) = live.openRoundInternal(round, now)

    /** COMMAND → LOCKED → RESOLVE → ROUND_END → (COMMAND | SETTLE)。 */
    private suspend fun resolveRound(live: Live, now: Long) {
        val slots = repo.listSlots(live.battle.id, live.battle.round)
        val commands = slots.mapNotNull { s -> s.payload.takeIf { it.isNotEmpty() }?.let { BattleCodec.decodeCommandPayload(it) }?.let { s.actorId to it } }.toMap()
        val defaulted = slots.any { it.isRequired == 1 && it.acceptedActionSeq == 0 }
        live.emitPublic(BattleCodec.phaseChanged(BattlePhase.COMMAND, BattlePhase.LOCKED, live.battle.round, 0), now, defaultApplied = defaulted)

        val rng = SplitMix64(live.battle.rngSeed, live.battle.rngCursor)
        val resolution = BattleRules.resolveRound(live.actors.map { it.toRule() }, commands, rng)
        live.emitPublic(BattleCodec.initiativeResolved(resolution.order, BattleRules.RNG_ALGORITHM_VERSION, rng.cursor), now)
        for (event in resolution.events) when (event) {
            is RoundEvent.Damage -> live.emitPublic(BattleCodec.damageDealt(event.sourceId, listOf(event.targetId), listOf(event.amount), event.retarget), now)
            is RoundEvent.Died -> live.emitPublic(BattleCodec.actorDied(event.actorId), now)
            is RoundEvent.EscapeAttempt -> Unit
        }
        val byId = resolution.actors.associateBy { it.id }
        for (i in live.actors.indices) {
            val r = byId.getValue(live.actors[i].id)
            live.actors[i] = live.actors[i].copy(hp = r.hp, alive = if (r.alive) 1 else 0, defending = if (r.defending) 1 else 0)
        }
        live.battle = live.battle.copy(
            rngCursor = rng.cursor,
            initiativeOrder = buildJsonArray { resolution.order.forEach { add(JsonPrimitive(it)) } }.toString(),
            phaseVersion = live.battle.phaseVersion + 1,
        )
        val outcome = resolution.outcome
        if (outcome != null) {
            settle(live, outcome, now)
        } else {
            val next = live.battle.round + 1
            live.openRoundInternal(next, now)
            live.emitPublic(BattleCodec.phaseChanged(BattlePhase.ROUND_END, BattlePhase.COMMAND, next, live.battle.deadlineAtMs), now)
        }
    }

    /** → SETTLE：落结算请求（v1 停在 PENDING，§15.6），会话转 BATTLE_EXITING。 */
    private suspend fun settle(live: Live, outcome: BattleOutcome, now: Long) {
        val closeAt = now + BattleRules.EXIT_GRACE_MS
        live.battle = live.battle.copy(
            phase = BattlePhase.SETTLE.name, winnerSide = outcome.winnerSide, phaseVersion = live.battle.phaseVersion + 1, deadlineAtMs = closeAt,
        )
        live.emitPublic(BattleCodec.phaseChanged(BattlePhase.RESOLVE, BattlePhase.SETTLE, live.battle.round, closeAt), now)
        val settlementId = "bs-${live.battle.id}"
        repo.insertSettlement(MmoRewardSettlement(settlementRequestId = settlementId, battleId = live.battle.id, roleId = live.battle.roleId, winnerSide = outcome.winnerSide))
        live.emitPublic(BattleCodec.battleSettled(settlementId, outcome.winnerSide), now)
        sessions.findById(live.battle.sceneSessionId)
            ?.takeIf { it.status == 1 && it.state == SceneSessionState.IN_BATTLE }
            ?.let { sessions.updateState(it, SceneSessionState.BATTLE_EXITING) }
    }

    /** SETTLE → CLOSED：会话若还挂在本场上，放回 ACTIVE——不依赖客户端退订。 */
    private suspend fun close(live: Live, now: Long) {
        live.battle = live.battle.copy(phase = BattlePhase.CLOSED.name, phaseVersion = live.battle.phaseVersion + 1, deadlineAtMs = 0)
        live.emitPublic(BattleCodec.phaseChanged(BattlePhase.SETTLE, BattlePhase.CLOSED, live.battle.round, 0), now)
        sessions.findById(live.battle.sceneSessionId)
            ?.takeIf { it.status == 1 && it.state != SceneSessionState.ACTIVE }
            ?.let { sessions.updateState(it, SceneSessionState.ACTIVE) }
    }

    private suspend fun commit(live: Live) {
        repo.updateBattle(live.battle)
        for (actor in live.actors) repo.updateActor(actor)
        for (event in live.pending) repo.insertEvent(event)
        live.pending.clear()
    }

    /** 投递 outbox（事务外）。失败只记日志，留待下次 tick 补投。 */
    private suspend fun flush(battleId: Long) {
        val battle = repo.getBattle(battleId) ?: return
        if (battle.channelId == 0L) return
        val pending = repo.listUnpublished(battleId)
        if (pending.isEmpty()) return
        val now = nowMs()
        for ((key, events) in pending.groupBy { it.visibility to it.recipientRoleId }) {
            val (visibility, recipient) = key
            val payload = BattleCodec.encodeEventBatch(battleId, events)
            val sent = runCatching {
                if (visibility == BattleCodec.VISIBILITY_PUBLIC) {
                    rooms.broadcast(battle.channelId, payload)
                } else {
                    val userId = roles.findById(recipient)?.userId ?: error("recipient role $recipient is gone")
                    // server 要求 request_id 有足够熵(UUID v4 或 ≥16 随机字节);每次尝试一个新 id,
                    // 重投的去重由客户端按 stream_seq 完成,不靠 server。
                    rooms.sendTransfer(battle.channelId, userId, ROUTE_BATTLE_EVENT, uuidV4(), payload)
                }
            }
            sent.onSuccess { for (e in events) repo.markPublished(e, now) }
                .onFailure { log.warn("mmo.battle.publish_failed battle_id=$battleId visibility=$visibility recipient=$recipient err=${it.message}") }
        }
    }

    private fun allowedOf(slot: MmoBattleSlot): Set<String> =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(slot.allowedCommands) as kotlinx.serialization.json.JsonArray }
            .getOrNull()?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()

    private fun MmoBattleActor.toRule() = BattleActor(
        id = id, side = side, ownerRoleId = ownerRoleId, name = name, hp = hp, maxHp = maxHp, mp = mp, maxMp = maxMp,
        atk = atk, def = defense, speed = speed, alive = alive == 1, defending = defending == 1,
    )

    private fun entryOf(t: MmoBattleTransition) = BattleEntry(
        transitionId = t.id, status = t.status, battleId = t.battleId, channelId = t.channelId,
        ticket = t.ticket, ticketExp = t.ticketExp, sceneSessionId = t.sceneSessionId, reason = t.reason,
    )

    private fun nowMs(): Long = clock()

    /** RFC 4122 v4 文本形式,来自 [kotlin.random.Random] 的 16 字节。 */
    private fun uuidV4(): String {
        val b = kotlin.random.Random.nextBytes(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = b.joinToString("") { ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    companion object {
        const val TRANSITION_PENDING: String = "PENDING"
        const val TRANSITION_READY: String = "READY"
        const val TRANSITION_FAILED: String = "FAILED"

        /** PRIVATE 事件的定向 transfer route（§15.1）。 */
        const val ROUTE_BATTLE_EVENT: String = "mmorpg/battle/event"
    }
}

data class BattleEntry(
    val transitionId: Long,
    val status: String,
    val battleId: Long,
    val channelId: Long,
    val ticket: String,
    val ticketExp: Long,
    val sceneSessionId: Long,
    val reason: String,
)

/** 快照的领域形态；HTTP 层再翻成 DTO。`recipientRoleId = 0` 即 public 视角，不含 slot / 精确资源。 */
data class BattleSnapshot(
    val battle: MmoBattle,
    val actors: List<MmoBattleActor>,
    val recipientRoleId: Long,
    val openSlots: List<MmoBattleSlot>,
)
