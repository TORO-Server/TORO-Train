package net.torosaba.train.signs

import net.torosaba.train.util.BlockKey
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 名前の付いた場所 (行き先と、テレポートの出入口) の一覧。看板が置かれたときと、列車が通ったときに覚える。
 * names.yml に保存する。
 */
class NamedPlaces(private val file: File) {
    /** 行き先の名前 → 線路のブロック */
    val destinations = HashMap<String, MutableSet<BlockKey>>()

    /** 出入口の名前 → 線路のブロック */
    val portals = HashMap<String, BlockKey>()

    private var dirty = false

    private fun norm(name: String) = name.trim().lowercase(Locale.ROOT)

    fun addDestination(name: String, rail: BlockKey) {
        if (name.isBlank()) return
        if (destinations.getOrPut(norm(name)) { HashSet() }.add(rail)) dirty = true
    }

    fun isDestination(name: String, rail: BlockKey): Boolean = destinations[norm(name)]?.contains(rail) == true

    fun setPortal(name: String, rail: BlockKey) {
        if (name.isBlank()) return
        if (portals.put(norm(name), rail) != rail) dirty = true
    }

    fun portal(name: String): BlockKey? = portals[norm(name)]

    /** 看板が消えたとき、その線路のブロックの名前を忘れる */
    fun removeRail(rail: BlockKey) {
        var changed = false
        for (set in destinations.values) changed = set.remove(rail) || changed
        destinations.values.removeIf { it.isEmpty() }
        changed = portals.values.removeIf { it == rail } || changed
        if (changed) dirty = true
    }

    fun load() {
        destinations.clear()
        portals.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getConfigurationSection("destinations")?.let { s ->
            for (name in s.getKeys(false)) {
                destinations[name] = s.getStringList(name).mapNotNull(::parseKey).toHashSet()
            }
        }
        yaml.getConfigurationSection("portals")?.let { s ->
            for (name in s.getKeys(false)) parseKey(s.getString(name) ?: continue)?.let { portals[name] = it }
        }
    }

    fun saveIfDirty() {
        if (!dirty) return
        val yaml = YamlConfiguration()
        for ((name, keys) in destinations) yaml.set("destinations.$name", keys.map(::formatKey))
        for ((name, key) in portals) yaml.set("portals.$name", formatKey(key))
        file.parentFile.mkdirs()
        yaml.save(file)
        dirty = false
    }

    private fun formatKey(k: BlockKey) = "${k.world}:${k.x},${k.y},${k.z}"

    private fun parseKey(text: String): BlockKey? {
        val (world, pos) = text.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        val uuid = runCatching { UUID.fromString(world) }.getOrNull() ?: return null
        return BlockKey.parse(uuid, pos)
    }
}

/** 区間を 1 本の列車だけが通れるようにする排他の区間 */
class MutexZones {
    private val holders = HashMap<String, UUID>()

    fun holder(zone: String): UUID? = holders[zone.lowercase(Locale.ROOT)]

    fun tryAcquire(zone: String, train: UUID): Boolean {
        val key = zone.lowercase(Locale.ROOT)
        val current = holders[key]
        if (current != null && current != train) return false
        holders[key] = train
        return true
    }

    fun release(zone: String, train: UUID) {
        val key = zone.lowercase(Locale.ROOT)
        if (holders[key] == train) holders.remove(key)
    }

    fun releaseAll(train: UUID) {
        holders.values.removeIf { it == train }
    }

    fun zones(): Map<String, UUID> = holders
}
