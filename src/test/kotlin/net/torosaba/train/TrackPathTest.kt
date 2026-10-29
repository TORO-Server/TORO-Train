package net.torosaba.train

import net.torosaba.train.track.DirectedSegment
import net.torosaba.train.track.Next
import net.torosaba.train.track.TrackPath
import net.torosaba.train.track.TrackSegment
import net.torosaba.train.util.BlockKey
import net.torosaba.train.util.Text
import org.bukkit.World
import org.joml.Vector3d
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackPathTest {
    private val world = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(World::class.java)) { _, _, _ -> null } as World

    /** x 軸に沿った長さ 1 の直線の区間 (index 番目は x = index から index + 1) */
    private inner class Line(val index: Int) : TrackSegment {
        override val world: World get() = this@TrackPathTest.world
        override val length = 1.0
        override val id = "line$index"
        override fun point(s: Double, out: Vector3d): Vector3d = out.set(index + s, 0.0, 0.0)
        override fun tangent(s: Double, out: Vector3d): Vector3d = out.set(1.0, 0.0, 0.0)
        override fun triggerBlocks(from: Double, to: Double, out: MutableCollection<BlockKey>) {}
    }

    private val lines = (0 until 50).map { Line(it) }

    // x の正の向きに進むなら次の区間、負の向きなら前の区間
    private fun next(seg: DirectedSegment): Next {
        val i = (seg.segment as Line).index + if (seg.reversed) -1 else 1
        return lines.getOrNull(i)?.let { Next.Found(DirectedSegment(it, seg.reversed)) } ?: Next.End
    }

    private fun path(at: Int, offset: Double): TrackPath = TrackPath(world).apply {
        segments += DirectedSegment(lines[at], false)
        headOffset = offset
    }

    @Test
    fun advanceAndLocate() {
        val p = path(10, 0.5)
        val (moved, stop) = p.advance(3.25, ::next)
        assertEquals(3.25, moved, 1e-9)
        assertEquals(null, stop)
        assertEquals(13.75, p.locate(0.0).point().x, 1e-9)
        assertEquals(11.25, p.locate(2.5).point().x, 1e-9)
        // 覚えている道より後ろは、いちばん後ろの端
        assertEquals(10.0, p.locate(100.0).point().x, 1e-9)
    }

    @Test
    fun stopsAtEndOfTrack() {
        val p = path(48, 0.0)
        val (moved, stop) = p.advance(5.0, ::next)
        assertEquals(2.0, moved, 1e-9)
        assertEquals(Next.End, stop)
    }

    @Test
    fun reverseSwapsHeadAndTail() {
        val p = path(10, 0.0)
        p.advance(5.0, ::next)
        // 先頭は x = 15、長さ 3 の列車の最後尾は x = 12
        p.reverse(3.0)
        assertEquals(12.0, p.locate(0.0).point().x, 1e-9)
        assertEquals(15.0, p.locate(3.0).point().x, 1e-9)
        assertEquals(-1.0, p.locate(0.0).tangent().x, 1e-9)
        // 反対向きに進める
        p.advance(2.0, ::next)
        assertEquals(10.0, p.locate(0.0).point().x, 1e-9)
    }

    @Test
    fun retreatAndTrim() {
        val p = path(0, 0.0)
        p.advance(20.0, ::next)
        p.retreat(4.5)
        assertEquals(15.5, p.locate(0.0).point().x, 1e-9)
        p.trim(3.0)
        assertEquals(15.5, p.locate(0.0).point().x, 1e-9)
        assert(p.covered() >= 3.0 && p.covered() < 4.0)
    }

    @Test
    fun speedUnits() {
        assertEquals(1.0, Text.parseSpeed("72km")!!, 1e-9)
        assertEquals(0.5, Text.parseSpeed("10m/s")!!, 1e-9)
        assertEquals(0.4, Text.parseSpeed("0.4")!!, 1e-9)
        assertEquals(200, Text.parseTicks("10s"))
        assertEquals(2400, Text.parseTicks("2m"))
    }
}
