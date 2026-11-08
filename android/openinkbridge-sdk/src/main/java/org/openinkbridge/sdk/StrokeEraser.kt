package org.openinkbridge.sdk

import kotlin.math.floor

internal val StylusTool.isEraser: Boolean
    get() = this == StylusTool.ERASER_POINT || this == StylusTool.ERASER_STROKE

/** What an erase changed: the strokes it removed and the pieces of them it left behind. */
data class StrokeErase(
    val removed: List<DrawingStroke>,
    val added: List<DrawingStroke>
)

/**
 * Cuts strokes along an eraser path: every point within the eraser's radius of the path goes, and a
 * stroke it crosses is split in two. Kept free of Android types so it runs on the JVM.
 */
internal object StrokeEraser {
    /**
     * Returns [strokes] with what an eraser of [radiusPx] along [path] (x, y pairs, in the strokes'
     * coordinates) went over cut out, or removed entirely when [wholeStrokes]; null when it touched
     * none. Strokes are drawn [density] × their width wide.
     */
    fun erase(
        strokes: List<DrawingStroke>,
        path: List<FloatArray>,
        radiusPx: Float,
        density: Float,
        wholeStrokes: Boolean = false
    ): List<DrawingStroke>? {
        if (path.isEmpty() || strokes.isEmpty()) return null
        // A stroke's ink spreads around its points, so each piece ends a little outside the circle
        // and its ink stops at the edge. Half the drawn width overshoots: the pen is thinner at
        // ordinary pressure.
        fun cutRadius(stroke: DrawingStroke) = radiusPx + stroke.width * density / 8f
        val reach = strokes.maxOf(::cutRadius)
        val eraser = PathIndex(path, reach)

        var changed = false
        val result = ArrayList<DrawingStroke>(strokes.size)
        for (stroke in strokes) {
            val r = cutRadius(stroke)
            val points = stroke.points
            if (points.isEmpty() || !eraser.mayReach(points, r)) {
                result += stroke
                continue
            }
            val inside = BooleanArray(points.size) { eraser.covers(points[it].x, points[it].y, r) }
            if (inside.none { it }) {
                result += stroke
                continue
            }
            changed = true
            if (wholeStrokes) continue
            var run = ArrayList<PenPoint>()
            var runStart = 0
            for (i in points.indices) {
                if (!inside[i]) {
                    if (run.isEmpty()) runStart = i
                    run.add(points[i])
                }
                if (inside[i] || i == points.lastIndex) {
                    if (run.isNotEmpty()) {
                        // Extend each cut end to where the stroke crosses the eraser's edge.
                        if (runStart > 0) run.add(0, edgePoint(run.first(), points[runStart - 1]) { x, y -> eraser.covers(x, y, r) })
                        if (inside[i]) run.add(edgePoint(run.last(), points[i]) { x, y -> eraser.covers(x, y, r) })
                    }
                    if (run.size >= 2) result += stroke.copy(points = run)
                    run = ArrayList()
                }
            }
        }
        return if (changed) result else null
    }

    /** The point between [kept] (outside the eraser) and [erased] (inside) where the eraser's edge is. */
    private fun edgePoint(kept: PenPoint, erased: PenPoint, isErased: (Float, Float) -> Boolean): PenPoint {
        var lo = 0f
        var hi = 1f
        repeat(8) {
            val mid = (lo + hi) / 2
            if (isErased(kept.x + (erased.x - kept.x) * mid, kept.y + (erased.y - kept.y) * mid)) hi = mid else lo = mid
        }
        return kept.copy(
            x = kept.x + (erased.x - kept.x) * lo,
            y = kept.y + (erased.y - kept.y) * lo,
            pressure = kept.pressure + (erased.pressure - kept.pressure) * lo,
            timestamp = kept.timestamp + ((erased.timestamp - kept.timestamp) * lo).toLong()
        )
    }

    /** The path's segments bucketed by the grid cells their [reach]-padded bounds cover. */
    private class PathIndex(private val path: List<FloatArray>, reach: Float) {
        private val cell = maxOf(reach * 2, 1f)
        private val buckets = HashMap<Long, MutableList<Int>>()
        private val minX = path.minOf { it[0] } - reach
        private val maxX = path.maxOf { it[0] } + reach
        private val minY = path.minOf { it[1] } - reach
        private val maxY = path.maxOf { it[1] } + reach

        init {
            for (i in path.indices) {
                val a = path[i]
                val b = path[minOf(i + 1, path.lastIndex)]
                for (cx in cellOf(minOf(a[0], b[0]) - reach)..cellOf(maxOf(a[0], b[0]) + reach)) {
                    for (cy in cellOf(minOf(a[1], b[1]) - reach)..cellOf(maxOf(a[1], b[1]) + reach)) {
                        buckets.getOrPut(key(cx, cy)) { ArrayList() }.add(i)
                    }
                }
            }
        }

        fun mayReach(points: List<PenPoint>, r: Float): Boolean {
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (p in points) {
                if (p.x < left) left = p.x
                if (p.x > right) right = p.x
                if (p.y < top) top = p.y
                if (p.y > bottom) bottom = p.y
            }
            return right + r >= minX && left - r <= maxX && bottom + r >= minY && top - r <= maxY
        }

        fun covers(x: Float, y: Float, r: Float): Boolean {
            val segments = buckets[key(cellOf(x), cellOf(y))] ?: return false
            val r2 = r * r
            for (i in segments) {
                val a = path[i]
                val b = path[minOf(i + 1, path.lastIndex)]
                val dx = b[0] - a[0]
                val dy = b[1] - a[1]
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0f) 0f else (((x - a[0]) * dx + (y - a[1]) * dy) / len2).coerceIn(0f, 1f)
                val ex = x - a[0] - t * dx
                val ey = y - a[1] - t * dy
                if (ex * ex + ey * ey <= r2) return true
            }
            return false
        }

        private fun cellOf(v: Float) = floor(v / cell).toInt()

        private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
    }
}
