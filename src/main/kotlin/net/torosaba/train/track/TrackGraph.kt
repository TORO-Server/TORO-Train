package net.torosaba.train.track

import net.torosaba.train.util.BlockKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.floor

/** 分岐でどの区間に進むかを決める */
fun interface Chooser {
    /** options は 2 つ以上。null を返すと既定の選び方になる */
    fun choose(options: List<DirectedSegment>): DirectedSegment?
}

/** 区間の先を調べた結果 */
sealed interface Next {
    class Found(val segment: DirectedSegment) : Next
    /** 線路が途切れている */
    data object End : Next
    /** 先のチャンクが読み込まれていない */
    data object Unloaded : Next
}

/**
 * 線路のつながりを調べる。区間の端と同じ座標に端を持つ区間を、レールと自由な線路の両方から探す。
 */
class TrackGraph(val network: TrackNetwork) {

    /** 区間の終点から先に進める区間。チャンクが読み込まれていなければ null */
    fun candidates(from: DirectedSegment, allowLoad: Boolean = false): List<DirectedSegment>? =
        collect(from, allowLoad, null)

    /**
     * 経路探索用: 分岐器 (switchable が true のレール) は、今の形に関係なく取れるすべての形を候補にする。
     * 自由な線路の分岐も、切り替えの状態に関係なくすべて候補にする。
     */
    fun routeCandidates(from: DirectedSegment, switchable: (BlockKey) -> Boolean): List<DirectedSegment> =
        collect(from, false, switchable) ?: emptyList()

    private fun collect(from: DirectedSegment, allowLoad: Boolean, switchable: ((BlockKey) -> Boolean)?): List<DirectedSegment>? {
        val world = from.segment.world
        val p = from.end()
        val dir = from.tangent(from.length)
        val result = ArrayList<DirectedSegment>(2)

        // 自由な線路のノード
        for (node in network.nodesAt(world.uid, p)) {
            for (otherId in node.links) {
                val other = network.nodes[otherId] ?: continue
                val seg = network.segment(node, other)
                if (seg == from.segment) continue
                val ds = DirectedSegment(seg, seg.a.id != node.id)
                if (ds.tangent(0.0).dot(dir) > 0.1) result += ds
            }
        }

        // バニラのレール。進む向きの隣のブロックと、その 1 つ下 (下り坂) を調べる
        val bx = floor(p.x + dir.x * 0.5).toInt()
        val bz = floor(p.z + dir.z * 0.5).toInt()
        val by = floor(p.y + 1e-6).toInt()
        if (!world.isChunkLoaded(bx shr 4, bz shr 4)) {
            if (!allowLoad) return if (result.isEmpty()) null else result
            world.getChunkAt(bx shr 4, bz shr 4)
        }
        for (y in intArrayOf(by, by - 1)) {
            val block = world.getBlockAt(bx, y, bz)
            val rail = RailSegment.of(block) ?: continue
            if (rail == from.segment) continue
            if (switchable != null && switchable(rail.key)) {
                // 分岐器: 入ってくる辺を端に持つ形をすべて候補にする
                val face = entryFace(rail, p) ?: continue
                for (shape in RailSegment.shapesFrom(block, face)) {
                    val alt = RailSegment.of(block, shape) ?: continue
                    directed(alt, p)?.let { result += it }
                }
                continue
            }
            directed(rail, p)?.let { result += it }
        }
        return result
    }

    // p を端に持つなら、p から進む向きの区間にする
    private fun directed(rail: RailSegment, p: Vector3d): DirectedSegment? {
        val tmp = Vector3d()
        if (rail.end(0, tmp).distanceSquared(p) < 1e-4) return DirectedSegment(rail, false)
        if (rail.end(1, tmp).distanceSquared(p) < 1e-4) return DirectedSegment(rail, true)
        return null
    }

    // 座標 p がレールのブロックのどの辺にあるか
    private fun entryFace(rail: RailSegment, p: Vector3d): BlockFace? {
        val cx = rail.x + 0.5
        val cz = rail.z + 0.5
        return when {
            abs(p.x - (cx - 0.5)) < 1e-3 && abs(p.z - cz) < 1e-3 -> BlockFace.WEST
            abs(p.x - (cx + 0.5)) < 1e-3 && abs(p.z - cz) < 1e-3 -> BlockFace.EAST
            abs(p.z - (cz - 0.5)) < 1e-3 && abs(p.x - cx) < 1e-3 -> BlockFace.NORTH
            abs(p.z - (cz + 0.5)) < 1e-3 && abs(p.x - cx) < 1e-3 -> BlockFace.SOUTH
            else -> null
        }
    }

    /** 区間の先に進む。分岐では chooser、なければ自由な線路の切り替えの状態、なければいちばんまっすぐな区間を選ぶ */
    fun next(from: DirectedSegment, chooser: Chooser? = null, allowLoad: Boolean = false): Next {
        val options = candidates(from, allowLoad) ?: return Next.Unloaded
        if (options.isEmpty()) return Next.End
        if (options.size == 1) return Next.Found(options[0])
        chooser?.choose(options)?.let { return Next.Found(it) }
        return Next.Found(defaultChoice(from, options))
    }

    fun defaultChoice(from: DirectedSegment, options: List<DirectedSegment>): DirectedSegment {
        // 自由な線路のノードで切り替えが決まっていれば、それに従う
        val node = network.nodesAt(from.segment.world.uid, from.end()).firstOrNull()
        if (node != null) {
            // 来た区間の始点のノード。レールから来たときは 0
            val fromNode = (from.segment as? CustomSegment)?.let { if (from.reversed) it.b else it.a }
            val target = node.switches[fromNode?.id ?: 0]
            if (target != null) {
                options.firstOrNull { (it.segment as? CustomSegment)?.let { s -> s.a.id == target || s.b.id == target } == true }
                    ?.let { return it }
            }
        }
        val dir = from.tangent(from.length)
        return options.maxBy { it.tangent(0.0).dot(dir) }
    }

    /** 保存した識別子から区間を作り直す。レールの形が変わっていたら、今の形の区間を返す */
    fun segmentById(world: World, id: String): TrackSegment? = when {
        id.startsWith("r:") -> BlockKey.parse(world.uid, id.removePrefix("r:"))?.let { k ->
            world.getChunkAt(k.x shr 4, k.z shr 4)
            RailSegment.of(world.getBlockAt(k.x, k.y, k.z))
        }
        id.startsWith("c:") -> network.segmentById(id)
        else -> null
    }

    /** ブロックにあるレールの区間 */
    fun railAt(world: World, x: Int, y: Int, z: Int): RailSegment? = RailSegment.at(world, x, y, z)

    /** 点に最も近い線路の位置。レールは周囲のブロック、自由な線路は近くの区間を調べる */
    fun nearest(world: World, p: Vector3d, radius: Double = 2.0): TrackPos? {
        var best: TrackPos? = null
        var bestDist = radius * radius
        val tmp = Vector3d()
        fun consider(seg: TrackSegment) {
            val steps = maxOf(4, (seg.length * 4).toInt())
            for (i in 0..steps) {
                val s = seg.length * i / steps
                val d = seg.point(s, tmp).distanceSquared(p)
                if (d < bestDist) { bestDist = d; best = TrackPos(DirectedSegment(seg, false), s) }
            }
        }
        val r = radius.toInt() + 1
        val bx = floor(p.x).toInt()
        val by = floor(p.y).toInt()
        val bz = floor(p.z).toInt()
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            RailSegment.at(world, bx + dx, by + dy, bz + dz)?.let { consider(it) }
        }
        for (node in network.nodes.values) {
            if (node.world != world.uid) continue
            for (otherId in node.links) {
                if (otherId < node.id) continue
                val other = network.nodes[otherId] ?: continue
                val seg = network.segment(node, other)
                // 区間を囲む範囲から遠ければ調べない
                val reach = seg.length / 2 + radius
                val mid = Vector3d(node.pos).add(other.pos).mul(0.5)
                if (mid.distanceSquared(p) > reach * reach) continue
                consider(seg)
            }
        }
        return best
    }
}
