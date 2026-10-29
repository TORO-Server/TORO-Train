package net.torosaba.train.route

import net.torosaba.train.TOROTrain
import net.torosaba.train.signs.ActionSign
import net.torosaba.train.track.DirectedSegment
import net.torosaba.train.track.RailSegment
import net.torosaba.train.train.Train
import net.torosaba.train.util.BlockKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import java.util.PriorityQueue

/**
 * 行き先までの経路を探す。
 * 線路を区間ごとにたどる最短経路探索 (ダイクストラ法) で、分岐器 (switcher の看板があるレール) と
 * 自由な線路の分岐では、今の向きに関係なくすべての向きを試す。
 * 結果はしばらく覚え、線路や看板が変わったら忘れる。
 */
class Router(private val plugin: TOROTrain) {
    private class Cached(val distance: Double?, val time: Long, val version: Long)

    private val cache = HashMap<String, Cached>()
    private val ttl = 30_000L
    private val maxVisited = 50_000

    private fun version(): Long = plugin.network.version.toLong() * 1_000_003L + plugin.signIndex.version

    /**
     * 行き先の名前の場所に着いたか。覚えている名前のほか、線路の下の駅と行き先の看板も直接見る
     * (コマンドや WorldEdit で置かれた看板は、置いたときに名前を覚えられないので)
     */
    private fun reaches(seg: DirectedSegment, destination: String): Boolean {
        val rail = seg.segment as? RailSegment
            ?: return seg.segment.names().any { it.equals(destination, ignoreCase = true) }
        val key = rail.key
        if (plugin.places.isDestination(destination, key)) return true
        for (block in plugin.signIndex.signsFor(key)) {
            val sign = ActionSign.parse(block) ?: continue
            if ((sign.type == "station" || sign.type == "st" || sign.type == "destination" || sign.type == "dest") &&
                sign.line3.equals(destination, ignoreCase = true)
            ) {
                plugin.places.addDestination(sign.line3, key)
                return true
            }
        }
        return false
    }

    private fun isSwitchable(key: BlockKey): Boolean = plugin.signIndex.signsOfType(key, "switcher").isNotEmpty() ||
        plugin.signIndex.signsOfType(key, "switch").isNotEmpty()

    /** start から行き先までの道のり。たどり着けなければ null */
    fun distance(start: DirectedSegment, destination: String): Double? {
        val segKey = (start.segment as? RailSegment)?.toString() ?: start.segment.id
        val cacheKey = "${start.segment.world.name}|$segKey|${start.reversed}|${destination.lowercase()}"
        val now = System.currentTimeMillis()
        val v = version()
        cache[cacheKey]?.let { if (now - it.time < ttl && it.version == v) return it.distance }
        val result = search(start, destination)
        cache[cacheKey] = Cached(result, now, v)
        if (cache.size > 4096) cache.entries.removeIf { now - it.value.time > ttl }
        return result
    }

    private fun search(start: DirectedSegment, destination: String): Double? {
        val limit = plugin.settings.routeSearchDistance
        val best = HashMap<DirectedSegment, Double>()
        val queue = PriorityQueue<Pair<DirectedSegment, Double>>(compareBy { it.second })
        queue += start to 0.0
        best[start] = 0.0
        var visited = 0
        while (queue.isNotEmpty()) {
            val (seg, dist) = queue.poll()
            if (dist > (best[seg] ?: Double.MAX_VALUE)) continue
            if (reaches(seg, destination)) return dist
            if (++visited > maxVisited || dist > limit) break
            for (next in plugin.graph.routeCandidates(seg, ::isSwitchable)) {
                val d = dist + seg.length
                if (d < (best[next] ?: Double.MAX_VALUE)) {
                    best[next] = d
                    queue += next to d
                }
            }
        }
        return null
    }

    /** 分岐の候補のうち、行き先に最も近いもの。どれもたどり着けなければ null */
    fun choose(options: List<DirectedSegment>, destination: String): DirectedSegment? =
        options.mapNotNull { o -> distance(o, destination)?.let { o to it } }.minByOrNull { it.second }?.first

    /** 分岐器のレールに entry の辺から入るとき、行き先に向かうにはどの辺から出ればよいか */
    fun chooseFace(train: Train, block: Block, entry: BlockFace, destination: String): BlockFace? {
        val options = RailSegment.shapesFrom(block, entry).mapNotNull { shape ->
            val rail = RailSegment.of(block, shape) ?: return@mapNotNull null
            val directed = DirectedSegment(rail, rail.faceA != entry)
            val exit = if (directed.reversed) rail.faceA else rail.faceB
            exit to directed
        }
        if (options.isEmpty()) return null
        return options.mapNotNull { (face, seg) -> distance(seg, destination)?.let { face to it } }
            .minByOrNull { it.second }?.first
    }

    fun clear() = cache.clear()
}
