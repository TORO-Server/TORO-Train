package net.torosaba.train.model

import net.torosaba.train.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Base64

/** 組み合わせて描く部品 1 つ。位置と回転は車体の座標で、拡大はその部品の座標で表す */
sealed class Piece(val position: Vector3f, val rotation: Quaternionf, val scale: Vector3f) {
    /** ブロック 1 つ。TrainCarts と同じく、横と前後の中央、いちばん下が position になる */
    class Block(val data: String, position: Vector3f, rotation: Quaternionf, scale: Vector3f) : Piece(position, rotation, scale)

    class Item(val item: ItemStack, val display: ItemDisplay.ItemDisplayTransform, position: Vector3f, rotation: Quaternionf, scale: Vector3f) :
        Piece(position, rotation, scale)

    /** 文字。& の色記号を使える */
    class Label(val text: String, position: Vector3f, rotation: Quaternionf, scale: Vector3f) : Piece(position, rotation, scale)

    /** 保存してあるブロックのモデル */
    class Blocks(val model: String, position: Vector3f, rotation: Quaternionf, scale: Vector3f) : Piece(position, rotation, scale)

    /** トロッコの形 */
    class Minecart(val material: Material, position: Vector3f, rotation: Quaternionf, scale: Vector3f) : Piece(position, rotation, scale)
}

/**
 * いくつもの部品を組み合わせた車体 (TrainCarts の取り付けの木を引き継いだもの)。
 * 部品はあらかじめ車体の座標に直してあるので、親子の関係は持たない。
 */
class AttachmentCartModel(
    val pieces: List<Piece>,
    val fixedSeats: List<Seat>,
    /** 0 より大きければ、部品の大きさの代わりにこの長さを使う */
    val lengthOverride: Double = -1.0,
    override val wheelDistance: Double = -1.0,
) : CartModel() {
    private class Bounds(val min: Vector3f, val max: Vector3f)

    private var cached: Bounds? = null

    private fun bounds(lib: ModelLibrary): Bounds {
        cached?.let { return it }
        val min = Vector3f(Float.MAX_VALUE)
        val max = Vector3f(-Float.MAX_VALUE)
        fun include(piece: Piece, lo: Vector3f, hi: Vector3f) {
            for (corner in 0 until 8) {
                val p = Vector3f(
                    if (corner and 1 != 0) hi.x else lo.x,
                    if (corner and 2 != 0) hi.y else lo.y,
                    if (corner and 4 != 0) hi.z else lo.z,
                ).mul(piece.scale)
                piece.rotation.transform(p).add(piece.position)
                min.min(p)
                max.max(p)
            }
        }
        for (piece in pieces) {
            when (piece) {
                is Piece.Block -> include(piece, Vector3f(-0.5f, 0f, -0.5f), Vector3f(0.5f, 1f, 0.5f))
                is Piece.Item -> include(piece, Vector3f(-0.5f), Vector3f(0.5f))
                is Piece.Label -> include(piece, Vector3f(), Vector3f())
                is Piece.Minecart -> include(piece, Vector3f(-0.49f, 0f, -0.625f), Vector3f(0.49f, 0.9f, 0.625f))
                is Piece.Blocks -> lib.get(piece.model)?.bounds()?.let { (lo, hi) -> include(piece, lo, hi) }
            }
        }
        if (min.x > max.x) {
            min.set(-0.5f, 0f, -0.5f)
            max.set(0.5f, 1f, 0.5f)
        }
        return Bounds(min, max).also { cached = it }
    }

    override fun length(lib: ModelLibrary): Double {
        if (lengthOverride > 0) return lengthOverride
        val b = bounds(lib)
        return (b.max.z - b.min.z).toDouble().coerceAtLeast(1.0)
    }

    override fun width(lib: ModelLibrary): Double {
        val b = bounds(lib)
        return (b.max.x - b.min.x).toDouble().coerceAtLeast(0.5)
    }

    override fun height(lib: ModelLibrary): Double = bounds(lib).max.y.toDouble().coerceAtLeast(0.5)

    override fun usesBlockModel(model: String) = pieces.any { it is Piece.Blocks && it.model.equals(model, ignoreCase = true) }

    override fun parts(lib: ModelLibrary, flipSignItems: Boolean): List<Part> {
        val out = ArrayList<Part>()
        for (piece in pieces) {
            when (piece) {
                is Piece.Block -> {
                    val data = runCatching { Bukkit.createBlockData(piece.data) }.getOrNull() ?: continue
                    val corner = Vector3f(-0.5f * piece.scale.x, 0f, -0.5f * piece.scale.z)
                    val at = piece.rotation.transform(corner).add(piece.position)
                    out += BlockPart(data, PartTransform(at, Quaternionf(piece.rotation), Vector3f(piece.scale)))
                }
                is Piece.Item -> out += ItemPart(
                    piece.item.clone(), piece.display, PartTransform(Vector3f(piece.position), Quaternionf(piece.rotation), Vector3f(piece.scale)),
                )
                is Piece.Label -> out += TextPart(
                    Text.color(piece.text), false, PartTransform(Vector3f(piece.position), Quaternionf(piece.rotation), Vector3f(piece.scale)),
                )
                is Piece.Blocks -> lib.get(piece.model)?.parts(flipSignItems)?.forEach { out += posed(it, piece) }
                is Piece.Minecart -> DefaultCartModel(piece.material).parts(lib, flipSignItems).forEach { out += posed(it, piece) }
            }
        }
        return out
    }

    // 部品の座標で描いた部品を、車体の座標へ移す
    private fun posed(part: Part, piece: Piece): Part {
        val t = part.transform
        val scaled = PartTransform(Vector3f(t.translation).mul(piece.scale), Quaternionf(t.left), Vector3f(t.scale).mul(piece.scale), Quaternionf(t.right))
        val moved = scaled.premultiply(piece.rotation, piece.position)
        return when (part) {
            is BlockPart -> BlockPart(part.data, moved)
            is ItemPart -> ItemPart(part.item, part.display, moved)
            is TextPart -> TextPart(part.text, part.glowing, moved)
        }
    }

    override fun seats(lib: ModelLibrary) = fixedSeats

    override fun save(section: ConfigurationSection) {
        section.set("type", "attachments")
        if (lengthOverride > 0) section.set("length", lengthOverride)
        if (wheelDistance > 0) section.set("wheel-distance", wheelDistance)
        section.set("seats", fixedSeats.map { mapOf("x" to it.position.x.toDouble(), "y" to it.position.y.toDouble(), "z" to it.position.z.toDouble(), "driver" to it.driver) })
        section.set("pieces", pieces.map(::pieceMap))
    }

    private fun pieceMap(piece: Piece): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        m["position"] = listOf(piece.position.x.toDouble(), piece.position.y.toDouble(), piece.position.z.toDouble())
        m["rotation"] = listOf(piece.rotation.x.toDouble(), piece.rotation.y.toDouble(), piece.rotation.z.toDouble(), piece.rotation.w.toDouble())
        m["scale"] = listOf(piece.scale.x.toDouble(), piece.scale.y.toDouble(), piece.scale.z.toDouble())
        when (piece) {
            is Piece.Block -> {
                m["kind"] = "block"
                m["data"] = piece.data
            }
            is Piece.Item -> {
                m["kind"] = "item"
                m["item"] = Base64.getEncoder().encodeToString(piece.item.serializeAsBytes())
                m["display"] = piece.display.name
            }
            is Piece.Label -> {
                m["kind"] = "text"
                m["text"] = piece.text
            }
            is Piece.Blocks -> {
                m["kind"] = "blocks"
                m["model"] = piece.model
            }
            is Piece.Minecart -> {
                m["kind"] = "minecart"
                m["material"] = piece.material.name
            }
        }
        return m
    }

    override fun describe() = "取り付けの組み合わせ (${pieces.size} 個)"

    fun withLength(length: Double) = AttachmentCartModel(pieces, fixedSeats, length, wheelDistance)

    companion object {
        fun load(section: ConfigurationSection): AttachmentCartModel = AttachmentCartModel(
            section.getMapList("pieces").mapNotNull(::readPiece),
            section.getMapList("seats").map { m ->
                Seat(Vector3f((m["x"] as Number).toFloat(), (m["y"] as Number).toFloat(), (m["z"] as Number).toFloat()), m["driver"] == true)
            },
            section.getDouble("length", -1.0),
            section.getDouble("wheel-distance", -1.0),
        )

        private fun floats(value: Any?, size: Int): List<Float>? =
            (value as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.size == size }

        private fun readPiece(m: Map<*, *>): Piece? {
            val p = floats(m["position"], 3) ?: return null
            val r = floats(m["rotation"], 4) ?: return null
            val s = floats(m["scale"], 3) ?: return null
            val position = Vector3f(p[0], p[1], p[2])
            val rotation = Quaternionf(r[0], r[1], r[2], r[3])
            val scale = Vector3f(s[0], s[1], s[2])
            return when (m["kind"]) {
                "block" -> Piece.Block(m["data"] as? String ?: return null, position, rotation, scale)
                "item" -> Piece.Item(
                    ItemStack.deserializeBytes(Base64.getDecoder().decode(m["item"] as? String ?: return null)),
                    runCatching { ItemDisplay.ItemDisplayTransform.valueOf(m["display"] as String) }.getOrDefault(ItemDisplay.ItemDisplayTransform.HEAD),
                    position, rotation, scale,
                )
                "text" -> Piece.Label(m["text"] as? String ?: return null, position, rotation, scale)
                "blocks" -> Piece.Blocks(m["model"] as? String ?: return null, position, rotation, scale)
                "minecart" -> Piece.Minecart(Material.matchMaterial(m["material"] as? String ?: "") ?: Material.IRON_BLOCK, position, rotation, scale)
                else -> null
            }
        }
    }
}
