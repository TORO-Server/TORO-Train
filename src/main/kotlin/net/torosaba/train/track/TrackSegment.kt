package net.torosaba.train.track

import net.torosaba.train.util.BlockKey
import org.bukkit.World
import org.joml.Vector3d

/**
 * 線路の 1 区間。始点 (s = 0) から終点 (s = length) までの曲線で、s は始点からの道のり (ブロック)。
 * バニラのレール 1 ブロックと、自由に置ける線路 (ノードの間の曲線) がある。
 */
interface TrackSegment {
    val world: World
    val length: Double

    /** 保存に使う識別子。同じ区間なら同じ文字列になる */
    val id: String

    fun point(s: Double, out: Vector3d = Vector3d()): Vector3d

    /** 進行方向 (始点から終点に向かう向き) の単位ベクトル */
    fun tangent(s: Double, out: Vector3d = Vector3d()): Vector3d

    /** 車体の傾き (度)。カントのある線路で使う */
    fun roll(s: Double): Double = 0.0

    /** 区間の [from, to] を列車が占めているときに、看板を調べるブロック */
    fun triggerBlocks(from: Double, to: Double, out: MutableCollection<BlockKey>)

    /** 区間の中にある、行き先の名前 (自由な線路のノードの名前) */
    fun names(): Collection<String> = emptyList()
}

/** 向きを持った区間。reversed なら終点から始点に向かって進む */
class DirectedSegment(val segment: TrackSegment, val reversed: Boolean) {
    val length: Double get() = segment.length

    private fun map(d: Double) = if (reversed) segment.length - d else d

    fun point(d: Double, out: Vector3d = Vector3d()): Vector3d = segment.point(map(d), out)

    fun tangent(d: Double, out: Vector3d = Vector3d()): Vector3d =
        segment.tangent(map(d), out).also { if (reversed) it.negate() }

    fun roll(d: Double): Double = segment.roll(map(d)).let { if (reversed) -it else it }

    fun start(out: Vector3d = Vector3d()) = point(0.0, out)
    fun end(out: Vector3d = Vector3d()) = point(length, out)

    fun triggerBlocks(from: Double, to: Double, out: MutableCollection<BlockKey>) {
        val a = map(from)
        val b = map(to)
        segment.triggerBlocks(minOf(a, b), maxOf(a, b), out)
    }

    fun flip() = DirectedSegment(segment, !reversed)

    override fun equals(other: Any?) = other is DirectedSegment && other.segment == segment && other.reversed == reversed
    override fun hashCode() = segment.hashCode() * 31 + reversed.hashCode()
    override fun toString() = "${segment.id}${if (reversed) "<" else ">"}"
}

/** 区間の中の位置 */
class TrackPos(val segment: DirectedSegment, val offset: Double) {
    fun point(out: Vector3d = Vector3d()) = segment.point(offset, out)
    fun tangent(out: Vector3d = Vector3d()) = segment.tangent(offset, out)
    fun roll() = segment.roll(offset)
}
