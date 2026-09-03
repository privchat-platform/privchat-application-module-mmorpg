package logic.scene

import model.MmoSceneSession
import kotlin.test.Test
import kotlin.test.assertEquals

class SceneMovementTest {

    private fun session(start: Vec2Fixed, target: Vec2Fixed, startMs: Long, speed: Int) = MmoSceneSession(
        id = 1, roleId = 1, sceneRef = "l-1-1", channelId = 1,
        startX = start.x, startY = start.y, targetX = target.x, targetY = target.y,
        pathStartMs = startMs, speed = speed,
    )

    @Test
    fun interpolatesAlongTheLineAndStopsAtTheTarget() {
        // 5 units/s over 30 units: 6 s of travel.
        val s = session(Vec2Fixed(0, 0), Vec2Fixed(30_000, 0), startMs = 1_000, speed = 5_000)
        assertEquals(Vec2Fixed(0, 0), SceneMovement.positionAt(s, 1_000))
        assertEquals(Vec2Fixed(10_000, 0), SceneMovement.positionAt(s, 3_000))
        assertEquals(Vec2Fixed(30_000, 0), SceneMovement.positionAt(s, 7_000))
        assertEquals(Vec2Fixed(30_000, 0), SceneMovement.positionAt(s, 99_000))
        assertEquals(7_000, SceneMovement.arrivalMs(s))
    }

    @Test
    fun truncatesTowardZeroLikeEveryOtherClient() {
        // 1 unit/s diagonally for 1.5 s across (1000, 1000): distance 1414 milli-units.
        val s = session(Vec2Fixed(0, 0), Vec2Fixed(1_000, 1_000), startMs = 1_000, speed = 1_000)
        val p = SceneMovement.positionAt(s, 1_500)
        // travelled 500 of 1414: x = 1000 * 500 / 1414 = 353.6 -> 353 (truncate, not round)
        assertEquals(Vec2Fixed(353, 353), p)
    }

    @Test
    fun aStationarySessionIsWhereItStarted() {
        val s = session(Vec2Fixed(5, 5), Vec2Fixed(5, 5), startMs = 0, speed = 0)
        assertEquals(Vec2Fixed(5, 5), SceneMovement.positionAt(s, 1_000_000))
    }

    @Test
    fun integerSquareRootIsExactOnSquaresAndFloorsOtherwise() {
        assertEquals(0, SceneMovement.isqrt(0))
        assertEquals(1, SceneMovement.isqrt(1))
        assertEquals(1414, SceneMovement.isqrt(2_000_000))
        assertEquals(100_000, SceneMovement.isqrt(10_000_000_000))
    }
}
