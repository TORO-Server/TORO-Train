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

    /** そのブロックのモデルを使っているか (モデルを消してよいかを調べるときに使う) */
    open fun usesBlockModel(model: String): Boolean = blockModelName.equals(model, ignoreCase = true)

    /** 前後の台車の間隔。0 以下なら車両の長さから決める */
    open val wheelDistance: Double get() = -1.0

    companion object {
        fun load(section: ConfigurationSection?): CartModel {
            if (section == null) return DefaultCartModel()
            return when (section.getString("type")) {
                "blocks" -> BlockCartModel(
                    section.getString("name") ?: "",
                    readVector(section, "offset"),
                    section.getDouble("yaw").toFloat(),
                    section.getDouble("length", -1.0),
                    Quaternionf(
                        section.getDouble("rotation.x").toFloat(), section.getDouble("rotation.y").toFloat(),
                        section.getDouble("rotation.z").toFloat(), section.getDouble("rotation.w", 1.0).toFloat(),
                    ),
                    if (section.contains("scale")) readVector(section, "scale") else Vector3f(1f),
                    section.getMapList("seats").takeIf { it.isNotEmpty() }?.map { m ->
                        Seat(
                            Vector3f((m["x"] as Number).toFloat(), (m["y"] as Number).toFloat(), (m["z"] as Number).toFloat()),
                            m["driver"] == true,
                        )
                    },
                    section.getDouble("wheel-distance", -1.0),
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
                "attachments" -> AttachmentCartModel.load(section)
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

/**
 * ワールドから写し取ったブロックのモデル。
 * 車体の座標での変換は T(offset) * Ry(yaw) * extraRotation * S(scale) * モデル
 */
class BlockCartModel(
    val name: String,
    val offset: Vector3f = Vector3f(),
    val yaw: Float = 0f,
    /** 0 より大きければ、モデルの大きさの代わりにこの長さを使う */
    val lengthOverride: Double = -1.0,
    /** yaw のあとにかける回転 (TrainCarts から引き継いだモデルの傾きなど) */
    val extraRotation: Quaternionf = Quaternionf(),
    val scale: Vector3f = Vector3f(1f),
    /** 決めてある座席 (車体の座標、足の位置)。null ならモデルの階段から決める */
    val fixedSeats: List<Seat>? = null,
    override val wheelDistance: Double = -1.0,
) : CartModel() {
    override val blockModelName: String get() = name

    fun rotation(): Quaternionf = Quaternionf().rotateY(Math.toRadians(yaw.toDouble()).toFloat()).mul(extraRotation)

    private val scaled get() = scale.x != 1f || scale.y != 1f || scale.z != 1f

    // モデルの座標で大きさを変える (縦横の倍率が同じなら正確、違えば近い形)
    private fun scaleTransform(t: PartTransform) = PartTransform(
        Vector3f(t.translation).mul(scale), Quaternionf(t.left), Vector3f(t.scale).mul(scale), Quaternionf(t.right),
    )

    private fun scaledBounds(lib: ModelLibrary): Pair<Vector3f, Vector3f>? {
        val (min, max) = lib.get(name)?.bounds() ?: return null
        if (!scaled && extraRotation.equals(Quaternionf(), 1e-6f)) return min to max
        val outMin = Vector3f(Float.MAX_VALUE)
        val outMax = Vector3f(-Float.MAX_VALUE)
        for (corner in 0 until 8) {
            val p = Vector3f(
                if (corner and 1 != 0) max.x else min.x,
                if (corner and 2 != 0) max.y else min.y,
                if (corner and 4 != 0) max.z else min.z,
            ).mul(scale)
            extraRotation.transform(p)
            outMin.min(p)
            outMax.max(p)
        }
        return outMin to outMax
    }

    override fun length(lib: ModelLibrary): Double {
        if (lengthOverride > 0) return lengthOverride
        val (min, max) = scaledBounds(lib) ?: return 1.0
        return (max.z - min.z).toDouble().coerceAtLeast(0.5)
    }

    override fun width(lib: ModelLibrary): Double {
        val (min, max) = scaledBounds(lib) ?: return 1.0
        return (max.x - min.x).toDouble().coerceAtLeast(0.5)
    }

    override fun height(lib: ModelLibrary): Double {
        val (_, max) = scaledBounds(lib) ?: return 1.0
        return (max.y + offset.y).toDouble().coerceAtLeast(0.5)
    }

    override fun parts(lib: ModelLibrary, flipSignItems: Boolean): List<Part> {
        val model = lib.get(name) ?: return DefaultCartModel(Material.RED_CONCRETE).parts(lib, flipSignItems)
        val base = model.parts(flipSignItems)
        val rot = rotation()
        if (!scaled && rot.equals(Quaternionf(), 1e-6f) && offset.lengthSquared() == 0f) return base
        return base.map { part ->
            val t = if (scaled) scaleTransform(part.transform) else part.transform
            when (part) {
                is BlockPart -> BlockPart(part.data, t.premultiply(rot, offset))
                is ItemPart -> ItemPart(part.item, part.display, t.premultiply(rot, offset))
                is TextPart -> TextPart(part.text, part.glowing, t.premultiply(rot, offset))
            }
        }
    }

    override fun seats(lib: ModelLibrary): List<Seat> {
        fixedSeats?.let { return it }
        val model = lib.get(name) ?: return listOf(Seat(Vector3f(0f, 0.3f, 0f), true))
        val rot = rotation()
        return model.seats().map { Seat(rot.transform(Vector3f(it.position).mul(scale)).add(offset), it.driver) }
    }

    override fun save(section: ConfigurationSection) {
        section.set("type", "blocks")
        section.set("name", name)
        writeVector(section, "offset", offset)
        section.set("yaw", yaw.toDouble())
        if (lengthOverride > 0) section.set("length", lengthOverride)
        if (!extraRotation.equals(Quaternionf(), 1e-6f)) {
            section.set("rotation.x", extraRotation.x.toDouble())
            section.set("rotation.y", extraRotation.y.toDouble())
            section.set("rotation.z", extraRotation.z.toDouble())
            section.set("rotation.w", extraRotation.w.toDouble())
        }
        if (scaled) writeVector(section, "scale", scale)
        fixedSeats?.let { seats ->
            section.set("seats", seats.map { mapOf("x" to it.position.x.toDouble(), "y" to it.position.y.toDouble(), "z" to it.position.z.toDouble(), "driver" to it.driver) })
        }
        if (wheelDistance > 0) section.set("wheel-distance", wheelDistance)
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
