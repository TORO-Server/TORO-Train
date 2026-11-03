package net.torosaba.train.compat

import org.bukkit.entity.ItemDisplay.ItemDisplayTransform
import org.joml.Vector3f
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.InflaterInputStream

/** TrainCarts の保存形式のうち、Bukkit のサーバーがなくても読める部分 */
object TrainCartsFormat {
    /** destinations.dat の行き先 1 つ */
    data class Destination(val name: String, val world: String, val x: Int, val y: Int, val z: Int)

    // 分岐器を表すときに、行き先の名前の代わりに入っている文字列
    private const val SWITCHER_NAME = "::traincarts::switchable::"

    fun readDestinations(file: File): List<Destination> = file.inputStream().use { readDestinations(it) }

    /**
     * destinations.dat (Deflate で圧縮したデータ) を読む。先頭に場所の一覧があり、その後ろのつながりの情報は要らない。
     * 1 つの場所は「名前 (改行で区切って複数)、ワールド名、x, y, z」で、名前が空の場所は分岐器
     */
    fun readDestinations(stream: InputStream): List<Destination> {
        val data = DataInputStream(InflaterInputStream(stream))
        val out = ArrayList<Destination>()
        try {
            val count = data.readInt()
            if (count !in 0..100_000) return out
            for (i in 0 until count) {
                val names = data.readUTF()
                val world = data.readUTF()
                val x = data.readInt()
                val y = data.readInt()
                val z = data.readInt()
                for (name in names.split('\n')) {
                    if (name.isNotEmpty() && name != SWITCHER_NAME) out += Destination(name, world, x, y, z)
                }
            }
        } catch (_: EOFException) {
            // 途中で切れていても、読めたところまでは使う
        }
        return out
    }

    /**
     * ITEM の取り付けの描き方。TrainCarts の防具立ての表示をディスプレイで描くときと同じ大きさとずらし方にする
     * @param scale 決まった大きさ。null なら取り付けの sizeX などを使う
     * @param offset 取り付けの座標でのずらし
     */
    class ItemSpec(val display: ItemDisplayTransform, val scale: Float?, val offset: Vector3f)

    /** position.transform の値を読む。書いていなければ防具立ての頭 (TrainCarts の既定) */
    fun itemSpec(transform: String?): ItemSpec {
        val name = transform?.trim()?.uppercase(Locale.ROOT).orEmpty()
        val key = name.removePrefix("HYBRID_").removePrefix("ARMORSTAND_")
        return when (key) {
            "", "HEAD", "CHEST", "LEGS", "FEET" -> ItemSpec(ItemDisplayTransform.HEAD, 0.625f, Vector3f(0f, 0.25f, 0f))
            "SMALL_HEAD", "HEAD_SMALL", "SMALL_CHEST", "SMALL_LEGS", "SMALL_FEET" -> ItemSpec(ItemDisplayTransform.HEAD, 0.4375f, Vector3f(0f, 0.175f, 0f))
            "RIGHT_HAND" -> ItemSpec(ItemDisplayTransform.THIRDPERSON_RIGHTHAND, 1f, Vector3f(-0.0625f, 0.12575f, 0.625f))
            "LEFT_HAND" -> ItemSpec(ItemDisplayTransform.THIRDPERSON_LEFTHAND, 1f, Vector3f(0.0625f, 0.12575f, 0.625f))
            "SMALL_RIGHT_HAND", "RIGHT_HAND_SMALL" -> ItemSpec(ItemDisplayTransform.THIRDPERSON_RIGHTHAND, 0.5f, Vector3f(-0.06775f, 0.259f, 0.31225f))
            "SMALL_LEFT_HAND", "LEFT_HAND_SMALL" -> ItemSpec(ItemDisplayTransform.THIRDPERSON_LEFTHAND, 0.5f, Vector3f(0.06775f, 0.259f, 0.31225f))
            "DISPLAY_HEAD_SMALL" -> ItemSpec(ItemDisplayTransform.HEAD, 0.4375f, Vector3f())
            "DISPLAY_RIGHT_HAND_SMALL" -> ItemSpec(ItemDisplayTransform.THIRDPERSON_RIGHTHAND, 0.5f, Vector3f())
            else -> if (name.startsWith("HYBRID_DISPLAY_")) {
                // HYBRID の表示は決まった大きさ、DISPLAY の表示は取り付けの大きさ
                ItemSpec(displayMode(key.removePrefix("DISPLAY_")), if (key == "DISPLAY_HEAD") 0.625f else 1f, Vector3f())
            } else {
                ItemSpec(displayMode(key.removePrefix("DISPLAY_")), null, Vector3f())
            }
        }
    }

    // BKCommonLib の ItemDisplayMode の名前 (THIRD_PERSON_RIGHT_HAND など) を Bukkit の名前に直す
    private fun displayMode(name: String): ItemDisplayTransform {
        val compact = name.replace("_", "")
        return ItemDisplayTransform.entries.firstOrNull { it.name.replace("_", "") == compact } ?: ItemDisplayTransform.HEAD
    }
}
