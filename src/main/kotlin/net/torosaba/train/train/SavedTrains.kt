package net.torosaba.train.train

import net.torosaba.train.model.CartModel
import net.torosaba.train.util.Text
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** 保存した列車 (車両の並びと設定)。/tt spawn や spawn の看板で何度でも出せる */
class SavedTrains(private val file: File) {
    class Template(val props: TrainProperties, val carts: List<Pair<CartModel, CartProperties>>)

    private val templates = LinkedHashMap<String, Template>()

    fun names(): List<String> = templates.keys.sorted()

    fun get(name: String): Template? = templates[name.lowercase()]

    fun save(name: String, train: Train) {
        val props = TrainProperties().also { it.copyFrom(train.props) }
        props.destination = null
        templates[name.lowercase()] = Template(props, train.carts.map { cart ->
            // 列車の向きに関係なく、今の並びを先頭から保存する
            val cp = CartProperties().also { p ->
                p.tags += cart.props.tags
                p.allowEnter = cart.props.allowEnter
                p.allowExit = cart.props.allowExit
            }
            cart.model to cp
        })
        write()
    }

    /** 作った Template をまとめて保存する (TrainCarts から引き継ぐときなど) */
    fun putAll(list: Map<String, Template>) {
        for ((name, template) in list) templates[name.lowercase()] = template
        write()
    }

    fun delete(name: String): Boolean = (templates.remove(name.lowercase()) != null).also { if (it) write() }

    /** 文字列の始まりに一致する、いちばん長い保存した列車の名前 */
    fun matchPrefix(text: String): String? {
        val lower = text.lowercase()
        return templates.keys.filter { lower.startsWith(it) }.maxByOrNull { it.length }
    }

    /** そのブロックのモデルを使っている保存した列車があるか */
    fun usesModel(model: String): Boolean =
        templates.values.any { t -> t.carts.any { it.first.usesBlockModel(model) } }

    fun load() {
        templates.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (key in yaml.getKeys(false)) {
            val s = yaml.getConfigurationSection(key) ?: continue
            val name = Text.fromYamlKey(key)
            val props = TrainProperties().also { p -> s.getConfigurationSection("properties")?.let { p.load(it) } }
            val carts = s.getConfigurationSection("carts")?.let { c ->
                c.getKeys(false).sortedBy { it.toIntOrNull() ?: 0 }.mapNotNull { c.getConfigurationSection(it) }.map { cs ->
                    CartModel.load(cs.getConfigurationSection("model")) to
                        CartProperties().also { p -> cs.getConfigurationSection("properties")?.let { p.load(it) } }
                }
            } ?: continue
            templates[name] = Template(props, carts)
        }
    }

    private fun write() {
        val yaml = YamlConfiguration()
        for ((rawName, t) in templates) {
            val name = Text.toYamlKey(rawName)
            t.props.save(yaml.createSection("$name.properties"))
            for ((i, pair) in t.carts.withIndex()) {
                pair.first.save(yaml.createSection("$name.carts.$i.model"))
                pair.second.save(yaml.createSection("$name.carts.$i.properties"))
            }
        }
        file.parentFile.mkdirs()
        yaml.save(file)
    }
}
