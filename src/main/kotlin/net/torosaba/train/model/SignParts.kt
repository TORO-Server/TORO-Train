package net.torosaba.train.model

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.torosaba.train.util.Text
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Rotatable
import org.bukkit.block.data.type.HangingSign
import org.bukkit.block.data.type.WallHangingSign
import org.bukkit.block.data.type.WallSign
import org.bukkit.block.sign.Side
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min

/**
 * 看板を Display エンティティで描く部品を作る。
 *
 * ブロックのディスプレイは看板の板も文字も描かないので、自分で描く。
 * MarumaSign の形式の看板は、看板の文字をデータに持つ看板のアイテムをアイテムのディスプレイに入れる。
 * MarumaSign はそのアイテムを看板と同じ画像・3D モデルで描くので、看板に書かれた位置・大きさ・回転を
 * ディスプレイの変換で再現すると、ワールドに置いた看板と同じ見た目になる。
 */
object SignParts {
    /** MarumaSign の看板の形式: URL|X|Y|Z|横幅|高さ|Z軸回転|Y軸回転|X軸回転 */
    class MarumaSpec(
        val url: String,
        val x: Float, val y: Float, val z: Float,
        val scaleX: Float, val scaleY: Float,
        val roll: Float, val yaw: Float, val pitch: Float,
    )

    private const val MAX_VALUE = 1000f

    /** MarumaSign と同じ規則で看板の文字列を読む。MarumaSign の看板でなければ null */
    fun parseMaruma(text: String): MarumaSpec? {
        if (text.count { it == '|' } < 8) return null
        val p = text.split('|')
        if (p.size < 9) return null
        val values = FloatArray(8)
        for (i in 0 until 8) {
            val v = p[i + 1].toFloatOrNull() ?: return null
            if (v.isNaN() || v.isInfinite()) return null
            values[i] = max(-MAX_VALUE, min(MAX_VALUE, v))
        }
        return MarumaSpec(p[0], values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
    }

    /** 表と裏の 8 行をつなげた文字列 (MarumaSign が看板として読む文字列) */
    fun joinedText(sign: Sign): String =
        (sign.getSide(Side.FRONT).lines() + sign.getSide(Side.BACK).lines()).joinToString("") { Text.plain(it) }

    private val ROTATIONS = listOf(
        BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
        BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
        BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
        BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST,
    )

    /** 看板の向き (度)。ゲームの看板の描画と同じで、南向きが 0、西向きが 90 */
    private fun yawOf(data: org.bukkit.block.data.BlockData): Float = when (data) {
        is Rotatable -> ROTATIONS.indexOf(data.rotation).coerceAtLeast(0) * 22.5f
        is Directional -> when (data.facing) {
            BlockFace.SOUTH -> 0f
            BlockFace.WEST -> 90f
            BlockFace.NORTH -> 180f
            BlockFace.EAST -> 270f
            else -> 0f
        }
        else -> 0f
    }

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    /**
     * 看板 1 つ分の部品を作る。
     *
     * @param center 看板のブロックの中心 (底面ではなく立方体の中心) のモデル上の座標
     */
    fun build(sign: Sign, center: Vector3f, flipItem: Boolean): List<Part> {
        val data = sign.blockData
        val hanging = data is HangingSign || data is WallHangingSign
        val wall = data is WallSign
        val yaw = yawOf(data)

        // 吊り看板は MarumaSign が描かないので、ふつうの看板として描く
        if (!hanging) {
            val spec = parseMaruma(joinedText(sign))
            if (spec != null) return listOf(marumaPart(sign, spec, center, yaw, wall, flipItem))
        }
        return vanillaParts(sign, center, yaw, wall, hanging)
    }

    /**
     * MarumaSign の看板を、アイテムのディスプレイ 1 つにする。
     *
     * MarumaSign は、ワールドの看板ではブロックの中心から見て
     *   Ry(-signYaw - 180) * (R_spec * Ry(180) * S * q + (x, y, z) + 壁掛けのずらし)
     * の位置に、アイテムでは 0 - 1 の立方体の中央に (q の位置に) 描く。
     * アイテムのディスプレイは描く前にアイテムを Y 軸で 180 度回すので、ディスプレイの変換を
     *   Ry(-signYaw - 180) * T((x, y, z) + 壁掛けのずらし) * R_spec * S
     * にすると、ワールドの看板と同じ位置・向き・大きさになる。
     */
    private fun marumaPart(sign: Sign, spec: MarumaSpec, center: Vector3f, signYaw: Float, wall: Boolean, flipItem: Boolean): Part =
        ItemPart(signItem(sign), ItemDisplay.ItemDisplayTransform.NONE, marumaTransform(spec, center, signYaw, wall, flipItem))

    /** MarumaSign の看板のアイテムのディスプレイの変換。center は看板のブロックの中心 */
    fun marumaTransform(spec: MarumaSpec, center: Vector3f, signYaw: Float, wall: Boolean, flipItem: Boolean): PartTransform {
        val signRot = Quaternionf().rotateY(rad(-signYaw - 180f))
        // MarumaSign の rotate(): X 軸 (pitch)、Y 軸 (-yaw)、Z 軸 (-roll) の順に回す
        val specRot = Quaternionf().rotateZ(rad(-spec.roll)).rotateY(rad(-spec.yaw)).rotateX(rad(spec.pitch))
        if (!flipItem) specRot.rotateY(Math.PI.toFloat())
        // 壁掛け看板は壁の方へ寄せて描かれる (画像は 0.4375、3D モデルは奥行きで変わるが画像と同じにする)
        val move = Vector3f(spec.x, spec.y, spec.z + if (wall) 0.4375f else 0f)
        val translation = signRot.transform(move).add(center)
        return PartTransform(
            translation,
            Quaternionf(signRot).mul(specRot),
            Vector3f(spec.scaleX, spec.scaleY, spec.scaleX),
        )
    }

    /**
     * 看板の文字をデータに持つ立て看板のアイテム。MarumaSign は立て看板のアイテムだけを看板として描くので、
     * 元が壁掛けの看板でも樫の看板にする。
     *
     * MarumaSign はアイテムの minecraft:block_entity_data の front_text と back_text を読む。
     * Paper の BlockStateMeta で作ると、文字が別の部品 (minecraft:sign_text_front など) に入って読まれないので、
     * MarumaSign の動作確認と同じ書き方のアイテムを、コマンドと同じ文字列から作る。
     * MarumaSign が使うのは文字だけなので、各行は文字だけ (装飾なし) で入れる。
     */
    fun signItem(sign: Sign): ItemStack {
        fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun messages(side: Side) = sign.getSide(side).lines().joinToString(",") { quote(Text.plain(it)) }
        val hasBack = sign.getSide(Side.BACK).lines().any { Text.plain(it).isNotEmpty() }
        val data = buildString {
            append("{id:\"minecraft:sign\",front_text:{messages:[").append(messages(Side.FRONT)).append("]}")
            if (hasBack) append(",back_text:{messages:[").append(messages(Side.BACK)).append("]}")
            append("}")
        }
        return runCatching {
            org.bukkit.Bukkit.getItemFactory().createItemStack("minecraft:oak_sign[minecraft:block_entity_data=$data]")
        }.getOrElse { legacySignItem(sign) }
    }

    // 文字列から作れなかったとき (古い Paper など) の作り方
    private fun legacySignItem(sign: Sign): ItemStack {
        val item = ItemStack(Material.OAK_SIGN)
        val meta = item.itemMeta as BlockStateMeta
        val state = Material.OAK_SIGN.createBlockData().createBlockState() as Sign
        for (side in Side.entries) {
            val from = sign.getSide(side)
            val to = state.getSide(side)
            from.lines().forEachIndexed { i, line -> to.line(i, line) }
            to.color = from.color
            to.isGlowingText = from.isGlowingText
        }
        state.isWaxed = true
        meta.blockState = state
        item.itemMeta = meta
        return item
    }

    // ゲームの看板の形 (SignRenderer と HangingSignRenderer の値から求めたもの)。座標は看板のブロックの底面の中心から
    private class Shape(val board: Vector3f, val boardSize: Vector3f, val post: Boolean, val textScale: Float)

    private val STANDING = Shape(Vector3f(0f, 0.8333f, 0f), Vector3f(1f, 0.5f, 0.0833f), true, 0.2778f)
    private val WALL = Shape(Vector3f(0f, 0.5208f, -0.4375f), Vector3f(1f, 0.5f, 0.0833f), false, 0.2778f)
    private val HANGING = Shape(Vector3f(0f, 0.625f, 0f), Vector3f(0.875f, 0.625f, 0.125f), false, 0.32f)

    private fun vanillaParts(sign: Sign, center: Vector3f, yaw: Float, wall: Boolean, hanging: Boolean): List<Part> {
        val shape = when {
            hanging -> HANGING
            wall -> WALL
            else -> STANDING
        }
        val parts = ArrayList<Part>()
        val rot = Quaternionf().rotateY(rad(-yaw))
        val bottom = Vector3f(center).sub(0f, 0.5f, 0f)
        val planks = planksOf(sign.type).createBlockData()

        fun box(c: Vector3f, size: Vector3f) {
            val min = Vector3f(c).sub(Vector3f(size).mul(0.5f))
            parts += BlockPart(planks, PartTransform.box(min, size).premultiply(rot, bottom))
        }
        box(shape.board, shape.boardSize)
        if (shape.post) box(Vector3f(0f, 0.2917f, 0f), Vector3f(0.0833f, 0.5833f, 0.0833f))

        for (side in Side.entries) {
            val s = sign.getSide(side)
            if (s.lines().all { Text.plain(it).isBlank() }) continue
            val color = TextColor.color(s.color?.color?.asRGB() ?: 0)
            val text = Component.join(
                net.kyori.adventure.text.JoinConfiguration.newlines(),
                s.lines().map { it.colorIfAbsent(color) },
            )
            // 文字のディスプレイは文字の下端が原点に来るので、4 行分の半分だけ下げる
            val lineHeight = 10 * 0.025f * shape.textScale
            val faceZ = shape.boardSize.z / 2 + 0.005f
            val local = Vector3f(shape.board).add(0f, -lineHeight * 2, faceZ)
            val sideRot = Quaternionf()
            if (side == Side.BACK) {
                local.set(shape.board.x, shape.board.y - lineHeight * 2, shape.board.z - faceZ)
                sideRot.rotateY(Math.PI.toFloat())
            }
            val transform = PartTransform(local, sideRot, Vector3f(shape.textScale)).premultiply(rot, bottom)
            parts += TextPart(text, s.isGlowingText, transform)
        }
        return parts
    }

    private fun planksOf(type: Material): Material {
        val wood = type.name
            .removeSuffix("_WALL_HANGING_SIGN").removeSuffix("_HANGING_SIGN")
            .removeSuffix("_WALL_SIGN").removeSuffix("_SIGN")
        return Material.matchMaterial("${wood}_PLANKS") ?: Material.OAK_PLANKS
    }
}
