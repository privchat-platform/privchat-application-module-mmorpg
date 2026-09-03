package logic.map

import logic.scene.Vec2Fixed
import model.MmoMap
import model.MmoNpc

/**
 * 一张地图的运行时形态：格子网格上的寻路与可行走判定。
 *
 * 只认数据，不认玩法。坐标全程毫单位定点、向零取整（spec `scene_common.fbs`）。
 */
class SceneMap(val data: MmoMap, val npcs: List<MmoNpc>) {
    val id: Long get() = data.id
    val widthUnits: Int = data.widthCells * data.cellSize
    val heightUnits: Int = data.heightCells * data.cellSize
    val spawn: Vec2Fixed = Vec2Fixed(data.spawnX, data.spawnY)

    private val blocked = BooleanArray(data.widthCells * data.heightCells) { i -> data.grid.getOrNull(i) == '#' }

    init {
        require(data.grid.length == data.widthCells * data.heightCells) {
            "map ${data.id} grid has ${data.grid.length} cells, expected ${data.widthCells * data.heightCells}"
        }
    }

    fun contains(p: Vec2Fixed): Boolean = p.x in 0 until widthUnits && p.y in 0 until heightUnits

    fun isWalkable(p: Vec2Fixed): Boolean = contains(p) && !blockedAt(cellOf(p))

    /**
     * 权威路径：格子 A*（8 邻域，禁止斜穿阻挡角）+ 直线可见性平滑。
     * 返回的点列**不含起点**；目标不可达或不可走返回 `null`。
     */
    fun pathTo(from: Vec2Fixed, to: Vec2Fixed): List<Vec2Fixed>? {
        if (!isWalkable(to)) return null
        if (from == to) return emptyList()
        val start = cellOf(from)
        val goal = cellOf(to)
        if (start == goal || lineOfSight(from, to)) return listOf(to)
        val cells = astar(start, goal) ?: return null
        // 格子中心点列 → 以真实起终点收尾 → 视线平滑
        val raw = ArrayList<Vec2Fixed>(cells.size + 1)
        raw += from
        for (c in cells.drop(1).dropLast(1)) raw += centerOf(c)
        raw += to
        return smooth(raw).drop(1)
    }

    fun npc(id: Long): MmoNpc? = npcs.firstOrNull { it.id == id && it.status == 1 }

    // ---- 网格 ----

    data class Cell(val cx: Int, val cy: Int)

    fun cellOf(p: Vec2Fixed): Cell = Cell(p.x / data.cellSize, p.y / data.cellSize)

    fun centerOf(c: Cell): Vec2Fixed = Vec2Fixed(c.cx * data.cellSize + data.cellSize / 2, c.cy * data.cellSize + data.cellSize / 2)

    private fun inGrid(c: Cell) = c.cx in 0 until data.widthCells && c.cy in 0 until data.heightCells

    private fun blockedAt(c: Cell): Boolean = !inGrid(c) || blocked[c.cy * data.widthCells + c.cx]

    private fun astar(start: Cell, goal: Cell): List<Cell>? {
        val w = data.widthCells
        fun key(c: Cell) = c.cy * w + c.cx
        val gScore = HashMap<Int, Int>().apply { put(key(start), 0) }
        val cameFrom = HashMap<Int, Cell>()
        val open = java_less_priority_queue()
        open.add(Node(start, heuristic(start, goal)))
        val closed = HashSet<Int>()
        var expanded = 0
        while (open.isNotEmpty()) {
            val current = open.poll().cell
            val ck = key(current)
            if (!closed.add(ck)) continue
            if (current == goal) return reconstruct(cameFrom, current, ::key)
            if (++expanded > MAX_EXPANSIONS) return null
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val next = Cell(current.cx + dx, current.cy + dy)
                if (blockedAt(next)) continue
                // 斜走不许擦着阻挡角过：两边任一格阻挡就不能斜穿。
                if (dx != 0 && dy != 0 && (blockedAt(Cell(current.cx + dx, current.cy)) || blockedAt(Cell(current.cx, current.cy + dy)))) continue
                val step = if (dx != 0 && dy != 0) 14 else 10
                val tentative = gScore.getValue(ck) + step
                val nk = key(next)
                if (tentative < (gScore[nk] ?: Int.MAX_VALUE)) {
                    gScore[nk] = tentative
                    cameFrom[nk] = current
                    open.add(Node(next, tentative + heuristic(next, goal)))
                }
            }
        }
        return null
    }

    private fun heuristic(a: Cell, b: Cell): Int {
        val dx = kotlin.math.abs(a.cx - b.cx)
        val dy = kotlin.math.abs(a.cy - b.cy)
        return 10 * (dx + dy) - 6 * minOf(dx, dy)   // octile, integer
    }

    private fun reconstruct(cameFrom: Map<Int, Cell>, end: Cell, key: (Cell) -> Int): List<Cell> {
        val out = ArrayList<Cell>()
        var c: Cell? = end
        while (c != null) { out += c; c = cameFrom[key(c)] }
        return out.asReversed()
    }

    /** 相邻点之间能直视就删掉中间点。 */
    private fun smooth(points: List<Vec2Fixed>): List<Vec2Fixed> {
        if (points.size <= 2) return points
        val out = ArrayList<Vec2Fixed>()
        out += points.first()
        var anchor = 0
        var i = 1
        while (i < points.size - 1) {
            if (!lineOfSight(points[anchor], points[i + 1])) { out += points[i]; anchor = i }
            i++
        }
        out += points.last()
        return out
    }

    /** 整数 Bresenham 走过的每一格都可走，则两点直线可见。 */
    fun lineOfSight(a: Vec2Fixed, b: Vec2Fixed): Boolean {
        var (x0, y0) = cellOf(a).let { it.cx to it.cy }
        val (x1, y1) = cellOf(b).let { it.cx to it.cy }
        val dx = kotlin.math.abs(x1 - x0); val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0); val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (blockedAt(Cell(x0, y0))) return false
            if (x0 == x1 && y0 == y1) return true
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }

    private class Node(val cell: Cell, val f: Int)

    /** 极简二叉堆，避免依赖 JVM 的 PriorityQueue（K/N 没有）。 */
    private class java_less_priority_queue {
        private val heap = ArrayList<Node>()
        fun isNotEmpty() = heap.isNotEmpty()
        fun add(n: Node) { heap += n; var i = heap.size - 1; while (i > 0) { val p = (i - 1) / 2; if (heap[p].f <= heap[i].f) break; heap[p] = heap[i].also { heap[i] = heap[p] }; i = p } }
        fun poll(): Node { val top = heap[0]; val last = heap.removeAt(heap.size - 1); if (heap.isNotEmpty()) { heap[0] = last; var i = 0; while (true) { val l = 2 * i + 1; val r = l + 1; var m = i; if (l < heap.size && heap[l].f < heap[m].f) m = l; if (r < heap.size && heap[r].f < heap[m].f) m = r; if (m == i) break; heap[m] = heap[i].also { heap[i] = heap[m] }; i = m } }; return top }
    }

    companion object {
        /** 5 世界单位每秒；速度属玩法，底座先给一个常量。 */
        const val WALK_SPEED: Int = 5_000
        const val NAVIGATION_VERSION: Int = 1
        private const val MAX_EXPANSIONS = 20_000
    }
}
