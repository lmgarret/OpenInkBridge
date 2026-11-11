package org.openinkbridge.sdk

import kotlin.math.roundToLong

/** An excluded area, in the view's px; edges count as inside. */
internal data class ExcludedBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Cuts strokes where they cross excluded regions, so no committed ink lies inside one: each
 * stroke becomes the pieces drawn outside them, in order. A piece ends and starts where the stroke
 * crosses a region's edge, with pressure, tilt and time interpolated there.
 */
internal object ExcludedRegions {
    private const val EPSILON = 1e-4f

    /**
     * The pieces of [points] outside every box of [boxes]. A stroke that never enters one comes
     * back as the same list; one drawn entirely inside them comes back as none.
     */
    fun split(points: List<PenPoint>, boxes: List<ExcludedBox>): List<List<PenPoint>> {
        if (boxes.isEmpty() || points.isEmpty()) return listOf(points)
        if (points.size == 1) {
            val p = points[0]
            return if (boxes.any { it.contains(p.x, p.y) }) emptyList() else listOf(points)
        }

        val pieces = mutableListOf<List<PenPoint>>()
        var current: MutableList<PenPoint>? = null
        var cut = false

        val first = points[0]
        if (boxes.none { it.contains(first.x, first.y) }) current = mutableListOf(first) else cut = true

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val inside = insideIntervals(a, b, boxes)
            if (inside.isNotEmpty()) cut = true
            for ((start, end) in outsideIntervals(inside)) {
                if (start > EPSILON || current == null) {
                    current?.let(pieces::add)
                    current = mutableListOf(lerp(a, b, start))
                }
                if (end < 1f - EPSILON) {
                    current!!.add(lerp(a, b, end))
                    pieces.add(current!!)
                    current = null
                } else {
                    current!!.add(b)
                }
            }
        }
        current?.let(pieces::add)

        if (!cut) return listOf(points)
        // A piece that only touches an edge has no length: nothing was drawn outside there.
        return pieces.filter { piece -> piece.size >= 2 && piece.any { it.x != piece[0].x || it.y != piece[0].y } }
    }

    /** Parts of segment [a]→[b], as merged parameter intervals in [0, 1], that lie inside a box. */
    private fun insideIntervals(a: PenPoint, b: PenPoint, boxes: List<ExcludedBox>): List<Pair<Float, Float>> {
        val hits = boxes.mapNotNull { clip(a, b, it) }.sortedBy { it.first }
        if (hits.isEmpty()) return hits
        val merged = mutableListOf(hits[0])
        for (hit in hits.drop(1)) {
            val last = merged.last()
            if (hit.first <= last.second + EPSILON) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, hit.second)
            } else {
                merged.add(hit)
            }
        }
        return merged
    }

    private fun outsideIntervals(inside: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val outside = mutableListOf<Pair<Float, Float>>()
        var from = 0f
        for ((start, end) in inside) {
            if (start - from > EPSILON) outside.add(from to start)
            from = maxOf(from, end)
        }
        if (1f - from > EPSILON) outside.add(from to 1f)
        return outside
    }

    /** Liang–Barsky: the part of segment [a]→[b] inside [box], or null if it misses or only grazes it. */
    private fun clip(a: PenPoint, b: PenPoint, box: ExcludedBox): Pair<Float, Float>? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(a.x - box.left, box.right - a.x, a.y - box.top, box.bottom - a.y)
        var t0 = 0f
        var t1 = 1f
        for (i in 0 until 4) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return null
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0f) {
                    if (r > t1) return null
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return null
                    if (r < t1) t1 = r
                }
            }
        }
        // A zero-length segment inside a box is inside it whole.
        if (dx == 0f && dy == 0f) return 0f to 1f
        return if (t1 - t0 > EPSILON) t0 to t1 else null
    }

    private fun lerp(a: PenPoint, b: PenPoint, t: Float): PenPoint = when {
        t <= 0f -> a
        t >= 1f -> b
        else -> a.copy(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
            pressure = a.pressure + (b.pressure - a.pressure) * t,
            tilt = a.tilt + (b.tilt - a.tilt) * t,
            timestamp = a.timestamp + ((b.timestamp - a.timestamp) * t.toDouble()).roundToLong()
        )
    }
}
