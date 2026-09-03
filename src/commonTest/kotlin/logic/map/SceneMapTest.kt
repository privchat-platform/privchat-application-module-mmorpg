package logic.map

import logic.scene.TestMap
import logic.scene.Vec2Fixed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneMapTest {
    private val map = TestMap.sceneMap()

    @Test
    fun straightLineWhenNothingIsInTheWay() {
        assertEquals(listOf(Vec2Fixed(10_000, 10_000)), map.pathTo(Vec2Fixed(50_000, 50_000), Vec2Fixed(10_000, 10_000)))
        assertEquals(emptyList(), map.pathTo(Vec2Fixed(5, 5), Vec2Fixed(5, 5)))
    }

    @Test
    fun detoursAroundTheObstacleAndNeverCrossesIt() {
        val from = Vec2Fixed(50_000, 50_000)
        val path = map.pathTo(from, Vec2Fixed(90_000, 50_000))!!
        assertTrue(path.size >= 2, "$path")
        var prev = from
        for (p in path) {
            assertTrue(map.lineOfSight(prev, p), "segment $prev -> $p crosses the obstacle")
            prev = p
        }
    }

    @Test
    fun blockedOrOutsideTargetsAreNull() {
        assertNull(map.pathTo(Vec2Fixed(50_000, 50_000), Vec2Fixed(70_000, 52_000)))
        assertNull(map.pathTo(Vec2Fixed(50_000, 50_000), Vec2Fixed(-1, 0)))
        assertNull(map.pathTo(Vec2Fixed(50_000, 50_000), Vec2Fixed(100_000, 0)))
    }
}
