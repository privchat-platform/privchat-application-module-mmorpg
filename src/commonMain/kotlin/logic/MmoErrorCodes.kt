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

    /** 请求者无权控制该实体（角色不属于该账号）。 */
    const val SCENE_ENTITY_NOT_CONTROLLABLE: Int = 21607

    /** 载荷超出包长预算，或结构不合法。 */
    const val SCENE_PAYLOAD_TOO_LARGE: Int = 21608

    /** `protocol_version` 超出服务端支持范围。 */
    const val SCENE_PROTOCOL_VERSION_UNSUPPORTED: Int = 21609
}
