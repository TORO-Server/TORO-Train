package net.torosaba.train.track

import org.bukkit.World

/**
 * 列車が通ってきた区間の並び (最後尾側から先頭側の順)。
 * 先頭の位置は最後の区間の headOffset にあり、車両の位置は先頭からの距離で引く。
 * 通ってきた道をそのまま覚えるので、分岐の形が後から変わっても後ろの車両は同じ道をたどる。
 */
class TrackPath(val world: World) {
    val segments = ArrayList<DirectedSegment>()

    /** 最後の区間の中での、先頭の位置 (区間の始点からの道のり) */
    var headOffset = 0.0

    val head: DirectedSegment get() = segments.last()

    /** 先頭から最後尾側の端までの道のり */
    fun covered(): Double {
        var total = headOffset
        for (i in 0 until segments.size - 1) total += segments[i].length
        return total
    }

    /** 先頭から back だけ後ろの位置。覚えている道より後ろなら、いちばん後ろの端 */
    fun locate(back: Double): TrackPos {
        var remaining = back
        var i = segments.lastIndex
        var offset = headOffset
        while (true) {
            if (remaining <= offset) return TrackPos(segments[i], offset - remaining)
            remaining -= offset
            i--
            if (i < 0) return TrackPos(segments[0], 0.0)
            offset = segments[i].length
        }
    }

    // locate と同じ位置を、区間の番号と区間の中の道のりで返す
    private fun locateIndex(back: Double): Pair<Int, Double> {
        var remaining = back
        var i = segments.lastIndex
        var offset = headOffset
        while (true) {
            if (remaining <= offset) return i to offset - remaining
            remaining -= offset
            i--
            if (i < 0) return 0 to 0.0
            offset = segments[i].length
        }
    }

    /**
     * 先頭を dist だけ進める。進めた道のりと、止まった理由を返す。
     * 線路が途切れていたら途切れたところで止まる。
     */
    fun advance(dist: Double, next: (DirectedSegment) -> Next): Pair<Double, Next?> {
        var left = dist
        var moved = 0.0
        var steps = 0
        while (left > 1e-9 && steps++ < 4096) {
            val seg = head
            val room = seg.length - headOffset
            if (left <= room) {
                headOffset += left
                moved += left
                break
            }
            headOffset = seg.length
            moved += room
            left -= room
            when (val n = next(seg)) {
                is Next.Found -> {
                    segments += n.segment
                    headOffset = 0.0
                }
                else -> return moved to n
            }
        }
        return moved to null
    }

    /** 先頭を dist だけ後ろに戻す (覚えている道の中だけ) */
    fun retreat(dist: Double) {
        var r = dist
        while (r > headOffset && segments.size > 1) {
            r -= headOffset
            segments.removeAt(segments.lastIndex)
            headOffset = head.length
        }
        headOffset = maxOf(0.0, headOffset - r)
    }

    /** 列車の向きを反対にする。trainLength は先頭から最後尾までの長さで、最後尾が新しい先頭になる */
    fun reverse(trainLength: Double) {
        val (index, offset) = locateIndex(trainLength)
        val newHeadOffset = segments[index].length - offset
        val flipped = segments.subList(index, segments.size).asReversed().map { it.flip() }
        segments.clear()
        segments.addAll(flipped)
        headOffset = newHeadOffset
    }

    /** 最後尾から keep より後ろの、もう要らない区間を捨てる */
    fun trim(keep: Double) {
        while (segments.size > 1 && covered() - segments[0].length >= keep) segments.removeAt(0)
    }

    /** 先頭の区間を、線路の今の形で作り直す (分岐器の形を変えたとき)。入ってきた端が同じなら置き換える */
    fun refreshHead(graph: TrackGraph): Boolean {
        val old = head
        val rail = old.segment as? RailSegment ?: return false
        val fresh = graph.railAt(world, rail.x, rail.y, rail.z) ?: return false
        if (fresh == rail) return true
        val entry = old.start()
        val tmp = org.joml.Vector3d()
        val directed = when {
            fresh.end(0, tmp).distanceSquared(entry) < 1e-4 -> DirectedSegment(fresh, false)
            fresh.end(1, tmp).distanceSquared(entry) < 1e-4 -> DirectedSegment(fresh, true)
            else -> return false
        }
        segments[segments.lastIndex] = directed
        headOffset = headOffset.coerceAtMost(directed.length)
        return true
    }

    fun copy(): TrackPath = TrackPath(world).also {
        it.segments.addAll(segments)
        it.headOffset = headOffset
    }

    /**
     * 先頭から前に向かって線路をたどる (動かさない)。
     * visit には、区間と、先頭からその区間の始点までの道のり (今いる区間は負になる) を渡し、false を返すとやめる。
     */
    fun scanAhead(maxDist: Double, next: (DirectedSegment) -> Next, visit: (DirectedSegment, Double) -> Boolean): Next? {
        var seg = head
        var start = -headOffset
        if (!visit(seg, start)) return null
        var steps = 0
        while (start + seg.length < maxDist && steps++ < 4096) {
            start += seg.length
            seg = when (val n = next(seg)) {
                is Next.Found -> n.segment
                else -> return n
            }
            if (!visit(seg, start)) return null
        }
        return null
    }
}
