package net.torosaba.train.train

import net.torosaba.train.model.CartModel
import net.torosaba.train.render.CartVisual
import net.torosaba.train.util.BlockKey
import org.bukkit.entity.Player
import org.joml.Quaternionf
import org.joml.Vector3d
import java.util.UUID

/** 列車の車両 1 両 */
class Cart(val id: UUID, model: CartModel, val props: CartProperties = CartProperties()) {
    lateinit var train: Train

    var model: CartModel = model
        set(value) {
            field = value
            visual?.rebuild()
        }

    /** 前後の長さ。モデルから決め、列車に入れたときに設定する */
    var length = 1.25

    /** 車体の向きが進行方向と逆か (列車が折り返すと入れ替わる) */
    var flipped = false

    /** 先頭から、この車両の前端までの距離 */
    var frontDistance = 0.0

    /** 車体の中心と向き (毎 tick 計算する) */
    val position = Vector3d()
    val rotation = Quaternionf()
    var poseValid = false

    /** 車両が占めているブロック (看板の判定に使う) */
    val occupied = HashSet<BlockKey>()

    var visual: CartVisual? = null

    val index: Int get() = train.carts.indexOf(this)

    fun passengers(): List<Player> = visual?.passengers() ?: emptyList()
}
