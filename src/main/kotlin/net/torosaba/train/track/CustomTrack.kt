package net.torosaba.train.track

import net.torosaba.train.util.BlockKey
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.joml.Vector3d
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * 自由に置ける線路の点。ノードどうしを結ぶと、その間が滑らかな曲線の線路になる。
 * レールの端と同じ座標に置けば、バニラのレールともつながる。
 */
class TrackNode(
    val id: Int,
    val world: UUID,
    val pos: Vector3d,
) {
    /** 線路の向き。null なら隣のノードから決める */
    var direction: Vector3d? = null

    /** 車体の傾き (度)。正の値で進行方向の右側が下がる */
    var roll: Double = 0.0

    /** 行き先の名前 */
    var name: String? = null

    /** つながっているノードの ID */
    val links = LinkedHashSet<Int>()

    /** 分岐の向き: どのノードから来たときに、どのノードへ進むか */
    val switches = HashMap<Int, Int>()

    fun blockKey() = BlockKey(world, floor(pos.x).toInt(), floor(pos.y).toInt(), floor(pos.z).toInt())
}

/**
 * 2 つのノードの間の線路。両端のノードの向きに沿う 3 次ベジェ曲線で、道のりから位置を引けるよう表を持つ。
 */
class CustomSegment(private val network: TrackNetwork, val a: TrackNode, val b: TrackNode) : TrackSegment {
    override val world: World get() = Bukkit.getWorld(a.world) ?: error("ワールドが読み込まれていません")
    override val id: String get() = "c:${a.id}-${b.id}"

    private var builtVersion = -1
    private val p0 = Vector3d()
    private val p1 = Vector3d()
    private val p2 = Vector3d()
    private val p3 = Vector3d()
    private var ts = DoubleArray(0)
    private var lens = DoubleArray(0)
    private var total = 0.0

    override val length: Double get() { ensureBuilt(); return total }

    private fun ensureBuilt() {
        if (builtVersion == network.version) return
        p0.set(a.pos)
        p3.set(b.pos)
        val chord = Vector3d(p3).sub(p0)
        val dist = chord.length()
        val ta = network.directionOf(a).let { if (it.dot(chord) < 0) Vector3d(it).negate() else Vector3d(it) }
        val tb = network.directionOf(b).let { if (it.dot(chord) < 0) Vector3d(it).negate() else Vector3d(it) }
        p1.set(ta).mul(dist / 3).add(p0)
        p2.set(tb).mul(-dist / 3).add(p3)

        val n = (dist * 8).toInt().coerceIn(8, 1024)
        ts = DoubleArray(n + 1)
        lens = DoubleArray(n + 1)
        val prev = Vector3d(p0)
        val cur = Vector3d()
        var acc = 0.0
        for (i in 1..n) {
            val t = i.toDouble() / n
            bezier(t, cur)
            acc += cur.distance(prev)
            prev.set(cur)
            ts[i] = t
            lens[i] = acc
        }
        total = maxOf(acc, 1e-3)
        builtVersion = network.version
    }

    private fun bezier(t: Double, out: Vector3d): Vector3d {
        val u = 1 - t
        val c0 = u * u * u
        val c1 = 3 * u * u * t
        val c2 = 3 * u * t * t
        val c3 = t * t * t
        return out.set(
            p0.x * c0 + p1.x * c1 + p2.x * c2 + p3.x * c3,
            p0.y * c0 + p1.y * c1 + p2.y * c2 + p3.y * c3,
            p0.z * c0 + p1.z * c1 + p2.z * c2 + p3.z * c3,
        )
    }

    private fun derivative(t: Double, out: Vector3d): Vector3d {
        val u = 1 - t
        val c0 = 3 * u * u
        val c1 = 6 * u * t
        val c2 = 3 * t * t
        return out.set(
            (p1.x - p0.x) * c0 + (p2.x - p1.x) * c1 + (p3.x - p2.x) * c2,
            (p1.y - p0.y) * c0 + (p2.y - p1.y) * c1 + (p3.y - p2.y) * c2,
            (p1.z - p0.z) * c0 + (p2.z - p1.z) * c1 + (p3.z - p2.z) * c2,
        )
    }

    // 道のりから曲線のパラメータ t を求める
    private fun paramAt(s: Double): Double {
        ensureBuilt()
        if (s <= 0) return 0.0
        if (s >= total) return 1.0
        var lo = 0
        var hi = lens.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (lens[mid] < s) lo = mid else hi = mid
        }
        val span = lens[hi] - lens[lo]
        val f = if (span > 0) (s - lens[lo]) / span else 0.0
        return ts[lo] + (ts[hi] - ts[lo]) * f
    }

    override fun point(s: Double, out: Vector3d): Vector3d = bezier(paramAt(s), out)

    override fun tangent(s: Double, out: Vector3d): Vector3d {
        derivative(paramAt(s), out)
        if (out.lengthSquared() < 1e-12) out.set(p3).sub(p0)
        return out.normalize()
    }

    override fun roll(s: Double): Double {
        val f = (s / length).coerceIn(0.0, 1.0)
        return a.roll + (b.roll - a.roll) * f
    }

    override fun triggerBlocks(from: Double, to: Double, out: MutableCollection<BlockKey>) {
        val w = a.world
        val p = Vector3d()
        var s = from
        while (s <= to) {
            point(s, p)
            // 線路の点のブロックを、レールのブロックと同じように扱う
            out += BlockKey(w, floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())
            s += 0.5
        }
    }

    override fun names(): Collection<String> = listOfNotNull(a.name, b.name)

    override fun equals(other: Any?) = other is CustomSegment && other.a.id == a.id && other.b.id == b.id
    override fun hashCode() = a.id * 7919 + b.id
}

/** 自由な線路のノードをすべて持ち、tracks.yml に保存する */
class TrackNetwork(private val file: File) {
    val nodes = HashMap<Int, TrackNode>()
    private var nextId = 1
    private val segments = HashMap<Long, CustomSegment>()
    // 座標から、そこにあるノードを引く表
    private val byPoint = HashMap<PointKey, MutableList<Int>>()

    /** ノードが変わるたびに増える。曲線の作り直しと、経路の記憶の破棄に使う */
    var version = 0
        private set

    private data class PointKey(val world: UUID, val x: Long, val y: Long, val z: Long)

    private fun keyOf(world: UUID, p: Vector3d) =
        PointKey(world, (p.x * 32).roundToLong(), (p.y * 32).roundToLong(), (p.z * 32).roundToLong())

    fun changed() {
        version++
        byPoint.clear()
        for (node in nodes.values) byPoint.getOrPut(keyOf(node.world, node.pos)) { mutableListOf() } += node.id
    }

    fun add(world: World, pos: Vector3d): TrackNode {
        val node = TrackNode(nextId++, world.uid, Vector3d(pos))
        nodes[node.id] = node
        changed()
        return node
    }

    fun remove(node: TrackNode) {
        for (other in node.links.toList()) unlink(node, nodes[other] ?: continue)
        nodes.remove(node.id)
        changed()
    }

    fun link(a: TrackNode, b: TrackNode) {
        if (a.id == b.id || a.world != b.world) return
        a.links += b.id
        b.links += a.id
        changed()
    }

    fun unlink(a: TrackNode, b: TrackNode) {
        a.links -= b.id
        b.links -= a.id
        a.switches.values.removeIf { it == b.id }
        b.switches.values.removeIf { it == a.id }
        segments.remove(pairKey(a.id, b.id))
        changed()
    }

    fun move(node: TrackNode, pos: Vector3d) {
        node.pos.set(pos)
        changed()
    }

    private fun pairKey(a: Int, b: Int): Long = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()

    /** 2 つのノードの間の区間。ID の小さいノードが始点になる */
    fun segment(a: TrackNode, b: TrackNode): CustomSegment =
        segments.getOrPut(pairKey(a.id, b.id)) {
            if (a.id < b.id) CustomSegment(this, a, b) else CustomSegment(this, b, a)
        }

    fun segmentById(id: String): CustomSegment? {
        val ids = id.removePrefix("c:").split('-').mapNotNull { it.toIntOrNull() }
        if (ids.size != 2) return null
        val a = nodes[ids[0]] ?: return null
        val b = nodes[ids[1]] ?: return null
        if (b.id !in a.links) return null
        return segment(a, b)
    }

    fun nodesAt(world: UUID, p: Vector3d): List<TrackNode> =
        byPoint[keyOf(world, p)]?.mapNotNull { nodes[it] } ?: emptyList()

    fun nearest(world: World, p: Vector3d, maxDistance: Double): TrackNode? =
        nodes.values.filter { it.world == world.uid }
            .minByOrNull { it.pos.distanceSquared(p) }
            ?.takeIf { it.pos.distance(p) <= maxDistance }

    fun byName(name: String): List<TrackNode> = nodes.values.filter { it.name.equals(name, ignoreCase = true) }

    /** ノードの線路の向き。決めていなければ、つながっているノードから求める */
    fun directionOf(node: TrackNode): Vector3d {
        node.direction?.let { return Vector3d(it).normalize() }
        // 同じ場所のノードからは向きが決まらない
        val others = node.links.mapNotNull { nodes[it] }.filter { it.pos.distanceSquared(node.pos) > 1e-8 }
        return when (others.size) {
            0 -> Vector3d(1.0, 0.0, 0.0)
            1 -> Vector3d(others[0].pos).sub(node.pos).normalize()
            else -> {
                // 最も反対を向いている 2 つのノードを、まっすぐ通り抜ける向きにする
                var best: Pair<TrackNode, TrackNode>? = null
                var bestDot = Double.MAX_VALUE
                for (i in others.indices) for (j in i + 1 until others.size) {
                    val u = Vector3d(others[i].pos).sub(node.pos).normalize()
                    val v = Vector3d(others[j].pos).sub(node.pos).normalize()
                    val dot = u.dot(v)
                    if (dot < bestDot) { bestDot = dot; best = others[i] to others[j] }
                }
                val (p, q) = best!!
                Vector3d(q.pos).sub(p.pos).normalize()
            }
        }
    }

    fun load() {
        nodes.clear()
        segments.clear()
        if (!file.exists()) { changed(); return }
        val yaml = YamlConfiguration.loadConfiguration(file)
        nextId = yaml.getInt("next-id", 1)
        val section = yaml.getConfigurationSection("nodes")
        for (key in section?.getKeys(false) ?: emptySet()) {
            val s = section!!.getConfigurationSection(key) ?: continue
            val id = key.toIntOrNull() ?: continue
            val world = runCatching { UUID.fromString(s.getString("world")) }.getOrNull() ?: continue
            val node = TrackNode(id, world, Vector3d(s.getDouble("x"), s.getDouble("y"), s.getDouble("z")))
            if (s.isSet("dx")) node.direction = Vector3d(s.getDouble("dx"), s.getDouble("dy"), s.getDouble("dz"))
            node.roll = s.getDouble("roll")
            node.name = s.getString("name")
            node.links += s.getIntegerList("links")
            s.getConfigurationSection("switches")?.let { sw ->
                for (from in sw.getKeys(false)) node.switches[from.toInt()] = sw.getInt(from)
            }
            nodes[id] = node
            nextId = maxOf(nextId, id + 1)
        }
        changed()
    }

    fun save() {
        val yaml = YamlConfiguration()
        yaml.set("next-id", nextId)
        for (node in nodes.values) {
            val s = yaml.createSection("nodes.${node.id}")
            s.set("world", node.world.toString())
            s.set("x", node.pos.x)
            s.set("y", node.pos.y)
            s.set("z", node.pos.z)
            node.direction?.let { s.set("dx", it.x); s.set("dy", it.y); s.set("dz", it.z) }
            if (abs(node.roll) > 1e-9) s.set("roll", node.roll)
            node.name?.let { s.set("name", it) }
            s.set("links", node.links.toList())
            for ((from, to) in node.switches) s.set("switches.$from", to)
        }
        file.parentFile.mkdirs()
        yaml.save(file)
    }
}
