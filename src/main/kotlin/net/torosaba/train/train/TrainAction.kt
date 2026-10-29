package net.torosaba.train.train

import org.bukkit.block.BlockFace
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 列車が今している動作 (駅に止まる、待つ、発車する など)。速さを決め、終わったら次の動作に移る。
 */
abstract class TrainAction {
    /** 動作が終わったら移る動作 */
    var then: TrainAction? = null

    /** 動作を始めたときに 1 回呼ぶ */
    open fun start(train: Train) {}

    /** 毎 tick、列車を動かす前に呼ぶ。速さを決める。終わったら true */
    abstract fun tick(train: Train): Boolean

    /** 列車が動いたあとに呼ぶ */
    open fun moved(train: Train, distance: Double) {}

    /** 摩擦や重力、運転操作を止めるか */
    open val holdsSpeed: Boolean get() = true

    open fun describe(): String = javaClass.simpleName

    fun andThen(next: TrainAction?): TrainAction {
        var last = this
        while (last.then != null) last = last.then!!
        last.then = next
        return this
    }
}

/** remaining だけ進んだところで止まる。減速度は列車の設定を使い、間に合わなければ強くかける */
class StopAfter(var remaining: Double) : TrainAction() {
    override fun tick(train: Train): Boolean {
        if (remaining <= 0.005) {
            train.speed = 0.0
            return true
        }
        val brake = sqrt(2 * train.props.deceleration * remaining)
        // 止まりかけでも、目標まではゆっくり進む
        train.speed = max(min(train.speed, brake), min(remaining, 0.02))
        return false
    }

    override fun moved(train: Train, distance: Double) {
        remaining -= distance
    }

    override fun describe() = String.format("停車 (あと %.1f ブロック)", maxOf(remaining, 0.0))
}

/** ticks だけ止まって待つ。until を渡すと、時間が過ぎたあともそれが true になるまで待つ */
class WaitAction(var ticks: Int, private val label: String = "待機", private val until: (() -> Boolean)? = null) : TrainAction() {
    override fun tick(train: Train): Boolean {
        train.speed = 0.0
        if (ticks > 0) {
            ticks--
            return false
        }
        return until?.invoke() ?: true
    }

    override fun describe() = if (ticks > 0) "$label (あと ${ticks / 20} 秒)" else label
}

/** 発車する向き */
sealed interface LaunchDirection {
    data object Continue : LaunchDirection
    data object Reverse : LaunchDirection
    class Compass(val face: BlockFace) : LaunchDirection

    companion object {
        fun parse(text: String?): LaunchDirection? = when (text?.lowercase()) {
            null, "", "continue", "c", "forward", "f" -> Continue
            "reverse", "r", "back", "b" -> Reverse
            "north", "n" -> Compass(BlockFace.NORTH)
            "east", "e" -> Compass(BlockFace.EAST)
            "south", "s" -> Compass(BlockFace.SOUTH)
            "west", "w" -> Compass(BlockFace.WEST)
            "up", "u" -> Compass(BlockFace.UP)
            "down", "d" -> Compass(BlockFace.DOWN)
            else -> null
        }
    }
}

/** 向きを決めて、target の速さまで加速する */
class LaunchAction(val target: Double, private val accel: Double?, private val direction: LaunchDirection = LaunchDirection.Continue) : TrainAction() {
    override fun start(train: Train) {
        when (direction) {
            LaunchDirection.Continue -> {}
            LaunchDirection.Reverse -> if (train.speed < 1e-6) train.reverse()
            is LaunchDirection.Compass -> {
                val t = train.headTangent()
                val f = direction.face
                if (t.x * f.modX + t.y * f.modY + t.z * f.modZ < 0) train.reverse()
            }
        }
    }

    override fun tick(train: Train): Boolean {
        val a = accel ?: train.props.acceleration
        if (train.speed < target) {
            train.speed = min(target, train.speed + a)
        } else {
            train.speed = max(target, train.speed - train.props.deceleration)
        }
        return kotlin.math.abs(train.speed - target) < 1e-6
    }

    override fun describe() = "発車"
}
