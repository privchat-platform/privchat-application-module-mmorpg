package logic.scene

import model.MmoSceneSession

/**
 * 定点坐标（spec `scene_common.fbs`）：单位 1/1000 世界单位，原点左上，+x 右 +y 下。
 * 全程整数、**向零取整**——Kotlin / C++ / GDScript 三端必须得到同一个数。
 */
data class Vec2Fixed(val x: Int, val y: Int)

/**
 * 地图数据（边界、速度、导航版本）。
 *
 * 地图文件格式与寻路算法是 spec 的非目标（§9）；在真正的地图数据接入之前，
 * 这里给每个场景一块固定边界的平地：路径就是起点到终点的直线。接入导航网格时
 * 只换 [pathTo] 的实现，协议与调用方不动。
 */
object SceneMap {
    /** 100 x 100 世界单位。 */
    const val WIDTH: Int = 100_000
    const val HEIGHT: Int = 100_000

    /** 5 世界单位每秒。 */
    const val WALK_SPEED: Int = 5_000

    /** 导航数据版本；换地图数据时递增，客户端据此拒绝新旧混算。 */
    const val NAVIGATION_VERSION: Int = 1

    /** 新进入场景的角色出生点。 */
    val SPAWN: Vec2Fixed = Vec2Fixed(WIDTH / 2, HEIGHT / 2)

    fun contains(p: Vec2Fixed): Boolean = p.x in 0..WIDTH && p.y in 0..HEIGHT

    /** 权威路径：平地上就是直线。返回的点列**不含**起点。 */
    fun pathTo(from: Vec2Fixed, to: Vec2Fixed): List<Vec2Fixed> = if (from == to) emptyList() else listOf(to)
}

object SceneMovement {

    /**
     * 会话在 [nowMs] 时刻的权威位置：沿 start→target 以 speed 匀速，到达后停在 target。
     * 中间量用 Long 避免溢出；除法向零取整（Kotlin 整数除法即如此）。
     */
    fun positionAt(session: MmoSceneSession, nowMs: Long): Vec2Fixed {
        val start = Vec2Fixed(session.startX, session.startY)
        val target = Vec2Fixed(session.targetX, session.targetY)
        // 静止（速度 0）或原地路径：位置就是起点。速度 > 0 时 pathStartMs 一定 > 0。
        if (session.speed <= 0 || start == target) return start
        val elapsedMs = (nowMs - session.pathStartMs).coerceAtLeast(0)
        val total = distance(start, target)
        if (total == 0L) return target
        // 已走距离（毫单位）= speed(毫单位/s) * elapsed(ms) / 1000
        val travelled = session.speed.toLong() * elapsedMs / 1000
        if (travelled >= total) return target
        val dx = (target.x - start.x).toLong()
        val dy = (target.y - start.y).toLong()
        return Vec2Fixed(
            (start.x + dx * travelled / total).toInt(),
            (start.y + dy * travelled / total).toInt(),
        )
    }

    /** 到达时刻；静止或已到达返回 [nowMs]。 */
    fun arrivalMs(session: MmoSceneSession): Long {
        if (session.speed <= 0) return session.pathStartMs
        val total = distance(Vec2Fixed(session.startX, session.startY), Vec2Fixed(session.targetX, session.targetY))
        return session.pathStartMs + total * 1000 / session.speed
    }

    /** 欧氏距离，向零取整。 */
    fun distance(a: Vec2Fixed, b: Vec2Fixed): Long {
        val dx = (b.x - a.x).toLong()
        val dy = (b.y - a.y).toLong()
        return isqrt(dx * dx + dy * dy)
    }

    /** 整数平方根（向下取整），避免 Double 在三端上的舍入差异。 */
    fun isqrt(n: Long): Long {
        if (n <= 0) return 0
        var x = n
        var y = (x + 1) / 2
        while (y < x) { x = y; y = (x + n / x) / 2 }
        return x
    }
}
