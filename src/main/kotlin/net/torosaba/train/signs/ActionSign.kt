package net.torosaba.train.signs

import net.torosaba.train.util.BlockKey
import net.torosaba.train.util.Text
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import java.util.Locale

/**
 * 列車に働きかける看板。
 *
 * 1 行目: [train] (列車全体) または [cart] (車両ごと)。[+train] はレッドストーンの信号があるときだけ、
 *          [!train] は信号がないときだけ働く
 * 2 行目: 看板の種類 (station, launch など) と、続けて種類ごとの追加の指定
 * 3, 4 行目: 種類ごとの値
 */
class ActionSign(
    val block: Block,
    val scope: Scope,
    val power: PowerMode,
    val type: String,
    val args: List<String>,
    val line3: String,
    val line4: String,
) {
    enum class Scope { TRAIN, CART }
    enum class PowerMode { ALWAYS, POWERED, UNPOWERED }

    val key: BlockKey get() = BlockKey.of(block)

    fun isPowered(): Boolean = block.isBlockPowered || block.isBlockIndirectlyPowered

    /** レッドストーンの条件を満たしているか */
    fun isActive(): Boolean = when (power) {
        PowerMode.ALWAYS -> true
        PowerMode.POWERED -> isPowered()
        PowerMode.UNPOWERED -> !isPowered()
    }

    /** 3 行目と 4 行目をつなげた値 */
    val text34: String get() = listOf(line3, line4).filter { it.isNotBlank() }.joinToString(" ")

    companion object {
        private val HEADER = Regex("""^\[([+!]?)(train|cart|tt)]$""", RegexOption.IGNORE_CASE)

        fun isHeader(line: String) = HEADER.matches(line.trim())

        fun parse(block: Block): ActionSign? {
            val sign = block.state as? Sign ?: return null
            return parse(block, sign.getSide(Side.FRONT).lines().map { Text.plain(it) })
        }

        fun parse(block: Block, lines: List<String>): ActionSign? {
            if (lines.size < 2) return null
            val m = HEADER.matchEntire(lines[0].trim()) ?: return null
            val power = when (m.groupValues[1]) {
                "+" -> PowerMode.POWERED
                "!" -> PowerMode.UNPOWERED
                else -> PowerMode.ALWAYS
            }
            val scope = if (m.groupValues[2].equals("cart", true)) Scope.CART else Scope.TRAIN
            val tokens = lines[1].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            return ActionSign(
                block, scope, power,
                tokens[0].lowercase(Locale.ROOT), tokens.drop(1),
                lines.getOrElse(2) { "" }.trim(), lines.getOrElse(3) { "" }.trim(),
            )
        }
    }
}
