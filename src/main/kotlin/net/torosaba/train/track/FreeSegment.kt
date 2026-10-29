package net.torosaba.train.track

import net.torosaba.train.util.BlockKey
import org.bukkit.World
import org.joml.Vector3d

/**
 * 線路から外れた列車が 1 tick で動いた道 (まっすぐな線分)。
 * 線路の上ではないので、ほかの区間とはつながらない。後ろの車両はこの道をたどる。
 */
class FreeSegment(override val world: World, val a: Vector3d, val b: Vector3d) : TrackSegment {
    override val length: Double = a.distance(b)
    override val id: String get() = "f:${a.x},${a.y},${a.z},${b.x},${b.y},${b.z}"

    override fun point(s: Double, out: Vector3d): Vector3d =
        if (length < 1e-9) out.set(a) else out.set(a).lerp(b, (s / length).coerceIn(0.0, 1.0))

    override fun tangent(s: Double, out: Vector3d): Vector3d =
        if (length < 1e-9) out.set(0.0, 0.0, 1.0) else out.set(b).sub(a).normalize()

    override fun triggerBlocks(from: Double, to: Double, out: MutableCollection<BlockKey>) {
        out += BlockKey.of(world, point(from))
        out += BlockKey.of(world, point(to))
    }

    override fun equals(other: Any?) = other is FreeSegment && other.a == a && other.b == b && other.world == world
    override fun hashCode() = a.hashCode() * 31 + b.hashCode()
    override fun toString() = String.format(java.util.Locale.ROOT, "free(%.2f, %.2f, %.2f -> %.2f, %.2f, %.2f)", a.x, a.y, a.z, b.x, b.y, b.z)

    companion object {
        fun parse(world: World, id: String): FreeSegment? {
            val v = id.removePrefix("f:").split(',').mapNotNull { it.toDoubleOrNull() }
            if (v.size != 6) return null
            return FreeSegment(world, Vector3d(v[0], v[1], v[2]), Vector3d(v[3], v[4], v[5]))
        }
    }
}
