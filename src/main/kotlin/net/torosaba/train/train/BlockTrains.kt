package net.torosaba.train.train

import net.torosaba.train.TOROTrain
import net.torosaba.train.model.BlockCartModel
import net.torosaba.train.track.DirectedSegment
import net.torosaba.train.track.RailSegment
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Rail
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.util.BlockTransformer
import org.bukkit.util.BlockVector
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.Random
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * ワールドのブロックと列車の行き来。
 *
 * 範囲のブロックを Structure に写し取ってから消し、同じ見た目の車両にする。
 * 元に戻すときは Structure をそのまま置くので、看板の文字 (MarumaSign の看板も) やチェストの中身などのデータは失われない。
 */
class BlockTrains(private val plugin: TOROTrain) {
    class Selection(var world: World? = null, var pos1: BlockVector? = null, var pos2: BlockVector? = null) {
        fun complete() = world != null && pos1 != null && pos2 != null
        fun min() = BlockVector(minOf(pos1!!.blockX, pos2!!.blockX), minOf(pos1!!.blockY, pos2!!.blockY), minOf(pos1!!.blockZ, pos2!!.blockZ))
        fun max() = BlockVector(maxOf(pos1!!.blockX, pos2!!.blockX), maxOf(pos1!!.blockY, pos2!!.blockY), maxOf(pos1!!.blockZ, pos2!!.blockZ))
        fun volume(): Long {
            val a = min()
            val b = max()
            return (b.blockX - a.blockX + 1).toLong() * (b.blockY - a.blockY + 1) * (b.blockZ - a.blockZ + 1)
        }
    }

    /** 選んだ範囲。プレイヤーの UUID (コンソールなどは CONSOLE) ごと */
    val selections = HashMap<UUID, Selection>()

    fun selection(id: UUID) = selections.getOrPut(id) { Selection() }

    fun setPos(id: UUID, index: Int, location: Location) {
        val sel = selection(id)
        if (sel.world != location.world) {
            sel.pos1 = null
            sel.pos2 = null
        }
        sel.world = location.world
        val v = BlockVector(location.blockX, location.blockY, location.blockZ)
        if (index == 1) sel.pos1 = v else sel.pos2 = v
    }

    private fun checkSelection(id: UUID): Selection {
        val sel = selection(id)
        if (!sel.complete()) throw IllegalStateException("先に範囲を選んでください (/tt wand、/tt pos1、/tt pos2)")
        val limit = plugin.config.getLong("blocks.max-volume", 65536)
        if (sel.volume() > limit) throw IllegalStateException("範囲が大きすぎます (${sel.volume()} > $limit ブロック)")
        return sel
    }

    private fun yawOf(face: BlockFace) = atan2(face.modX.toDouble(), face.modZ.toDouble())

    /** 範囲をモデルとして保存する (ブロックは消さない)。前は forward の向き */
    fun capture(id: UUID, name: String, forward: BlockFace, yOffset: Double): Int {
        val sel = checkSelection(id)
        val world = sel.world!!
        val min = sel.min()
        val max = sel.max()
        val size = BlockVector(max.blockX - min.blockX + 1, max.blockY - min.blockY + 1, max.blockZ - min.blockZ + 1)
        val structure = Bukkit.getStructureManager().createStructure()
        structure.fill(Location(world, min.x, min.y, min.z), size, false)
        val origin = Vector3d(size.x / 2, -yOffset, size.z / 2)
        val base = Quaternionf().rotateY((-yawOf(forward)).toFloat())
        plugin.models.save(name, structure, origin, base)
        return structure.palettes.firstOrNull()?.blockCount ?: 0
    }

    /**
     * 範囲のブロックを列車にする。範囲の中か真下にある線路の上を走る 1 両の列車になり、ブロックは消える。
     * 線路の 2 つの向きのうち、look に近いほうが前になる。
     */
    fun convert(id: UUID, name: String?, look: Vector3d): Train {
        val sel = checkSelection(id)
        val world = sel.world!!
        val min = sel.min()
        val max = sel.max()
        val size = BlockVector(max.blockX - min.blockX + 1, max.blockY - min.blockY + 1, max.blockZ - min.blockZ + 1)

        // 範囲の中央に近い、いちばん低い線路
        val cx = (min.x + max.x + 1) / 2
        val cz = (min.z + max.z + 1) / 2
        var rail: RailSegment? = null
        var best = Double.MAX_VALUE
        for (y in (min.blockY - 1)..max.blockY) for (x in min.blockX..max.blockX) for (z in min.blockZ..max.blockZ) {
            val r = RailSegment.of(world.getBlockAt(x, y, z)) ?: continue
            val d = (x + 0.5 - cx) * (x + 0.5 - cx) + (z + 0.5 - cz) * (z + 0.5 - cz) + (y - min.blockY + 1) * 0.01
            if (d < best) { best = d; rail = r }
        }
        rail ?: throw IllegalStateException("範囲の中か真下に線路がありません")

        val modelName = (name ?: "train-${UUID.randomUUID().toString().take(8)}").lowercase()
        if (plugin.models.exists(modelName)) throw IllegalStateException("モデル $modelName はもうあります")

        val forward = DirectedSegment(rail, false).let { if (it.tangent(it.length / 2).dot(look) >= 0) it else it.flip() }
        val fwd = forward.tangent(forward.length / 2)
        val length = if (abs(fwd.x) > abs(fwd.z)) size.x else size.z
        val center = forward.point(forward.length / 2)
        val along = (cx - center.x) * fwd.x + (cz - center.z) * fwd.z

        // 列車の中心が範囲の中央に来るように、先頭の位置を決める
        val path = plugin.trains.buildPath(forward, forward.length / 2, length + 4 + maxOf(0.0, -along))
        val shift = along + length / 2
        if (shift > 0) path.advance(shift) { plugin.graph.next(it, null, true) } else path.retreat(-shift)

        val structure = Bukkit.getStructureManager().createStructure()
        structure.fill(Location(world, min.x, min.y, min.z), size, false)

        val tmp = net.torosaba.train.train.Train(UUID.randomUUID(), "tmp", path)
        tmp.gap = plugin.settings.cartGap
        val probe = Cart(UUID.randomUUID(), net.torosaba.train.model.DefaultCartModel())
        tmp.add(probe) { length }
        tmp.computePoses()
        val origin = Vector3d(probe.position).sub(min.x, min.y, min.z)
        val base = Quaternionf(probe.rotation).conjugate()
        plugin.models.save(modelName, structure, origin, base)

        // ブロックを消す (線路は残す)。物理の更新をしないので、看板や松明が落ちて物にならない
        for (y in max.blockY downTo min.blockY) for (x in min.blockX..max.blockX) for (z in min.blockZ..max.blockZ) {
            val block = world.getBlockAt(x, y, z)
            if (block.type.isAir || block.blockData is Rail) continue
            block.setType(Material.AIR, false)
        }

        return plugin.trains.create(path, listOf(BlockCartModel(modelName, lengthOverride = length)))
    }

    /**
     * 列車の車両を、今いる場所にブロックとして置き直す。向きは 90 度ごとに合わせる。
     * force でなければ、置く場所にブロックがあるときはやめる。
     */
    fun restore(train: Train, force: Boolean): Int {
        if (train.speed > 1e-3) throw IllegalStateException("列車を止めてから戻してください")
        val carts = train.carts.filter { it.model is BlockCartModel }
        if (carts.isEmpty()) throw IllegalStateException("ブロックでできた車両がありません")
        val plans = carts.map { plan(it) }
        if (!force) {
            for (p in plans) p.blocked?.let { throw IllegalStateException("置く場所にブロックがあります (${it.x}, ${it.y}, ${it.z})。/tt blocks restore force で上書きします") }
        }
        for (p in plans) p.place()
        val names = carts.mapNotNull { it.model.blockModelName }
        plugin.trains.destroy(train)
        // 変換で自動で作ったモデルは、ほかで使っていなければ消す
        for (name in names) {
            if (!name.startsWith("train-")) continue
            val used = plugin.trains.trains.values.any { t -> t.carts.any { it.model.blockModelName == name } } || plugin.saved.usesModel(name)
            if (!used) plugin.models.delete(name)
        }
        return plans.sumOf { it.count }
    }

    private class Plan(val blocked: org.bukkit.block.Block?, val count: Int, val place: () -> Unit)

    private fun plan(cart: Cart): Plan {
        val model = cart.model as BlockCartModel
        val block = plugin.models.get(model.name) ?: throw IllegalStateException("モデル ${model.name} がありません")
        val world = cart.train.world
        val q = Quaternionf(cart.rotation).rotateY(Math.toRadians(model.yaw.toDouble()).toFloat()).mul(block.baseRotation)
        val x = q.transform(Vector3f(1f, 0f, 0f))
        val k = ((atan2(-x.z.toDouble(), x.x.toDouble()) / (PI / 2)).roundToInt()).mod(4)
        val snap = Quaternionf().rotateY((k * PI / 2).toFloat())
        val rotation = when (k) {
            1 -> StructureRotation.COUNTERCLOCKWISE_90
            2 -> StructureRotation.CLOCKWISE_180
            3 -> StructureRotation.CLOCKWISE_90
            else -> StructureRotation.NONE
        }
        val offset = cart.rotation.transform(Vector3f(model.offset))
        val first = snap.transform(Vector3f(0.5f, 0.5f, 0.5f).sub(block.origin.x.toFloat(), block.origin.y.toFloat(), block.origin.z.toFloat()))
        val w0 = Vector3d(cart.position).add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()).add(first.x.toDouble(), first.y.toDouble(), first.z.toDouble())
        val loc = Location(world, floor(w0.x), floor(w0.y), floor(w0.z))

        var blocked: org.bukkit.block.Block? = null
        var count = 0
        for (state in block.blocks()) {
            if (state.type.isAir || state.blockData is Rail) continue
            count++
            val v = snap.transform(Vector3f(state.x.toFloat(), state.y.toFloat(), state.z.toFloat()))
            val target = loc.block.getRelative(v.x.roundToInt(), v.y.roundToInt(), v.z.roundToInt())
            if (blocked == null && !target.type.isAir && !target.isReplaceable && target.blockData !is Rail) blocked = target
        }
        // 空気と線路は置かず、置く場所の線路も残す
        val keepWorld = BlockTransformer { _, _, _, _, _, s ->
            val original = s.original
            val existing = s.world
            if (original.type.isAir || original.blockData is Rail || existing.blockData is Rail) existing else original
        }
        return Plan(blocked, count) {
            block.structure.place(loc, false, rotation, Mirror.NONE, 0, 1f, Random(), listOf(keepWorld), emptyList())
        }
    }
}
