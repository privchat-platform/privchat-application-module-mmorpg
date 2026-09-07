package logic.codec


/**
 * NPC 交互(`mmorpg/scene/interact`)的领域对象;线格式只有 FlatBuffers(MIR1/MIA1,见 [SceneFlatCodec])。
 *
 * 与移动同一原则：客户端只报"我要和谁交互"，在不在交互距离内由服务端按权威位置判。
 * 响应只是对话内容与选项——这是底座，玩法（任务、商店、进战斗）挂在 `options` 上。
 */
object SceneInteractCodec {
    data class Request(val sceneSessionId: Long, val requestId: String, val npcId: Long)
    data class Response(val npcId: Long, val name: String, val kind: String, val dialog: String, val options: List<String>)

    sealed interface DecodeError {
        data object NotAnObject : DecodeError
        data class UnsupportedVersion(val got: Int) : DecodeError
        data class MissingField(val name: String) : DecodeError
    }
    class DecodeFailure(val error: DecodeError) : Exception(error.toString())
}
