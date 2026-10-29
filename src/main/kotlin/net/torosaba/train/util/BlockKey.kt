package net.torosaba.train.util

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Block
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.floor

/** ワールドとブロックの座標の組。マップのキーに使う */
data class BlockKey(val world: UUID, val x: Int, val y: Int, val z: Int) {
    fun world(): World? = Bukkit.getWorld(world)

    fun isLoaded(): Boolean = world()?.isChunkLoaded(x shr 4, z shr 4) == true

    /** チャンクが読み込まれていなければ null (読み込みはしない) */
    fun blockIfLoaded(): Block? {
        val w = world() ?: return null
        if (!w.isChunkLoaded(x shr 4, z shr 4)) return null
        return w.getBlockAt(x, y, z)
    }

    fun relative(dx: Int, dy: Int, dz: Int) = BlockKey(world, x + dx, y + dy, z + dz)

    fun center(): Vector3d = Vector3d(x + 0.5, y + 0.5, z + 0.5)

    override fun toString() = "$x,$y,$z"

    companion object {
        fun of(block: Block) = BlockKey(block.world.uid, block.x, block.y, block.z)

        fun of(world: World, p: Vector3d) = BlockKey(world.uid, floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())

        /** "x,y,z" を読む */
        fun parse(world: UUID, text: String): BlockKey? {
            val p = text.split(',').mapNotNull { it.trim().toIntOrNull() }
            return if (p.size == 3) BlockKey(world, p[0], p[1], p[2]) else null
        }
    }
}
