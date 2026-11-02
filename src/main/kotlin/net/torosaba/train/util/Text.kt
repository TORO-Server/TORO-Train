package net.torosaba.train.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.Locale

/** メッセージと速さの表記 */
object Text {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()
    private val section = LegacyComponentSerializer.legacySection()

    /** & と § の色記号を使った文字列を Component にする */
    fun color(text: String): Component =
        if (text.contains('§')) section.deserialize(text) else legacy.deserialize(text)

    fun plain(component: Component?): String =
        if (component == null) "" else PlainTextComponentSerializer.plainText().serialize(component)

    /**
     * 名前を YAML のキーにする。Bukkit の YAML は . を階層の区切りとして読むので、名前の . を別の文字に置き換えて保存する
     */
    fun toYamlKey(name: String): String = name.replace('.', YAML_DOT)

    fun fromYamlKey(key: String): String = key.replace(YAML_DOT, '.')

    private const val YAML_DOT = '․'

    /** 1 tick あたりのブロック数を km/h にする (1 ブロック = 1 m、1 秒 = 20 tick) */
    fun kmh(blocksPerTick: Double): Double = blocksPerTick * 72.0

    fun speed(blocksPerTick: Double): String = String.format(Locale.ROOT, "%.0f km/h", kmh(blocksPerTick))

    /**
     * 速さの指定を読み、1 tick あたりのブロック数にする。
     * 単位なしはブロック/tick、"km" や "kmh" で終われば km/h、"m/s" や "ms" で終われば m/s。
     */
    fun parseSpeed(text: String): Double? {
        val t = text.trim().lowercase(Locale.ROOT)
        return when {
            t.endsWith("km/h") -> t.removeSuffix("km/h").toDoubleOrNull()?.div(72.0)
            t.endsWith("kmh") -> t.removeSuffix("kmh").toDoubleOrNull()?.div(72.0)
            t.endsWith("km") -> t.removeSuffix("km").toDoubleOrNull()?.div(72.0)
            t.endsWith("m/s") -> t.removeSuffix("m/s").toDoubleOrNull()?.div(20.0)
            t.endsWith("ms") -> t.removeSuffix("ms").toDoubleOrNull()?.div(20.0)
            else -> t.toDoubleOrNull()
        }?.takeIf { it.isFinite() }
    }

    /** 秒数の指定 ("10", "10s", "2m", "500ms") を tick にする */
    fun parseTicks(text: String): Int? {
        val t = text.trim().lowercase(Locale.ROOT)
        val seconds = when {
            t.endsWith("ms") -> t.removeSuffix("ms").toDoubleOrNull()?.div(1000.0)
            t.endsWith("s") -> t.removeSuffix("s").toDoubleOrNull()
            t.endsWith("m") -> t.removeSuffix("m").toDoubleOrNull()?.times(60.0)
            t.endsWith("t") -> return t.removeSuffix("t").toIntOrNull()
            else -> t.toDoubleOrNull()
        } ?: return null
        if (!seconds.isFinite() || seconds < 0) return null
        return (seconds * 20.0).toInt()
    }
}
