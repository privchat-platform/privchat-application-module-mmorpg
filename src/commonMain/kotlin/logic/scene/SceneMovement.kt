package logic.scene

import model.MmoSceneSession

/**
 * 定点坐标（spec `scene_common.fbs`）：单位 1/1000 世界单位，原点左上，+x 右 +y 下。
 * 全程整数、**向零取整**——Kotlin / C++ / GDScript 三端必须得到同一个数。
 */
data class Vec2Fixed(val x: Int, val y: Int)

object SceneMovement {

    /**
     * 会话在 [nowMs] 时刻的权威位置：从 start 沿路径点列以 speed 匀速逐段走，
     * 走完停在最后一点。中间量用 Long 避免溢出；除法向零取整。
     * 客户端（GDScript）是同一套算法，所以两端算出同一个点。
     */
    fun positionAt(session: MmoSceneSession, nowMs: Long): Vec2Fixed =
        positionOnPath(Vec2Fixed(session.startX, session.startY), decodePath(session.pathPoints), session.pathStartMs, session.speed, nowMs)

    fun positionOnPath(start: Vec2Fixed, points: List<Vec2Fixed>, startMs: Long, speed: Int, nowMs: Long): Vec2Fixed {
        if (speed <= 0 || points.isEmpty()) return start
        var travelled = speed.toLong() * (nowMs - startMs).coerceAtLeast(0) / 1000
        var from = start
        for (to in points) {
            val seg = distance(from, to)
            if (travelled < seg) {
                val dx = (to.x - from.x).toLong()
                val dy = (to.y - from.y).toLong()
                return Vec2Fixed((from.x + dx * travelled / seg).toInt(), (from.y + dy * travelled / seg).toInt())
            }
            travelled -= seg
            from = to
        }
        return from
    }

    /** 到达时刻；静止返回 pathStartMs。 */
    fun arrivalMs(session: MmoSceneSession): Long {
        if (session.speed <= 0) return session.pathStartMs
        var from = Vec2Fixed(session.startX, session.startY)
        var total = 0L
        for (to in decodePath(session.pathPoints)) { total += distance(from, to); from = to }
        return session.pathStartMs + total * 1000 / session.speed
    }

    fun encodePath(points: List<Vec2Fixed>): String =
        points.joinToString(",", "[", "]") { """{"x":${it.x},"y":${it.y}}""" }

    fun decodePath(json: String): List<Vec2Fixed> {
        if (json.isBlank() || json == "[]") return emptyList()
        return PATH_POINT.findAll(json).map { Vec2Fixed(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.toList()
    }

    private val PATH_POINT = Regex(""""x":(-?\d+),"y":(-?\d+)""")

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
