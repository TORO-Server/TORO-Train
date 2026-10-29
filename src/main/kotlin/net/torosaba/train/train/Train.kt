package net.torosaba.train.train

import net.torosaba.train.track.RailSegment
import net.torosaba.train.track.TrackPath
import net.torosaba.train.util.BlockKey
import org.bukkit.World
import org.bukkit.entity.Player
import org.joml.Quaternionf
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.min

/**
 * 列車。車両の並び (先頭から順) と、通ってきた線路 (TrackPath) を持つ。
 * 列車はいつも先頭の方へ進み、後ろへ動くときは向きを反対にする (先頭と最後尾を入れ替える)。
 */
class Train(val id: UUID, var name: String, var path: TrackPath, val props: TrainProperties = TrainProperties()) {
    val carts = ArrayList<Cart>()

    /** 速さ (ブロック/tick)。いつも 0 以上で、先頭の方へ進む */
    var speed = 0.0

    /** 前の列車などで止められる前の速さ。空いたらこの速さまで戻す */
    var resumeSpeed = 0.0

    var action: TrainAction? = null
        set(value) {
            field = value
            value?.start(this)
        }

    /** マスコンの段 (正: 力行 P1-P5、負: ブレーキ B1-B7、-8: 非常) */
    var notch = 0

    /** これから通る看板を何枚無視するか */
    var skipSigns = 0

    /** 車両の間の隙間 */
    var gap = 0.3

    /** 最後に動いた道のり。車両の向きの計算や判定に使う */
    var lastMoved = 0.0

    /** 列車が止まっている理由 (情報の表示用) */
    var blockedBy: String? = null

    /** 列車全体が占めているブロック */
    val occupied = HashSet<BlockKey>()

    val world: World get() = path.world

    var removed = false

    fun length(): Double = if (carts.isEmpty()) 0.0 else carts.last().frontDistance + carts.last().length

    fun add(cart: Cart, lengthOf: (Cart) -> Double) {
        cart.train = this
        cart.length = lengthOf(cart)
        carts += cart
        layout()
    }

    /** 車両の位置 (先頭からの距離) を並べ直す */
    fun layout() {
        var d = 0.0
        for (cart in carts) {
            cart.frontDistance = d
            d += cart.length + gap
        }
    }

    fun headTangent(): Vector3d = path.locate(0.0).tangent()

    /** 向きを反対にする。最後尾が先頭になり、車両の並びも逆になる */
    fun reverse() {
        path.reverse(length())
        carts.reverse()
        for (cart in carts) cart.flipped = !cart.flipped
        layout()
        // 止まる位置などは向きが変わると意味がなくなる
        if (action is StopAfter) action = null
        resumeSpeed = 0.0
    }

    /** 車両の中心と向きを、線路の上の位置から求める。前後の台車の位置を結んだ向きにする */
    fun computePoses() {
        val pf = Vector3d()
        val pr = Vector3d()
        for (cart in carts) {
            val front = cart.frontDistance
            val inset = min(1.0, cart.length * 0.2)
            val bf = front + inset
            val br = front + cart.length - inset
            path.locate(bf).point(pf)
            path.locate(br).point(pr)
            cart.position.set(pf).add(pr).mul(0.5)
            val forward = Vector3d(pf).sub(pr)
            if (forward.lengthSquared() < 1e-6) path.locate(front + cart.length / 2).tangent(forward)
            forward.normalize()
            var roll = path.locate(front + cart.length / 2).roll()
            if (cart.flipped) {
                forward.negate()
                roll = -roll
            }
            val yaw = atan2(forward.x, forward.z)
            val pitch = -asin(forward.y.coerceIn(-1.0, 1.0))
            cart.rotation.identity()
                .rotateY(yaw.toFloat())
                .rotateX(pitch.toFloat())
                .rotateZ(Math.toRadians(roll).toFloat())
            cart.poseValid = true
        }
    }

    /**
     * 先頭から見た、ブロックの中心までの道のり (前なら正、後ろなら負)。
     * 列車が通ってきた道の中になければ null。
     */
    fun distanceToBlock(key: BlockKey): Double? {
        var start = -path.headOffset
        for (i in path.segments.indices.reversed()) {
            val seg = path.segments[i]
            val rail = seg.segment as? RailSegment
            if (rail != null && rail.x == key.x && rail.y == key.y && rail.z == key.z) return start + seg.length / 2
            start -= if (i > 0) path.segments[i - 1].length else 0.0
        }
        return null
    }

    fun passengers(): List<Player> = carts.flatMap { it.passengers() }

    fun isOwner(player: Player): Boolean = props.owners.isEmpty() || player.uniqueId in props.owners

    fun hasTag(tag: String): Boolean =
        tag in props.tags || carts.any { tag in it.props.tags }

    fun displayName(): String = props.displayName ?: name

    /** 行き先に着いたとき。経路があれば次の行き先にする */
    fun arrive(name: String) {
        if (!props.destination.equals(name, ignoreCase = true)) return
        val route = props.route
        if (route.isEmpty()) {
            props.destination = null
            return
        }
        val index = route.indexOfFirst { it.equals(name, ignoreCase = true) }
        props.destination = route[(index + 1).mod(route.size)]
    }

    fun tickNotchSpeed(): Double {
        val p = props
        return when {
            notch > 0 -> p.acceleration * notch / 5.0
            notch < 0 -> -p.deceleration * (if (notch <= -8) 2.0 else -notch / 7.0)
            else -> 0.0
        }
    }

    fun headRotation(): Quaternionf = carts.firstOrNull()?.rotation ?: Quaternionf()
}
