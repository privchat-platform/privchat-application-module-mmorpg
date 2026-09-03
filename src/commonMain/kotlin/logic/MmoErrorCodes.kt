package logic

/**
 * MMO 场景错误码（MMO_WORLD_SCENE_SPEC §9.1，段位 21600-21699）。
 *
 * 段位由 `privchat-protocol/registry/error_codes.toml` 分配。**不要就地新造码**：
 * registry 里已经记录过两次因此产生的真实冲突（20900 / 20920），代价是两个业务
 * 对同一个数字有不同理解，而客户端只能看到数字。
 */
object MmoErrorCodes {
    /** `SceneRef` 不存在或已关闭。 */
    const val SCENE_NOT_FOUND: Int = 21600

    /** `scene_session_id` 无效、已失效，或不属于发起者。 */
    const val SCENE_SESSION_INVALID: Int = 21601

    /** `SceneRef` 无法解析，或代际过期（分线已重建、ID 被复用）。 */
    const val SCENE_GENERATION_MISMATCH: Int = 21602

    /** 目标点越界 / 不在可行走区。 */
    const val SCENE_MOVE_TARGET_UNREACHABLE: Int = 21603

    /** `movement_seq` 不大于该 session 已受理的最大值（乱序迟到）。 */
    const val SCENE_MOVEMENT_SEQ_STALE: Int = 21605

    /** 同 `request_id` 但规范化载荷不同。 */
    const val SCENE_IDEMPOTENCY_KEY_REUSE: Int = 21606

    /** 请求者无权控制该实体（角色不属于该账号）。 */
    const val SCENE_ENTITY_NOT_CONTROLLABLE: Int = 21607

    /** 载荷超出包长预算，或结构不合法。 */
    const val SCENE_PAYLOAD_TOO_LARGE: Int = 21608

    /** `protocol_version` 超出服务端支持范围。 */
    const val SCENE_PROTOCOL_VERSION_UNSUPPORTED: Int = 21609

    /**
     * route 不被本服务端识别，或指令在当前实现中尚未提供。
     *
     * 与 [SCENE_NOT_FOUND] 分开：那是"你要的场景不在"，这是"你要的动作我不会"。
     * 混用会让客户端在收到 21600 时去重建场景，而问题其实是它发了一个本端还
     * 没实装的 route。
     */
    const val SCENE_COMMAND_INVALID: Int = 21610

    /** 交互目标（NPC）不在本场景的地图上。 */
    const val SCENE_INTERACT_TARGET_NOT_FOUND: Int = 21611

    /** 角色的权威位置离交互目标超过其交互距离。 */
    const val SCENE_INTERACT_OUT_OF_RANGE: Int = 21612

    /** 会话状态不允许该操作（战斗中不能移动、不能再发起战斗）。 */
    const val SCENE_STATE_NOT_ALLOWED: Int = 21613

    // ---- 战斗（MMO_BATTLE_PROTOCOL_SPEC §9，段位 21400-21499）----

    /** `battle_id` 不存在或已 CLOSED。 */
    const val BATTLE_NOT_FOUND: Int = 21400
    /** 当前不在 `COMMAND`。 */
    const val BATTLE_PHASE_MISMATCH: Int = 21401
    const val BATTLE_ROUND_MISMATCH: Int = 21402
    const val BATTLE_PHASE_VERSION_STALE: Int = 21403
    /** `role_id` 无权控制该 `actor_id`。 */
    const val BATTLE_ACTOR_NOT_CONTROLLABLE: Int = 21404
    /** `actor_id` 不属于该 `battle_id`。 */
    const val BATTLE_ACTOR_NOT_IN_BATTLE: Int = 21405
    /** 该 actor 当前不可行动（死亡）。 */
    const val BATTLE_ACTOR_CANNOT_ACT: Int = 21406
    /** 指令本身非法（目标非法 / 载荷缺失）。 */
    const val BATTLE_COMMAND_REJECTED: Int = 21407
    const val BATTLE_PROTOCOL_VERSION_UNSUPPORTED: Int = 21408
    /** 即时权威操作的乐观锁冲突。 */
    const val BATTLE_STATE_VERSION_CONFLICT: Int = 21409
    const val BATTLE_PAYLOAD_TOO_LARGE: Int = 21410
    /** 同 `request_id` 但 payload 不同。 */
    const val BATTLE_IDEMPOTENCY_KEY_REUSE: Int = 21411
    /** 同 `action_seq` 但 payload / `request_id` 不同。 */
    const val BATTLE_ACTION_SEQ_REUSE: Int = 21412
    const val BATTLE_SLOT_NOT_FOUND: Int = 21413
    const val BATTLE_SLOT_NOT_OWNED: Int = 21414
    const val BATTLE_COMMAND_NOT_ALLOWED_IN_SLOT: Int = 21415
    const val BATTLE_ACTION_SEQ_STALE: Int = 21416
}
