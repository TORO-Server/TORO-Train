package net.torosaba.train.model

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.structure.Structure
import org.joml.Quaternionf
import org.joml.Vector3d
import java.io.File
import java.util.logging.Logger

/**
 * ブロックのモデルの置き場。models/<名前>.nbt に Structure を、models/<名前>.yml に車体への合わせ方を保存する。
 * Structure の形式 (ストラクチャーブロックと同じ NBT) で保存するので、ブロックのデータは失われない。
 */
class ModelLibrary(private val dir: File, private val logger: Logger) {
    private val models = HashMap<String, BlockModel>()

    fun names(): List<String> = dir.listFiles { f -> f.extension == "nbt" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun exists(name: String) = models.containsKey(name.lowercase()) || File(dir, "${name.lowercase()}.nbt").exists()

    fun get(name: String): BlockModel? {
        val key = name.lowercase()
        models[key]?.let { return it }
        val nbt = File(dir, "$key.nbt")
        if (!nbt.exists()) return null
        return try {
            val structure = Bukkit.getStructureManager().loadStructure(nbt)
            val yaml = YamlConfiguration.loadConfiguration(File(dir, "$key.yml"))
            val origin = Vector3d(yaml.getDouble("origin.x"), yaml.getDouble("origin.y"), yaml.getDouble("origin.z"))
            val rot = Quaternionf(
                yaml.getDouble("rotation.x").toFloat(), yaml.getDouble("rotation.y").toFloat(),
                yaml.getDouble("rotation.z").toFloat(), yaml.getDouble("rotation.w", 1.0).toFloat(),
            )
            BlockModel(key, structure, origin, rot).also { models[key] = it }
        } catch (e: Exception) {
            logger.warning("モデル $key を読み込めませんでした: ${e.message}")
            null
        }
    }

    fun save(name: String, structure: Structure, origin: Vector3d, rotation: Quaternionf): BlockModel {
        val key = name.lowercase()
        dir.mkdirs()
        Bukkit.getStructureManager().saveStructure(File(dir, "$key.nbt"), structure)
        val yaml = YamlConfiguration()
        yaml.set("origin.x", origin.x)
        yaml.set("origin.y", origin.y)
        yaml.set("origin.z", origin.z)
        yaml.set("rotation.x", rotation.x.toDouble())
        yaml.set("rotation.y", rotation.y.toDouble())
        yaml.set("rotation.z", rotation.z.toDouble())
        yaml.set("rotation.w", rotation.w.toDouble())
        yaml.set("blocks", structure.palettes.firstOrNull()?.blockCount ?: 0)
        yaml.save(File(dir, "$key.yml"))
        return BlockModel(key, structure, origin, rotation).also { models[key] = it }
    }

    fun delete(name: String): Boolean {
        val key = name.lowercase()
        models.remove(key)
        val deleted = File(dir, "$key.nbt").delete()
        File(dir, "$key.yml").delete()
        return deleted
    }

    fun clearCache() = models.clear()
}
