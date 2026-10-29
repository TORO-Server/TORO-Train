package net.torosaba.train.model

import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.block.Sign
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Rail
import org.bukkit.block.data.type.Stairs
import org.bukkit.structure.Structure
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f

/**
 * ワールドのブロックを写し取った車体のモデル。
 *
 * ブロックは Structure にそのまま入れて保存するので、看板の文字やチェストの中身などのデータも失わない。
 * 見た目は、ブロックを写し取ったときの向きのまま Display エンティティで描き、baseRotation で車体の向きに合わせる。
 *
 * @param origin 車体の原点 (線路の上の車体の中心) の、Structure の角からの位置
 * @param baseRotation 写し取ったときのワールドの向きから、車体の向き (+Z が前) への回転
 */
class BlockModel(
    val name: String,
    val structure: Structure,
    val origin: Vector3d,
    val baseRotation: Quaternionf,
) {
    /** ブロックの数がこれより多いときは、外から見えないブロックも省かずに描く (省く計算のほうが重い) */
    private val cullLimit = 200_000

    private var cachedParts: List<Part>? = null
    private var cachedSeats: List<Seat>? = null
    private var cachedBounds: Pair<Vector3f, Vector3f>? = null

    fun blocks(): List<BlockState> = structure.palettes.firstOrNull()?.blocks ?: emptyList()

    /** 車体の座標での、モデルを囲む直方体 (min, max) */
    fun bounds(): Pair<Vector3f, Vector3f> {
        cachedBounds?.let { return it }
        val size = structure.size
        val min = Vector3f(Float.MAX_VALUE)
        val max = Vector3f(-Float.MAX_VALUE)
        for (corner in 0 until 8) {
            val p = Vector3f(
                (if (corner and 1 != 0) size.x else 0.0).toFloat() - origin.x.toFloat(),
                (if (corner and 2 != 0) size.y else 0.0).toFloat() - origin.y.toFloat(),
                (if (corner and 4 != 0) size.z else 0.0).toFloat() - origin.z.toFloat(),
            )
            baseRotation.transform(p)
            min.min(p)
            max.max(p)
        }
        return (min to max).also { cachedBounds = it }
    }

    fun parts(flipSignItems: Boolean): List<Part> {
        cachedParts?.let { return it }
        val states = blocks()
        val occluding = HashSet<Long>()
        if (states.size <= cullLimit) {
            for (s in states) if (s.type.isOccluding) occluding += pack(s.x, s.y, s.z)
        }
        val parts = ArrayList<Part>()
        for (state in states) {
            val type = state.type
            if (type.isAir || type == Material.STRUCTURE_VOID || type == Material.WATER || type == Material.LAVA) continue
            // 線路は車体に含めない (車両が走る線路を写し取っても、車体には描かない)
            if (state.blockData is Rail) continue
            if (isHidden(state, occluding)) continue

            val corner = Vector3f(
                (state.x - origin.x).toFloat(),
                (state.y - origin.y).toFloat(),
                (state.z - origin.z).toFloat(),
            )
            if (state is Sign) {
                val center = Vector3f(corner).add(0.5f, 0.5f, 0.5f)
                for (part in SignParts.build(state, center, flipSignItems)) {
                    parts += when (part) {
                        is BlockPart -> BlockPart(part.data, part.transform.premultiply(baseRotation))
                        is ItemPart -> ItemPart(part.item, part.display, part.transform.premultiply(baseRotation))
                        is TextPart -> TextPart(part.text, part.glowing, part.transform.premultiply(baseRotation))
                    }
                }
                continue
            }
            parts += BlockPart(state.blockData, PartTransform(corner).premultiply(baseRotation))
        }
        return parts.also { cachedParts = it }
    }

    // 6 面すべてが光を通さないブロックに囲まれていれば、外からは見えない
    private fun isHidden(state: BlockState, occluding: Set<Long>): Boolean {
        if (occluding.isEmpty() || !state.type.isOccluding) return false
        val x = state.x
        val y = state.y
        val z = state.z
        return pack(x + 1, y, z) in occluding && pack(x - 1, y, z) in occluding &&
            pack(x, y + 1, z) in occluding && pack(x, y - 1, z) in occluding &&
            pack(x, y, z + 1) in occluding && pack(x, y, z - 1) in occluding
    }

    private fun pack(x: Int, y: Int, z: Int): Long =
        ((x + 2048).toLong() and 0xFFF) or (((y + 2048).toLong() and 0xFFF) shl 12) or (((z + 2048).toLong() and 0xFFF) shl 24)

    /** 下向きの階段を座席にする。なければ車体の上面の中央に 1 つ置く */
    fun seats(): List<Seat> {
        cachedSeats?.let { return it }
        val positions = ArrayList<Vector3f>()
        for (state in blocks()) {
            val data = state.blockData
            if (data is Stairs && data.half == Bisected.Half.BOTTOM) {
                val p = Vector3f(
                    (state.x + 0.5 - origin.x).toFloat(),
                    (state.y + 0.5 - origin.y).toFloat(),
                    (state.z + 0.5 - origin.z).toFloat(),
                )
                positions += baseRotation.transform(p)
            }
        }
        if (positions.isEmpty()) {
            val (min, max) = bounds()
            positions += Vector3f((min.x + max.x) / 2, max.y, (min.z + max.z) / 2)
        }
        // いちばん前といちばん後ろの座席を運転席にする
        val front = positions.maxOf { it.z }
        val back = positions.minOf { it.z }
        return positions.map { Seat(it, it.z >= front - 0.01f || it.z <= back + 0.01f) }.also { cachedSeats = it }
    }

    fun invalidate() {
        cachedParts = null
        cachedSeats = null
        cachedBounds = null
    }
}
