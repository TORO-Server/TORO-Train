package net.torosaba.train.signs

import net.torosaba.train.track.RailSegment
import net.torosaba.train.track.TrackBlocks
import net.torosaba.train.util.BlockKey
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Ladder
import org.bukkit.block.data.type.WallSign

/**
 * 線路のブロックと、そこに働く看板の対応。
 * 看板は線路の 1 つ下か 2 つ下に置くか、線路の下のブロックの横に貼る。
 * 調べた結果はしばらく覚え、看板が変わったら周りの分を忘れる。
 */
class SignIndex {
    private class Entry(val signs: List<BlockKey>, val time: Long)

    private val cache = HashMap<BlockKey, Entry>()
    private val ttl = 10_000L

    /** 看板が変わるたびに増える。経路の記憶の破棄に使う */
    var version = 0
        private set

    fun signsFor(rail: BlockKey): List<Block> {
        val now = System.currentTimeMillis()
        val entry = cache[rail]
        val keys = if (entry != null && now - entry.time < ttl) entry.signs else scan(rail).also { cache[rail] = Entry(it, now) }
        return keys.mapNotNull { it.blockIfLoaded() }.filter { Tag.ALL_SIGNS.isTagged(it.type) }
    }

    /** 線路のブロックの、種類が type の看板 */
    fun signsOfType(rail: BlockKey, type: String): List<ActionSign> =
        signsFor(rail).mapNotNull { ActionSign.parse(it) }.filter { it.type == type }

    private fun scan(rail: BlockKey): List<BlockKey> {
        val block = rail.blockIfLoaded() ?: return emptyList()
        val result = ArrayList<BlockKey>()
        val below = block.getRelative(BlockFace.DOWN)
        for (b in listOf(below, below.getRelative(BlockFace.DOWN))) {
            if (Tag.ALL_SIGNS.isTagged(b.type) && ActionSign.parse(b) != null) result += BlockKey.of(b)
        }
        // 線路の下のブロックの横に貼った看板
        for (face in HORIZONTAL) {
            val b = below.getRelative(face)
            val data = b.blockData
            if (data is WallSign && data.facing == face && ActionSign.parse(b) != null) result += BlockKey.of(b)
        }
        // 逆さまのレールは上下を逆にして、天井のブロックの上に置いた看板と、天井のブロックの横に貼った看板
        if (RailSegment.isUpsideDown(block)) {
            val ceiling = block.getRelative(BlockFace.UP)
            val top = ceiling.getRelative(BlockFace.UP)
            if (Tag.ALL_SIGNS.isTagged(top.type) && ActionSign.parse(top) != null) result += BlockKey.of(top)
            for (face in HORIZONTAL) {
                val b = ceiling.getRelative(face)
                val data = b.blockData
                if (data is WallSign && data.facing == face && ActionSign.parse(b) != null) result += BlockKey.of(b)
            }
        }
        // ハシゴは、ハシゴを付けた壁の裏に置いた看板と、壁の横に貼った看板
        val ladder = block.blockData as? Ladder
        if (ladder != null) {
            val back = ladder.facing.oppositeFace
            val wall = block.getRelative(back)
            val behind = wall.getRelative(back)
            if (Tag.ALL_SIGNS.isTagged(behind.type) && ActionSign.parse(behind) != null) result += BlockKey.of(behind)
            for (face in HORIZONTAL) {
                if (face == ladder.facing) continue
                val b = wall.getRelative(face)
                val data = b.blockData
                if (data is WallSign && data.facing == face && ActionSign.parse(b) != null) result += BlockKey.of(b)
            }
        }
        return result
    }

    private val HORIZONTAL = arrayOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

    /** 看板が置かれた・消された・書き換えられたとき */
    fun invalidateAround(sign: BlockKey) {
        version++
        cache.keys.removeIf { k -> k.world == sign.world && kotlin.math.abs(k.x - sign.x) <= 2 && kotlin.math.abs(k.z - sign.z) <= 2 && k.y - sign.y in -3..3 }
    }

    fun clear() {
        version++
        cache.clear()
    }

    /** 看板が働く線路のブロック (看板から逆に探す) */
    fun railFor(sign: Block): Block? {
        val data = sign.blockData
        val base = if (data is WallSign) sign.getRelative(data.facing.oppositeFace) else sign
        for (dy in 1..2) {
            val b = base.getRelative(0, dy, 0)
            if (TrackBlocks.isTrack(b)) return b
        }
        // 逆さまのレールの天井の上や横の看板
        for (dy in 1..2) {
            val b = base.getRelative(0, -dy, 0)
            if (Tag.RAILS.isTagged(b.type) && RailSegment.isUpsideDown(b)) return b
        }
        // ハシゴを付けた壁の裏や横の看板
        for (face in HORIZONTAL) {
            val wall = if (data is WallSign) base else sign.getRelative(face)
            val b = wall.getRelative(face)
            if ((b.blockData as? Ladder)?.facing == face) return b
        }
        // 自由な線路のノードの下に置いた看板なら、線路のブロックそのものは空気
        return base.getRelative(0, 1, 0)
    }
}
