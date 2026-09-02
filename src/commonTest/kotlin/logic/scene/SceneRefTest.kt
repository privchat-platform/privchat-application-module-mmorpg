package logic.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SceneRefTest {

    @Test
    fun roundTripsThroughItsWireForm() {
        val ref = SceneRef(SceneRef.Kind.LINE, 10023, 7)
        assertEquals("l-10023-7", ref.encode())
        assertEquals(ref, SceneRef.parse("l-10023-7"))
    }

    @Test
    fun distinguishesLineFromInstance() {
        assertEquals(SceneRef.Kind.INSTANCE, SceneRef.parse("i-88401-1")!!.kind)
        assertEquals(SceneRef.Kind.LINE, SceneRef.parse("l-88401-1")!!.kind)
    }

    @Test
    fun rejectsMalformedInputInsteadOfGuessing() {
        // 少一段：曾经的写法是 "l-10023"，如果这里补默认 generation=1，
        // 老客户端就会静默落到"第一代"场景，而它想去的那个可能早已重建。
        assertNull(SceneRef.parse("l-10023"))
        assertNull(SceneRef.parse("l-10023-7-extra"))
        assertNull(SceneRef.parse("x-10023-7"))
        assertNull(SceneRef.parse("l-abc-7"))
        assertNull(SceneRef.parse(""))
    }

    @Test
    fun rejectsNonPositiveIdAndGeneration() {
        // 0 / 负数在 URL 里是可打的，但没有对应的实体。放过去会让下游用 0 去查库。
        assertNull(SceneRef.parse("l-0-7"))
        assertNull(SceneRef.parse("l-10023-0"))
        assertNull(SceneRef.parse("l--1-7"))
    }
}
