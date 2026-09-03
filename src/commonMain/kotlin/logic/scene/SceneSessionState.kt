package logic.scene

/** `mmo_scene_session.state` 的取值（MMO_BATTLE_PROTOCOL_SPEC §15.2）。 */
object SceneSessionState {
    const val ACTIVE: String = "ACTIVE"
    const val BATTLE_ENTERING: String = "BATTLE_ENTERING"
    const val IN_BATTLE: String = "IN_BATTLE"
    const val BATTLE_EXITING: String = "BATTLE_EXITING"

    /** 是否处在"战斗占用"中：此时不能移动、不能再发起战斗、不能重新 enter。 */
    fun isBattleBound(state: String): Boolean = state == BATTLE_ENTERING || state == IN_BATTLE
}
