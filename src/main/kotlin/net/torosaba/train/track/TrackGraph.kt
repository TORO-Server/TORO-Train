package net.torosaba.train.track

import net.torosaba.train.util.BlockKey
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Rail
import org.bukkit.block.data.type.Ladder
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

/** ブロックにある線路 (レール、感圧板、ハシゴ) */
object TrackBlocks {
    /** ブロックにある線路の区間。感圧板の十字路は南北と東西の 2 つを返す */
    fun at(block: Block): List<BlockTrackSegment> {
        val data = block.blockData
        if (data is Rail) return listOfNotNull(RailSegment.of(block))
        if (data is Ladder) return listOfNotNull(LadderSegment.of(block))
        return RailSegment.platesAt(block)
    }

    fun isTrack(block: Block): Boolean =
        Tag.RAILS.isTagged(block.type) || Tag.PRESSURE_PLATES.isTagged(block.type) || block.type == Material.LADDER

    /** 区間があるブロック */
    fun keyOf(segment: TrackSegment): BlockKey? = (segment as? BlockTrackSegment)?.key
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

        // ブロックの線路。端の点に触れているブロック (辺や面の上にあれば両側) と、その上下を調べる。
        // 坂の上の端は 1 つ下のブロックのレールに、ハシゴの下の端は 1 つ上のブロックのハシゴにある
        val fromBlock = TrackBlocks.keyOf(from.segment)
        val xs = touching(p.x)
        val zs = touching(p.z)
        val by = floor(p.y + 1e-6).toInt()
        var unloaded = false
        for (bx in xs) for (bz in zs) {
            if (!world.isChunkLoaded(bx shr 4, bz shr 4)) {
                if (!allowLoad) {
                    unloaded = true
                    continue
                }
                world.getChunkAt(bx shr 4, bz shr 4)
            }
            for (y in by - 1..by + 1) {
                val block = world.getBlockAt(bx, y, bz)
                if (!TrackBlocks.isTrack(block)) continue
                if (fromBlock != null && fromBlock.x == bx && fromBlock.y == y && fromBlock.z == bz) continue
                for (seg in TrackBlocks.at(block)) {
                    if (seg == from.segment) continue
                    val rail = seg as? RailSegment
                    if (rail != null && !rail.isPlate && switchable != null && switchable(rail.key)) {
                        // 分岐器: 入ってくる辺を端に持つ形をすべて候補にする
                        val face = entryFace(rail, p) ?: continue
                        for (shape in RailSegment.shapesFrom(block, face)) {
                            val alt = RailSegment.of(block, shape) ?: continue
                            directed(alt, p, dir)?.let { if (it !in result) result += it }
                        }
                        // 取れる形は平らなものだけなので、坂の分岐器は今の形も候補にする (下へ続ける)
                    }
                    directed(seg, p, dir)?.let { if (it !in result) result += it }
                }
            }
        }
        return if (result.isEmpty() && unloaded) null else result
    }

    // 座標が整数 (ブロックの境目) なら両側のブロック、そうでなければそのブロック
    private fun touching(v: Double): IntArray {
        val lo = floor(v - 1e-4).toInt()
        val hi = floor(v + 1e-4).toInt()
        return if (lo == hi) intArrayOf(lo) else intArrayOf(lo, hi)
    }

    // p を端に持つなら、p から進む向きの区間にする。来た向きへ折り返す区間は除く
    private fun directed(seg: TrackSegment, p: Vector3d, dir: Vector3d): DirectedSegment? {
        val tmp = Vector3d()
        val ds = when {
            seg.point(0.0, tmp).distanceSquared(p) < 1e-4 -> DirectedSegment(seg, false)
            seg.point(seg.length, tmp).distanceSquared(p) < 1e-4 -> DirectedSegment(seg, true)
            else -> return null
        }
        return if (ds.tangent(0.0, tmp).dot(dir) > -0.5) ds else null
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
        id.startsWith("r:") -> {
            val body = id.removePrefix("r:")
            val shape = body.substringAfter('/', "").takeIf { it.isNotEmpty() }
                ?.let { runCatching { Rail.Shape.valueOf(it) }.getOrNull() }
            BlockKey.parse(world.uid, body.substringBefore('/'))?.let { k ->
                world.getChunkAt(k.x shr 4, k.z shr 4)
                val block = world.getBlockAt(k.x, k.y, k.z)
                // 覚えていた形で作る (列車が通った道のまま)。そのブロックで取れない形なら今の形
                val usable = shape != null && (block.blockData as? Rail)?.shapes?.contains(shape) != false
                if (usable) RailSegment.of(block, shape) ?: RailSegment.of(block) else RailSegment.of(block)
            }
        }
        id.startsWith("l:") -> BlockKey.parse(world.uid, id.removePrefix("l:"))?.let { k ->
            world.getChunkAt(k.x shr 4, k.z shr 4)
            LadderSegment.of(world.getBlockAt(k.x, k.y, k.z))
        }
        id.startsWith("f:") -> FreeSegment.parse(world, id)
        id.startsWith("c:") -> network.segmentById(id)
        else -> null
    }

    /** ブロックにあるレールの区間 */
    fun railAt(world: World, x: Int, y: Int, z: Int): RailSegment? = RailSegment.at(world, x, y, z)

    /**
     * 点に最も近い線路の位置。レールは周囲のブロック、自由な線路は近くの区間を調べる。
     * skip が true を返す区間は除く
     */
    fun nearest(world: World, p: Vector3d, radius: Double = 2.0, skip: (TrackSegment) -> Boolean = { false }): TrackPos? {
        var best: TrackPos? = null
        var bestDist = radius * radius
        val tmp = Vector3d()
        fun consider(seg: TrackSegment) {
            if (skip(seg)) return
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
            val x = bx + dx
            val z = bz + dz
            if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
            val block = world.getBlockAt(x, by + dy, z)
            if (TrackBlocks.isTrack(block)) TrackBlocks.at(block).forEach { consider(it) }
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
