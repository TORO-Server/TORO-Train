package net.torosaba.train.compat

import net.torosaba.train.TOROTrain
import net.torosaba.train.model.BlockCartModel
import net.torosaba.train.model.CartModel
import net.torosaba.train.model.DefaultCartModel
import net.torosaba.train.model.ItemCartModel
import net.torosaba.train.model.Seat
import net.torosaba.train.track.TrackBlocks
import net.torosaba.train.train.CartProperties
import net.torosaba.train.train.SavedTrains
import net.torosaba.train.train.TrainProperties
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Minecart
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.EntitiesLoadEvent
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.io.File
import java.util.UUID
import java.util.logging.Level

/**
 * TrainCarts のデータを引き継ぐ。
 *
 * - 保存した列車 (SavedTrainProperties.yml) は、TORO-Train の保存した列車にする
 * - 走っていた列車 (TrainProperties.yml) は、そのトロッコのエンティティがチャンクとともに読み込まれたときに
 *   TORO-Train の列車に置き換える (TrainCarts を外すと、トロッコはふつうのトロッコとしてワールドに残っている)
 * - WorldEdit のスケマティックを使う車体 (WE_SCHEMATIC) は、スケマティックを TORO-Train のブロックのモデルにする
 *
 * 位置と向きは TrainCarts と同じ計算にする: 車体の座標は +Z が前、取り付けの変換は T(pos) * Ry(-yaw) * Rx(pitch) * Rz(roll)、
 * スケマティックは横と前後を中央に、いちばん下を原点にそろえる。座席の位置は座る人の腰の位置。
 */
class TrainCartsImport(private val plugin: TOROTrain) : Listener {
    private val stateFile get() = File(plugin.dataFolder, "traincarts-import.yml")
    private val tcDir get() = File(plugin.dataFolder.parentFile, "Train_Carts")

    // 走っていた列車の、まだ置き換えていないトロッコ: エンティティの UUID → 列車の名前
    private val pendingCarts = HashMap<UUID, String>()
    private var state = YamlConfiguration()

    class Report(var saved: Int = 0, var live: Int = 0, var models: Int = 0, val missing: MutableSet<String> = LinkedHashSet())

    fun load() {
        state = if (stateFile.exists()) YamlConfiguration.loadConfiguration(stateFile) else YamlConfiguration()
        pendingCarts.clear()
        val trains = state.getConfigurationSection("pending") ?: return
        for (name in trains.getKeys(false)) {
            val carts = trains.getConfigurationSection("$name.carts") ?: continue
            for (key in carts.getKeys(false)) {
                carts.getString("$key.uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { pendingCarts[it] = name }
            }
        }
    }

    /** TrainCarts が入っておらず、データがあって、まだ引き継いでいなければ引き継ぐ */
    fun autoImport() {
        load()
        if (state.getBoolean("imported")) {
            convertLoaded()
            return
        }
        if (!tcDir.isDirectory) return
        if (plugin.server.pluginManager.getPlugin("Train_Carts") != null) {
            plugin.logger.info("TrainCarts が入っているので、データの引き継ぎは TrainCarts を外してから行います (/tt import traincarts でもできます)")
            return
        }
        val report = importAll()
        plugin.logger.info("TrainCarts から引き継ぎました: 保存した列車 ${report.saved} 本、走っていた列車 ${report.live} 本、モデル ${report.models} 個" +
            if (report.missing.isNotEmpty()) " (見つからないスケマティック: ${report.missing.joinToString(", ")})" else "")
    }

    fun importAll(): Report {
        val report = Report()
        val modelCache = HashMap<String, String?>()

        // 保存した列車
        val saved = YamlConfiguration.loadConfiguration(File(tcDir, "SavedTrainProperties.yml"))
        for (name in saved.getKeys(false)) {
            val s = saved.getConfigurationSection(name) ?: continue
            try {
                val carts = cartSections(s).map { c -> convertModel(c.getConfigurationSection("model"), c.getBoolean("flipped"), report, modelCache) to cartProps(c) }
                if (carts.isEmpty()) continue
                plugin.saved.put(name, SavedTrains.Template(trainProps(s), carts))
                report.saved++
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "TrainCarts の保存した列車 $name を引き継げませんでした", e)
            }
        }

        // 走っていた列車
        val live = YamlConfiguration.loadConfiguration(File(tcDir, "TrainProperties.yml"))
        val pending = state.createSection("pending")
        for (name in live.getKeys(false)) {
            val s = live.getConfigurationSection(name) ?: continue
            val cartsSection = s.getConfigurationSection("carts") ?: continue
            val out = pending.createSection(name)
            trainProps(s).save(out.createSection("properties"))
            var i = 0
            for (uuid in cartsSection.getKeys(false)) {
                val c = cartsSection.getConfigurationSection(uuid) ?: continue
                val target = out.createSection("carts.$i")
                target.set("uuid", uuid)
                convertModel(c.getConfigurationSection("model"), c.getBoolean("flipped"), report, modelCache).save(target.createSection("model"))
                cartProps(c).save(target.createSection("properties"))
                runCatching { pendingCarts[UUID.fromString(uuid)] = name }
                i++
            }
            report.live++
        }
        report.models = modelCache.values.count { it != null }
        state.set("imported", true)
        state.set("missing-schematics", report.missing.toList())
        saveState()
        convertLoaded()
        return report
    }

    private fun cartSections(s: ConfigurationSection): List<ConfigurationSection> {
        val c = s.getConfigurationSection("carts") ?: return emptyList()
        return c.getKeys(false).sortedBy { it.toIntOrNull() ?: 0 }.mapNotNull { c.getConfigurationSection(it) }
    }

    // ---------- 設定 ----------

    private fun trainProps(s: ConfigurationSection): TrainProperties {
        val p = plugin.trains.defaultProps()
        if (s.contains("speedLimit")) p.speedLimit = s.getDouble("speedLimit")
        when (val slow = s.get("slowDown")) {
            is Boolean -> if (!slow) {
                p.friction = 0.0
                p.gravity = false
            }
            is ConfigurationSection -> {
                if (!slow.getBoolean("FRICTION", true)) p.friction = 0.0
                if (!slow.getBoolean("GRAVITY", true)) p.gravity = false
            }
            is List<*> -> {
                val modes = slow.map { it.toString().uppercase() }
                if ("FRICTION" !in modes) p.friction = 0.0
                if ("GRAVITY" !in modes) p.gravity = false
            }
        }
        if (s.contains("keepChunksLoaded")) p.keepChunksLoaded = s.getBoolean("keepChunksLoaded")
        if (s.contains("allowManualMovement")) p.manualControl = s.getBoolean("allowManualMovement")
        s.getString("destination")?.takeIf { it.isNotBlank() }?.let { p.destination = it }
        p.route += s.getStringList("route")
        p.tags += s.getStringList("tags")
        s.getString("displayName")?.takeIf { it.isNotBlank() }?.let { p.displayName = it }
        // claims は「名前 UUID」の並び
        for (claim in s.getStringList("claims")) {
            claim.split(' ').mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.firstOrNull()?.let { p.owners += it }
        }
        return p
    }

    private fun cartProps(c: ConfigurationSection): CartProperties = CartProperties().also { p ->
        p.tags += c.getStringList("tags")
        if (c.contains("allowPlayerEnter")) p.allowEnter = c.getBoolean("allowPlayerEnter")
        if (c.contains("allowPlayerExit")) p.allowExit = c.getBoolean("allowPlayerExit")
    }

    // ---------- 車体 ----------

    private class Pose(val pos: Vector3f, val rot: Quaternionf, val size: Vector3f)

    private fun rad(deg: Double) = Math.toRadians(deg).toFloat()

    private fun pose(s: ConfigurationSection?): Pose {
        val p = s?.getConfigurationSection("position")
        val pos = Vector3f(p?.getDouble("posX")?.toFloat() ?: 0f, p?.getDouble("posY")?.toFloat() ?: 0f, p?.getDouble("posZ")?.toFloat() ?: 0f)
        val rot = Quaternionf()
            .rotateY(rad(-(p?.getDouble("rotY") ?: 0.0)))
            .rotateX(rad(p?.getDouble("rotX") ?: 0.0))
            .rotateZ(rad(p?.getDouble("rotZ") ?: 0.0))
        val size = Vector3f(
            p?.getDouble("sizeX", 1.0)?.toFloat() ?: 1f, p?.getDouble("sizeY", 1.0)?.toFloat() ?: 1f, p?.getDouble("sizeZ", 1.0)?.toFloat() ?: 1f,
        )
        return Pose(pos, rot, size)
    }

    /** TrainCarts の取り付け (model の木) を、TORO-Train の車体のモデルにする */
    private fun convertModel(model: ConfigurationSection?, flipped: Boolean, report: Report, cache: HashMap<String, String?>): CartModel {
        if (model == null) return DefaultCartModel()
        val flip = Quaternionf().rotateY(if (flipped) Math.PI.toFloat() else 0f)
        val root = pose(model)
        val physical = model.getConfigurationSection("physical")
        val cartLength = physical?.getDouble("cartLength", -1.0) ?: -1.0
        val wheelDistance = physical?.getDouble("wheelDistance", -1.0) ?: -1.0

        // 座席: 取り付けの木をたどって、車体の座標の腰の位置を集める
        val butts = ArrayList<Vector3f>()
        fun walk(section: ConfigurationSection, parentPos: Vector3f, parentRot: Quaternionf) {
            val children = section.getConfigurationSection("attachments") ?: return
            for (key in children.getKeys(false)) {
                val child = children.getConfigurationSection(key) ?: continue
                val p = pose(child)
                val pos = parentRot.transform(Vector3f(p.pos)).add(parentPos)
                val rot = Quaternionf(parentRot).mul(p.rot)
                if (child.getString("type") == "SEAT") butts += pos
                walk(child, pos, rot)
            }
        }
        walk(model, root.pos, root.rot)
        // 腰の位置から足の位置へ (座った人の目は腰の 1.0 上、足は目の 1.62 下)
        val seatList = butts.map { flip.transform(Vector3f(it)).sub(0f, 0.62f, 0f) }
        val seats = if (seatList.isEmpty()) null else {
            val front = seatList.maxOf { it.z }
            val back = seatList.minOf { it.z }
            seatList.map { Seat(it, it.z >= front - 0.01f || it.z <= back + 0.01f) }
        }

        return when (model.getString("type")) {
            "WE_SCHEMATIC" -> {
                val schematic = model.getString("schematic") ?: return DefaultCartModel()
                val modelName = cache.getOrPut(schematic.lowercase()) { importSchematic(schematic, report) }
                    ?: return DefaultCartModel()
                BlockCartModel(
                    modelName,
                    offset = flip.transform(Vector3f(root.pos)),
                    yaw = if (flipped) 180f else 0f,
                    lengthOverride = cartLength,
                    extraRotation = root.rot,
                    scale = root.size,
                    fixedSeats = seats ?: emptyList(),
                    wheelDistance = wheelDistance,
                )
            }
            "ITEM" -> {
                val item = model.getItemStack("item") ?: return DefaultCartModel()
                ItemCartModel(item, root.size.x, root.pos, if (flipped) 180f else 0f, if (cartLength > 0) cartLength else 1.5)
            }
            else -> DefaultCartModel()
        }
    }

    // スケマティックを探して、ブロックのモデルにする。見つからなければ null
    private fun importSchematic(name: String, report: Report): String? {
        val file = findSchematic(name)
        if (file == null) {
            report.missing += name
            return null
        }
        val modelName = "tc-" + name.lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
        return try {
            plugin.models.importSchematic(modelName, file) ?: return null
            modelName
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "スケマティック $name (${file.name}) を読み込めませんでした", e)
            report.missing += name
            null
        }
    }

    // TrainCarts と同じく、WorldEdit の schematics と TrainCarts の schematics を、拡張子を除いた名前で探す
    private fun findSchematic(name: String): File? {
        val roots = listOf(File(plugin.dataFolder.parentFile, "WorldEdit/schematics"), File(tcDir, "schematics"), File(plugin.dataFolder.parentFile, "FastAsyncWorldEdit/schematics"))
        for (root in roots) {
            if (!root.isDirectory) continue
            File(root, name).takeIf { it.isFile }?.let { return it }
            root.walkTopDown().firstOrNull { f ->
                f.isFile && (f.extension.equals("schem", true) || f.extension.equals("schematic", true)) &&
                    f.nameWithoutExtension.equals(name, ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    // ---------- 走っていた列車の置き換え ----------

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        if (pendingCarts.isEmpty()) return
        val names = event.entities.filterIsInstance<Minecart>().mapNotNull { pendingCarts[it.uniqueId] }.toSet()
        if (names.isEmpty()) return
        // イベントの中ではエンティティを消さず、次の tick で置き換える
        plugin.server.scheduler.runTask(plugin, Runnable { names.forEach { convert(it) } })
    }

    /** 読み込まれているワールドの、置き換えていないトロッコを探して置き換える */
    fun convertLoaded() {
        if (pendingCarts.isEmpty()) return
        val names = LinkedHashSet<String>()
        for (world in plugin.server.worlds) {
            for (cart in world.getEntitiesByClass(Minecart::class.java)) pendingCarts[cart.uniqueId]?.let { names += it }
        }
        plugin.debug { "TrainCarts の置き換えを待っているトロッコ ${pendingCarts.size} 台のうち、読み込まれている列車: ${names.joinToString(", ")}" }
        names.forEach { convert(it) }
    }

    private fun convert(name: String) {
        val section = state.getConfigurationSection("pending.$name") ?: return
        val cartSections = section.getConfigurationSection("carts")?.let { c ->
            c.getKeys(false).sortedBy { it.toIntOrNull() ?: 0 }.mapNotNull { c.getConfigurationSection(it) }
        } ?: return
        val entities = cartSections.mapNotNull { c ->
            c.getString("uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { plugin.server.getEntity(it) as? Minecart }
        }
        plugin.debug { "TrainCarts の列車 $name: トロッコ ${entities.size}/${cartSections.size} 台が読み込まれています" }
        val anchor = entities.firstOrNull() ?: return
        try {
            val loc = anchor.location
            val block = sequenceOf(loc.block, loc.block.getRelative(BlockFace.DOWN)).firstOrNull { TrackBlocks.isTrack(it) }
            val models = cartSections.map { CartModel.load(it.getConfigurationSection("model")) }
            val props = TrainProperties().also { p -> section.getConfigurationSection("properties")?.let { p.load(it) } }
            val cartProps = cartSections.map { c -> CartProperties().also { p -> c.getConfigurationSection("properties")?.let { p.load(it) } } }
            if (block != null) {
                val length = models.sumOf { it.length(plugin.models) + plugin.settings.cartGap }
                val path = plugin.trains.pathAt(block, null, length, forward(anchor))
                if (path != null) {
                    val trainName = name.takeIf { plugin.trains.byName(it) == null }
                    val train = plugin.trains.create(path, models, trainName, props, cartProps)
                    plugin.logger.info("TrainCarts の列車 $name を ${train.name} に置き換えました (${block.x}, ${block.y}, ${block.z})")
                }
            } else {
                plugin.logger.warning("TrainCarts の列車 $name のトロッコが線路の上にないので、トロッコを消すだけにしました (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})")
            }
            entities.forEach { it.eject(); it.remove() }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "TrainCarts の列車 $name を置き換えられませんでした", e)
        }
        // 置き換えたら (読み込まれていない車両のトロッコも含めて) 待ちから外す
        for (c in cartSections) c.getString("uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { pendingCarts.remove(it) }
        state.set("pending.$name", null)
        saveState()
    }

    // TrainCarts はトロッコのエンティティの向きを、進む向きから 90 度ずらして入れている
    private fun forward(cart: Minecart): Vector3d {
        val v = cart.velocity
        if (v.lengthSquared() > 1e-6) return Vector3d(v.x, 0.0, v.z)
        val dir = Location(cart.world, 0.0, 0.0, 0.0, cart.location.yaw + 90f, 0f).direction
        return Vector3d(dir.x, 0.0, dir.z)
    }

    private fun saveState() {
        try {
            plugin.dataFolder.mkdirs()
            state.save(stateFile)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "traincarts-import.yml を保存できませんでした", e)
        }
    }

    fun pendingCount(): Int = state.getConfigurationSection("pending")?.getKeys(false)?.size ?: 0
}
