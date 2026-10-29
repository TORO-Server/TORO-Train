package net.torosaba.train.track

import net.torosaba.train.util.BlockKey
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.Rail
import org.joml.Vector3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * バニラのレール 1 ブロック分の区間。
 * 端はブロックの辺の中央にあり、隣のレールの端と同じ座標になる。曲線のレールは半径 0.5 の円弧にする。
 *
 * 感圧板もまっすぐなレールとして走れる。向きは隣の線路で決まり、南北だけに線路があれば南北、
 * 東西だけなら東西、両方ある・どちらもないなら十字路で、入ってきた向きのまままっすぐ抜ける。
 */
class RailSegment private constructor(
    override val world: World,
    val x: Int, val y: Int, val z: Int,
    val shape: Rail.Shape,
    val material: Material,
    /** パワードレールなどが動力を受けているか */
    val powered: Boolean,
) : BlockTrackSegment {
    private val a: Vector3d
    private val b: Vector3d
    // 曲線のときの円の中心 (ブロックの角)。直線なら null
    private val corner: Vector3d?
    override val length: Double
    val faceA: BlockFace
    val faceB: BlockFace

    init {
        val ends = ENDS.getValue(shape)
        faceA = ends.faceA
        faceB = ends.faceB
        a = port(faceA, ends.highA)
        b = port(faceB, ends.highB)
        corner = if (ends.curve) {
            Vector3d(x + 0.5 + (faceA.modX + faceB.modX) * 0.5, y + HEIGHT, z + 0.5 + (faceA.modZ + faceB.modZ) * 0.5)
        } else null
        length = if (corner != null) PI / 4 else a.distance(b)
    }

    override val key: BlockKey get() = BlockKey(world.uid, x, y, z)
    val isCurve: Boolean get() = corner != null
    val isSlope: Boolean get() = shape.name.startsWith("ASCENDING")
    val isPlate: Boolean get() = Tag.PRESSURE_PLATES.isTagged(material)

    // 感圧板の十字路は形が 2 つあるので、形も覚える
    override val id: String get() = if (isPlate) "r:$x,$y,$z/${shape.name}" else "r:$x,$y,$z"

    private fun port(face: BlockFace, high: Boolean) =
        Vector3d(x + 0.5 + face.modX * 0.5, y + HEIGHT + if (high) 1.0 else 0.0, z + 0.5 + face.modZ * 0.5)

    /** 端の座標 (0: 始点, 1: 終点) */
    fun end(index: Int, out: Vector3d = Vector3d()): Vector3d = out.set(if (index == 0) a else b)

    fun face(index: Int) = if (index == 0) faceA else faceB

    override fun point(s: Double, out: Vector3d): Vector3d {
        val t = (s / length).coerceIn(0.0, 1.0)
        val c = corner ?: return out.set(a).lerp(b, t)
        val angle = t * PI / 2
        val ca = cos(angle)
        val sa = sin(angle)
        return out.set(
            c.x + (a.x - c.x) * ca + (b.x - c.x) * sa,
            c.y,
            c.z + (a.z - c.z) * ca + (b.z - c.z) * sa,
        )
    }

    override fun tangent(s: Double, out: Vector3d): Vector3d {
        val c = corner ?: return out.set(b).sub(a).normalize()
        val angle = (s / length).coerceIn(0.0, 1.0) * PI / 2
        val ca = cos(angle)
        val sa = sin(angle)
        return out.set(
            -(a.x - c.x) * sa + (b.x - c.x) * ca,
            0.0,
            -(a.z - c.z) * sa + (b.z - c.z) * ca,
        ).normalize()
    }

    override fun triggerBlocks(from: Double, to: Double, out: MutableCollection<BlockKey>) {
        out += key
    }

    override fun equals(other: Any?) =
        other is RailSegment && other.x == x && other.y == y && other.z == z && other.shape == shape && other.world == world

    override fun hashCode() = ((x * 31 + y) * 31 + z) * 31 + shape.ordinal

    override fun toString() = "rail($x,$y,$z $shape)"

    private class Ends(val faceA: BlockFace, val highA: Boolean, val faceB: BlockFace, val highB: Boolean, val curve: Boolean)

    companion object {
        /** レールの上面の高さ。車輪はここに乗る */
        const val HEIGHT = 0.0625

        private val ENDS: Map<Rail.Shape, Ends> = mapOf(
            Rail.Shape.NORTH_SOUTH to Ends(BlockFace.NORTH, false, BlockFace.SOUTH, false, false),
            Rail.Shape.EAST_WEST to Ends(BlockFace.WEST, false, BlockFace.EAST, false, false),
            Rail.Shape.ASCENDING_EAST to Ends(BlockFace.WEST, false, BlockFace.EAST, true, false),
            Rail.Shape.ASCENDING_WEST to Ends(BlockFace.EAST, false, BlockFace.WEST, true, false),
            Rail.Shape.ASCENDING_NORTH to Ends(BlockFace.SOUTH, false, BlockFace.NORTH, true, false),
            Rail.Shape.ASCENDING_SOUTH to Ends(BlockFace.NORTH, false, BlockFace.SOUTH, true, false),
            Rail.Shape.SOUTH_EAST to Ends(BlockFace.SOUTH, false, BlockFace.EAST, false, true),
            Rail.Shape.SOUTH_WEST to Ends(BlockFace.SOUTH, false, BlockFace.WEST, false, true),
            Rail.Shape.NORTH_WEST to Ends(BlockFace.NORTH, false, BlockFace.WEST, false, true),
            Rail.Shape.NORTH_EAST to Ends(BlockFace.NORTH, false, BlockFace.EAST, false, true),
        )

        /** 2 つの方向をつなぐ平らなレールの形。つなげない組み合わせなら null */
        fun shapeBetween(a: BlockFace, b: BlockFace): Rail.Shape? =
            ENDS.entries.firstOrNull { (_, e) ->
                !e.highA && !e.highB && ((e.faceA == a && e.faceB == b) || (e.faceA == b && e.faceB == a))
            }?.key

        /** ブロックがレールならその区間。チャンクが読み込まれていなければ読まない */
        fun at(world: World, x: Int, y: Int, z: Int): RailSegment? {
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return null
            return of(world.getBlockAt(x, y, z))
        }

        /**
         * shape を渡すと、今の形ではなくその形だったときの区間を作る (分岐の経路探索に使う)。
         * 感圧板は取れる形のうち shape (なければ最初の形) にする
         */
        fun of(block: Block, shape: Rail.Shape? = null): RailSegment? {
            val data = block.blockData
            if (data is Rail) {
                val powered = (data as? Powerable)?.isPowered ?: false
                return RailSegment(block.world, block.x, block.y, block.z, shape ?: data.shape, block.type, powered)
            }
            if (!Tag.PRESSURE_PLATES.isTagged(block.type)) return null
            val shapes = plateShapes(block)
            val chosen = shape ?: shapes.first()
            if (chosen !in shapes) return null
            return RailSegment(block.world, block.x, block.y, block.z, chosen, block.type, false)
        }

        /** 感圧板の区間。十字路なら南北と東西の 2 つ */
        fun platesAt(block: Block): List<RailSegment> =
            if (!Tag.PRESSURE_PLATES.isTagged(block.type)) emptyList()
            else plateShapes(block).map { RailSegment(block.world, block.x, block.y, block.z, it, block.type, false) }

        // 隣 (同じ高さか 1 つ下) に線路があるか
        private fun trackBeside(block: Block, face: BlockFace): Boolean {
            val side = block.getRelative(face)
            return TrackBlocks.isTrack(side) || TrackBlocks.isTrack(side.getRelative(BlockFace.DOWN))
        }

        private fun plateShapes(block: Block): List<Rail.Shape> {
            val ns = trackBeside(block, BlockFace.NORTH) || trackBeside(block, BlockFace.SOUTH)
            val ew = trackBeside(block, BlockFace.EAST) || trackBeside(block, BlockFace.WEST)
            return when {
                ns && !ew -> listOf(Rail.Shape.NORTH_SOUTH)
                ew && !ns -> listOf(Rail.Shape.EAST_WEST)
                else -> listOf(Rail.Shape.NORTH_SOUTH, Rail.Shape.EAST_WEST)
            }
        }

        /** そのレールのブロックが取れる平らな形のうち、face を端に持つもの */
        fun shapesFrom(block: Block, face: BlockFace): List<Rail.Shape> {
            val shapes = (block.blockData as? Rail)?.shapes
                ?: if (Tag.PRESSURE_PLATES.isTagged(block.type)) plateShapes(block).toSet() else return emptyList()
            return shapes.filter { shape ->
                val e = ENDS[shape] ?: return@filter false
                !e.highA && !e.highB && (e.faceA == face || e.faceB == face)
            }
        }
    }
}
