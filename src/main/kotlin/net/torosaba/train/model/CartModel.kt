package net.torosaba.train.model

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Base64

/**
 * 車両の見た目と大きさ。部品と座席は車体の座標 (+Z が前、原点は線路の上の車体の中心) で返す。
 */
sealed class CartModel {
    /** 前後の長さ (連結したときの間隔に使う) */
    abstract fun length(lib: ModelLibrary): Double
    abstract fun width(lib: ModelLibrary): Double
    abstract fun height(lib: ModelLibrary): Double
    abstract fun parts(lib: ModelLibrary, flipSignItems: Boolean): List<Part>
    abstract fun seats(lib: ModelLibrary): List<Seat>
    abstract fun save(section: ConfigurationSection)
    abstract fun describe(): String

    /** 写し取ったブロックのモデルなら、その名前 */
    open val blockModelName: String? get() = null

    companion object {
        fun load(section: ConfigurationSection?): CartModel {
            if (section == null) return DefaultCartModel()
            return when (section.getString("type")) {
                "blocks" -> BlockCartModel(
                    section.getString("name") ?: "",
                    readVector(section, "offset"),
                    section.getDouble("yaw").toFloat(),
                    section.getDouble("length", -1.0),
                )
                "item" -> ItemCartModel(
                    section.getString("item")?.let { ItemStack.deserializeBytes(Base64.getDecoder().decode(it)) }
                        ?: ItemStack(Material.MINECART),
                    section.getDouble("scale", 1.0).toFloat(),
                    readVector(section, "offset"),
                    section.getDouble("yaw").toFloat(),
                    section.getDouble("length", 1.5),
                    section.getDouble("width", 1.0),
                    section.getDouble("height", 1.0),
                )
                else -> DefaultCartModel(
                    Material.matchMaterial(section.getString("material") ?: "") ?: Material.IRON_BLOCK,
                    section.getDouble("length", 1.25),
                )
            }
        }

        private fun readVector(section: ConfigurationSection, path: String) = Vector3f(
            section.getDouble("$path.x").toFloat(), section.getDouble("$path.y").toFloat(), section.getDouble("$path.z").toFloat(),
        )

        fun writeVector(section: ConfigurationSection, path: String, v: Vector3f) {
            section.set("$path.x", v.x.toDouble())
            section.set("$path.y", v.y.toDouble())
            section.set("$path.z", v.z.toDouble())
        }
    }
}

/** ブロックのディスプレイを組み合わせた、トロッコの形の既定のモデル */
class DefaultCartModel(val material: Material = Material.IRON_BLOCK, val length: Double = 1.25) : CartModel() {
    override fun length(lib: ModelLibrary) = length
    override fun width(lib: ModelLibrary) = 0.98
    override fun height(lib: ModelLibrary) = 0.9

    override fun parts(lib: ModelLibrary, flipSignItems: Boolean): List<Part> {
        val body = material.createBlockData()
        val wheel = Material.BLACK_CONCRETE.createBlockData()
        val l = (length - 0.1).toFloat()
        val parts = ArrayList<Part>()
        fun box(data: org.bukkit.block.data.BlockData, x0: Float, y0: Float, z0: Float, sx: Float, sy: Float, sz: Float) {
            parts += BlockPart(data, PartTransform.box(Vector3f(x0, y0, z0), Vector3f(sx, sy, sz)))
        }
        // 床と 4 枚の壁
        box(body, -0.45f, 0.22f, -l / 2, 0.9f, 0.08f, l)
        box(body, -0.45f, 0.3f, -l / 2, 0.06f, 0.5f, l)
        box(body, 0.39f, 0.3f, -l / 2, 0.06f, 0.5f, l)
        box(body, -0.39f, 0.3f, -l / 2, 0.78f, 0.5f, 0.06f)
        box(body, -0.39f, 0.3f, l / 2 - 0.06f, 0.78f, 0.5f, 0.06f)
        // 車輪
        for (x in floatArrayOf(-0.4f, 0.32f)) for (z in floatArrayOf(-l / 2 + 0.12f, l / 2 - 0.37f)) {
            box(wheel, x, 0.0f, z, 0.08f, 0.25f, 0.25f)
        }
        return parts
    }

    override fun seats(lib: ModelLibrary) = listOf(Seat(Vector3f(0f, 0.3f, 0f), true))

    override fun save(section: ConfigurationSection) {
        section.set("type", "default")
        section.set("material", material.name)
        section.set("length", length)
    }

    override fun describe() = "トロッコ (${material.name.lowercase()})"
}

/** ワールドから写し取ったブロックのモデル */
class BlockCartModel(
    val name: String,
    val offset: Vector3f = Vector3f(),
    val yaw: Float = 0f,
    /** 0 より大きければ、モデルの大きさの代わりにこの長さを使う */
    val lengthOverride: Double = -1.0,
) : CartModel() {
    override val blockModelName: String get() = name

    private fun rotation() = Quaternionf().rotateY(Math.toRadians(yaw.toDouble()).toFloat())

    override fun length(lib: ModelLibrary): Double {
        if (lengthOverride > 0) return lengthOverride
        val (min, max) = lib.get(name)?.bounds() ?: return 1.0
        return (max.z - min.z).toDouble().coerceAtLeast(0.5)
    }

    override fun width(lib: ModelLibrary): Double {
        val (min, max) = lib.get(name)?.bounds() ?: return 1.0
        return (max.x - min.x).toDouble().coerceAtLeast(0.5)
    }

    override fun height(lib: ModelLibrary): Double {
        val (_, max) = lib.get(name)?.bounds() ?: return 1.0
        return max.y.toDouble().coerceAtLeast(0.5)
    }

    override fun parts(lib: ModelLibrary, flipSignItems: Boolean): List<Part> {
        val model = lib.get(name) ?: return DefaultCartModel(Material.RED_CONCRETE).parts(lib, flipSignItems)
        if (yaw == 0f && offset.lengthSquared() == 0f) return model.parts(flipSignItems)
        val rot = rotation()
        return model.parts(flipSignItems).map { part ->
            when (part) {
                is BlockPart -> BlockPart(part.data, part.transform.premultiply(rot, offset))
                is ItemPart -> ItemPart(part.item, part.display, part.transform.premultiply(rot, offset))
                is TextPart -> TextPart(part.text, part.glowing, part.transform.premultiply(rot, offset))
            }
        }
    }

    override fun seats(lib: ModelLibrary): List<Seat> {
        val model = lib.get(name) ?: return listOf(Seat(Vector3f(0f, 0.3f, 0f), true))
        val rot = rotation()
        return model.seats().map { Seat(rot.transform(Vector3f(it.position)).add(offset), it.driver) }
    }

    override fun save(section: ConfigurationSection) {
        section.set("type", "blocks")
        section.set("name", name)
        writeVector(section, "offset", offset)
        section.set("yaw", yaw.toDouble())
        if (lengthOverride > 0) section.set("length", lengthOverride)
    }

    override fun describe() = "ブロックのモデル $name"
}

/** アイテム 1 つ (リソースパックのモデルなど) を車体にする */
class ItemCartModel(
    val item: ItemStack,
    val scale: Float = 1f,
    val offset: Vector3f = Vector3f(),
    val yaw: Float = 0f,
    val length: Double = 1.5,
    val width: Double = 1.0,
    val height: Double = 1.0,
) : CartModel() {
    override fun length(lib: ModelLibrary) = length
    override fun width(lib: ModelLibrary) = width
    override fun height(lib: ModelLibrary) = height

    override fun parts(lib: ModelLibrary, flipSignItems: Boolean): List<Part> {
        val rot = Quaternionf().rotateY(Math.toRadians(yaw.toDouble()).toFloat())
        val transform = PartTransform(Vector3f(offset).add(0f, (height / 2).toFloat(), 0f), rot, Vector3f(scale))
        return listOf(ItemPart(item.clone(), ItemDisplay.ItemDisplayTransform.FIXED, transform))
    }

    override fun seats(lib: ModelLibrary) = listOf(Seat(Vector3f(0f, (height * 0.3).toFloat(), 0f), true))

    override fun save(section: ConfigurationSection) {
        section.set("type", "item")
        section.set("item", Base64.getEncoder().encodeToString(item.serializeAsBytes()))
        section.set("scale", scale.toDouble())
        writeVector(section, "offset", offset)
        section.set("yaw", yaw.toDouble())
        section.set("length", length)
        section.set("width", width)
        section.set("height", height)
    }

    override fun describe() = "アイテム ${item.type.name.lowercase()}"
}
