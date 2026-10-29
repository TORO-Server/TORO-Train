package net.torosaba.train.model

import net.kyori.adventure.text.Component
import org.bukkit.block.data.BlockData
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * 部品の変換。T(translation) * left * S(scale) * right の順で、Display エンティティの変換と同じ形。
 * 車体の座標 (+Z が前、+Y が上、原点は線路の上の車体の中心) で表す。
 */
class PartTransform(
    val translation: Vector3f = Vector3f(),
    val left: Quaternionf = Quaternionf(),
    val scale: Vector3f = Vector3f(1f, 1f, 1f),
    val right: Quaternionf = Quaternionf(),
) {
    /** 回転 rot をかけてから offset だけ動かした変換。T(offset) * rot * this */
    fun transformed(rot: Quaternionf, offset: Vector3f? = null): Transformation {
        val t = rot.transform(Vector3f(translation))
        if (offset != null) t.add(offset)
        return Transformation(t, Quaternionf(rot).mul(left), Vector3f(scale), Quaternionf(right))
    }

    /** rot をかけてから offset だけ動かした新しい変換 */
    fun premultiply(rot: Quaternionf, offset: Vector3f? = null): PartTransform {
        val t = rot.transform(Vector3f(translation))
        if (offset != null) t.add(offset)
        return PartTransform(t, Quaternionf(rot).mul(left), Vector3f(scale), Quaternionf(right))
    }

    companion object {
        /** 原点が min の角にある大きさ size の直方体 (ブロックのディスプレイは 0 - 1 の立方体なので) */
        fun box(min: Vector3f, size: Vector3f) = PartTransform(Vector3f(min), Quaternionf(), Vector3f(size))
    }
}

/** 車両の見た目の部品 1 つ。Display エンティティ 1 つになる */
sealed class Part {
    abstract val transform: PartTransform
}

class BlockPart(val data: BlockData, override val transform: PartTransform) : Part()

class ItemPart(
    val item: ItemStack,
    val display: ItemDisplay.ItemDisplayTransform,
    override val transform: PartTransform,
) : Part()

class TextPart(
    val text: Component,
    val glowing: Boolean,
    override val transform: PartTransform,
) : Part()

/** 座席。position は座る人の足の位置 (車体の座標) */
class Seat(val position: Vector3f, val driver: Boolean)
