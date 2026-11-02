package net.torosaba.train.train

import org.bukkit.Material

/**
 * TrainCarts の spawn の書き方を読む。文字はトロッコの種類 (m: ふつう、s: チェスト付き、p, f: かまど付き、
 * h: ホッパー付き、t: TNT 付き)、数は続く車両の数で、保存した列車の名前も並べられる。(ab) のようにかっこでまとめられる。
 * 例: 3m, 2st, 2(mp), 3急行
 */
object SpawnPattern {
    /** 1 本に出せる車両の上限 */
    const val MAX_CARTS = 256

    sealed interface Item

    data class Vanilla(val letter: Char) : Item {
        fun material(): Material = when (letter) {
            's' -> Material.CHEST
            'f', 'p' -> Material.FURNACE
            't' -> Material.TNT
            'h' -> Material.HOPPER
            else -> Material.IRON_BLOCK
        }
    }

    data class Saved(val name: String) : Item

    private const val LETTERS = "msfpth"

    /**
     * 読めれば車両の並びを返す。読めない文字があるか、上限より長ければ null。
     * savedMatcher は、文字列の始まりに一致する保存した列車の名前 (いちばん長いもの) を返す
     */
    fun parse(text: String, savedMatcher: (String) -> String?): List<Item>? {
        val parser = Parser(text, savedMatcher)
        return try {
            parser.sequence(top = true)
        } catch (_: Invalid) {
            null
        }
    }

    private object Invalid : RuntimeException() {
        private fun readResolve(): Any = Invalid
    }

    private class Parser(val text: String, val savedMatcher: (String) -> String?) {
        var index = 0

        fun sequence(top: Boolean): List<Item> {
            val out = ArrayList<Item>()
            var count = StringBuilder()
            fun add(items: List<Item>) {
                val times = if (count.isEmpty()) 1 else count.toString().toIntOrNull() ?: throw Invalid
                count = StringBuilder()
                if (times <= 0 || out.size + items.size.toLong() * times > MAX_CARTS) throw Invalid
                repeat(times) { out += items }
            }
            while (index < text.length) {
                val c = text[index]
                when {
                    c in "[<({" -> {
                        index++
                        add(sequence(top = false))
                    }
                    c in "]>)}" -> {
                        index++
                        if (!top) return out
                    }
                    c.isDigit() -> {
                        count.append(c)
                        index++
                    }
                    else -> {
                        val name = savedMatcher(text.substring(index))
                        if (name != null && (name.length > 1 || c !in LETTERS)) {
                            index += name.length
                            add(listOf(Saved(name)))
                        } else if (c in LETTERS) {
                            index++
                            add(listOf(Vanilla(c)))
                        } else {
                            throw Invalid
                        }
                    }
                }
            }
            // 数だけで終わるものは書き間違い
            if (count.isNotEmpty()) throw Invalid
            return out
        }
    }
}
