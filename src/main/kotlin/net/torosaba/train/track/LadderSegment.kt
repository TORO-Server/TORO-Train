package net.torosaba.train.track

import net.torosaba.train.util.BlockKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Ladder
import org.joml.Vector3d

/**
 * ハシゴ 1 ブロック分の、真上・真下に進む区間。
 *
 * 線路はハシゴを付けた壁の面の上を通り、高さはレールと同じだけ (RailSegment.HEIGHT) 上にずらす。
 * こうすると、端の座標が次の線路の端とちょうど重なる:
 * - 下: ハシゴの下のブロックにある、壁へ向かって上る坂のレールの上の端
 * - 下: ハシゴの下のブロックにある、壁に端が向いた平らなレール (そのときは区間を 1 ブロック下まで伸ばす)
 * - 上: 壁の上に置いたレールの、ハシゴ側の端。ハシゴの真上に置いたレールの、壁側の端
 * 車体の床は壁を向き、上は壁から離れる向き (ハシゴの向き) になる。
 */
class LadderSegment private constructor(
    override val world: World,
    val x: Int, val y: Int, val z: Int,
    /** ハシゴが向いている向き (壁から離れる向き) */
    val facing: BlockFace,
    /** 下のブロックの平らなレールまで伸ばしているか */
    val extended: Boolean,
) : BlockTrackSegment {
    private val bottom = Vector3d(
        x + 0.5 - facing.modX * 0.5,
        y + RailSegment.HEIGHT - if (extended) 1.0 else 0.0,
        z + 0.5 - facing.modZ * 0.5,
    )
    override val length = if (extended) 2.0 else 1.0
    override val key: BlockKey get() = BlockKey(world.uid, x, y, z)
    override val id: String get() = "l:$x,$y,$z"

    override fun point(s: Double, out: Vector3d): Vector3d = out.set(bottom).add(0.0, s.coerceIn(0.0, length), 0.0)

    override fun tangent(s: Double, out: Vector3d): Vector3d = out.set(0.0, 1.0, 0.0)

    override fun up(s: Double): Vector3d = Vector3d(facing.modX.toDouble(), 0.0, facing.modZ.toDouble())

    override fun triggerBlocks(from: Double, to: Double, out: MutableCollection<BlockKey>) {
        out += key
    }

    override fun equals(other: Any?) =
        other is LadderSegment && other.x == x && other.y == y && other.z == z && other.extended == extended && other.world == world

    override fun hashCode() = ((x * 31 + y) * 31 + z) * 31 + 7

    override fun toString() = "ladder($x,$y,$z $facing)"

    companion object {
        fun of(block: Block): LadderSegment? {
            val data = block.blockData as? Ladder ?: return null
            val facing = data.facing
            return LadderSegment(block.world, block.x, block.y, block.z, facing, reachesRailBelow(block, facing))
        }

        // 下のブロックに、壁側の辺に端を持つ平らなレールがあるか
        private fun reachesRailBelow(block: Block, facing: BlockFace): Boolean {
            val below = block.getRelative(BlockFace.DOWN)
            if (below.blockData is Ladder) return false
            val wall = facing.oppositeFace
            val rail = RailSegment.of(below) ?: return false
            if (rail.isPlate) return RailSegment.shapesFrom(below, wall).isNotEmpty()
            return !rail.isSlope && (rail.faceA == wall || rail.faceB == wall)
        }
    }
}
