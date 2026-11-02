package net.torosaba.train

import net.torosaba.train.compat.TrainCartsFormat
import net.torosaba.train.train.SpawnPattern
import net.torosaba.train.util.Text
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrainCartsFormatTest {
    private fun destinations(write: DataOutputStream.() -> Unit): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bytes)).use { it.write() }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun DataOutputStream.node(name: String, world: String, x: Int, y: Int, z: Int) {
        writeUTF(name)
        writeUTF(world)
        writeInt(x)
        writeInt(y)
        writeInt(z)
    }

    @Test
    fun readsDestinationsAndSkipsSwitchers() {
        val stream = destinations {
            writeInt(3)
            node("東京\n::traincarts::switchable::", "world", 1, 64, -2)
            node("", "world", 5, 64, 5)
            node("大阪\nOsaka", "nether", -7, 70, 9)
            // この後ろのつながりの情報は読まない
            writeInt(99)
        }
        assertEquals(
            listOf(
                TrainCartsFormat.Destination("東京", "world", 1, 64, -2),
                TrainCartsFormat.Destination("大阪", "nether", -7, 70, 9),
                TrainCartsFormat.Destination("Osaka", "nether", -7, 70, 9),
            ),
            TrainCartsFormat.readDestinations(stream),
        )
    }

    @Test
    fun emptyDestinationsFile() {
        // 実際の TrainCarts が書いた、行き先のない destinations.dat の中身
        val bytes = intArrayOf(0x78, 0x9C, 0x63, 0x60, 0x60, 0x60, 0x00, 0x00, 0x00, 0x04, 0x00, 0x01).map { it.toByte() }.toByteArray()
        assertEquals(emptyList(), TrainCartsFormat.readDestinations(ByteArrayInputStream(bytes)))
    }

    @Test
    fun truncatedDestinationsKeepsWhatWasRead() {
        val stream = destinations {
            writeInt(2)
            node("東京", "world", 1, 2, 3)
        }
        assertEquals(listOf(TrainCartsFormat.Destination("東京", "world", 1, 2, 3)), TrainCartsFormat.readDestinations(stream))
    }

    @Test
    fun itemTransforms() {
        // 書いていなければ、TrainCarts の既定の防具立ての頭
        val head = TrainCartsFormat.itemSpec(null)
        assertEquals(ItemDisplayTransform.HEAD, head.display)
        assertEquals(0.625f, head.scale)
        assertEquals(0.25f, head.offset.y)
        // 防具立ての名前は、接頭辞なしでも HYBRID_ARMORSTAND_ でも同じ
        assertEquals(0.4375f, TrainCartsFormat.itemSpec("SMALL_HEAD").scale)
        assertEquals(0.4375f, TrainCartsFormat.itemSpec("HYBRID_ARMORSTAND_HEAD_SMALL").scale)
        assertEquals(ItemDisplayTransform.THIRDPERSON_RIGHTHAND, TrainCartsFormat.itemSpec("HYBRID_ARMORSTAND_RIGHT_HAND").display)
        // DISPLAY の表示は取り付けの大きさを使い、ずらさない
        val display = TrainCartsFormat.itemSpec("DISPLAY_THIRD_PERSON_LEFT_HAND")
        assertEquals(ItemDisplayTransform.THIRDPERSON_LEFTHAND, display.display)
        assertNull(display.scale)
        assertEquals(0f, display.offset.lengthSquared())
        assertEquals(ItemDisplayTransform.FIXED, TrainCartsFormat.itemSpec("DISPLAY_FIXED").display)
        // HYBRID_DISPLAY は決まった大きさで、ずらさない
        val hybrid = TrainCartsFormat.itemSpec("HYBRID_DISPLAY_HEAD")
        assertEquals(0.625f, hybrid.scale)
        assertEquals(0f, hybrid.offset.lengthSquared())
    }

    private fun pattern(text: String, vararg saved: String) = SpawnPattern.parse(text) { rest ->
        saved.filter { rest.startsWith(it) }.maxByOrNull { it.length }
    }

    private fun letters(text: String) = text.map { SpawnPattern.Vanilla(it) }

    @Test
    fun spawnPatterns() {
        assertEquals(letters("m"), pattern("m"))
        assertEquals(letters("mmm"), pattern("3m"))
        assertEquals(letters("sst"), pattern("2st"))
        assertEquals(letters("mpmp"), pattern("2(mp)"))
        assertEquals(listOf(SpawnPattern.Saved("急行"), SpawnPattern.Saved("急行"), SpawnPattern.Vanilla('m')), pattern("2急行m", "急行"))
    }

    @Test
    fun savedNameWinsUnlessItIsOneVanillaLetter() {
        assertEquals(listOf(SpawnPattern.Saved("max")), pattern("max", "max"))
        assertEquals(letters("m"), pattern("m", "m"))
    }

    @Test
    fun badSpawnPatterns() {
        assertNull(pattern("north 0.4"))
        assertNull(pattern("3"))
        assertNull(pattern("9999m"))
        assertNull(pattern("99999999999999m"))
        assertNull(pattern("200(200m)"))
    }

    @Test
    fun yamlKeysKeepDots() {
        val key = Text.toYamlKey("sta.1.v2")
        assertEquals(false, key.contains('.'))
        assertEquals("sta.1.v2", Text.fromYamlKey(key))
    }
}
