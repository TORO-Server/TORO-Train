package net.torosaba.train.compat

import net.torosaba.train.TOROTrain
import net.torosaba.train.model.AttachmentCartModel
import net.torosaba.train.model.BlockCartModel
import net.torosaba.train.model.CartModel
import net.torosaba.train.model.DefaultCartModel
import net.torosaba.train.model.Piece
import net.torosaba.train.model.Seat
import net.torosaba.train.track.TrackBlocks
import net.torosaba.train.train.CartProperties
import net.torosaba.train.train.SavedTrains
import net.torosaba.train.train.SpawnPattern
import net.torosaba.train.train.TrainProperties
import net.torosaba.train.util.BlockKey
import net.torosaba.train.util.Text
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.MemoryConfiguration
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
 * - 車体は取り付けの木 (ブロック、アイテム、文字、スケマティック、保存したモデル、座席) をたどって作る。
 *   スケマティック (WE_SCHEMATIC) は TORO-Train のブロックのモデルにする
 * - モジュール (savedTrainModules, savedModelModules)、保存した経路 (routes.yml)、行き先 (destinations.dat)、
 *   新しい列車の既定の設定 (DefaultTrainProperties.yml の default) も読む
 *
 * 位置と向きは TrainCarts と同じ計算にする: 車体の座標は +Z が前、取り付けの変換は T(pos) * Ry(-yaw) * Rx(pitch) * Rz(roll)、
 * スケマティックは横と前後を中央に、いちばん下を原点にそろえる。座席の位置は座る人の腰の位置。
 */
class TrainCartsImport(private val plugin: TOROTrain) : Listener {
    private val stateFile get() = File(plugin.dataFolder, "traincarts-import.yml")
    private val tcDir get() = File(plugin.dataFolder.parentFile, "Train_Carts")

    // 走っていた列車の、まだ置き換えていないトロッコ: エンティティの UUID → 列車の名前
    private val pendingCarts = HashMap<UUID, String>()
    // 列車は置き換えたが、読み込まれていなかったので残っている車両のトロッコ。読み込まれたら消す
    private val leftoverCarts = HashSet<UUID>()
    private var state = YamlConfiguration()

    // 引き継ぎの間だけ使う、TrainCarts の保存したモデルと既定の設定
    private var savedModels: Map<String, ConfigurationSection> = emptyMap()
    private var defaults: ConfigurationSection? = null

    class Report(
        var saved: Int = 0,
        var live: Int = 0,
        var models: Int = 0,
        var routes: Int = 0,
        var destinations: Int = 0,
        val missing: MutableSet<String> = LinkedHashSet(),
        val skipped: MutableSet<String> = LinkedHashSet(),
    )

    fun load() {
        state = if (stateFile.exists()) YamlConfiguration.loadConfiguration(stateFile) else YamlConfiguration()
        pendingCarts.clear()
        leftoverCarts.clear()
        state.getStringList("leftovers").mapNotNullTo(leftoverCarts) { runCatching { UUID.fromString(it) }.getOrNull() }
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
            // 前の版の引き継ぎは取り付けやモジュールなどを読んでいない。保存した列車を上書きするので、自動ではやり直さない
            if (state.getInt("format") < FORMAT && tcDir.isDirectory) {
                plugin.logger.warning("この版は TrainCarts のデータをより多く読めます。/tt import traincarts でやり直すと反映されます (同じ名前の保存した列車は上書きされます)")
            }
            return
        }
        if (!tcDir.isDirectory) return
        if (plugin.server.pluginManager.getPlugin("Train_Carts") != null) {
            plugin.logger.info("TrainCarts が入っているので、データの引き継ぎは TrainCarts を外してから行います (/tt import traincarts でもできます)")
            return
        }
        val report = importAll()
        plugin.logger.info("TrainCarts から引き継ぎました: 保存した列車 ${report.saved} 本、走っていた列車 ${report.live} 本、モデル ${report.models} 個、" +
            "経路 ${report.routes} 個、行き先 ${report.destinations} 個" +
            (if (report.missing.isNotEmpty()) " (見つからないスケマティック: ${report.missing.joinToString(", ")})" else "") +
            (if (report.skipped.isNotEmpty()) " (描かなかったもの: ${report.skipped.joinToString(", ")})" else ""))
    }

    fun importAll(): Report {
        val report = Report()
        val modelCache = HashMap<String, String?>()
        defaults = readYaml(File(tcDir, "DefaultTrainProperties.yml")).getConfigurationSection("default")
        savedModels = loadEntries("SavedModels.yml", "savedModelModules")

        // 保存した列車
        val saved = loadEntries("SavedTrainProperties.yml", "savedTrainModules")
        val templates = LinkedHashMap<String, SavedTrains.Template>()
        for ((name, s) in saved) {
            try {
                val carts = cartSections(s).map { c -> convertCart(c, report, modelCache) to cartProps(c) }
                if (carts.isNotEmpty()) templates[name] = SavedTrains.Template(trainProps(s, cartSections(s), false), carts)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "TrainCarts の保存した列車 $name を引き継げませんでした", e)
            }
        }
        // 車両を持たず、ほかの列車を並べた別名 (spawnPattern) は、並べる先を読んでから作る
        for ((name, s) in saved) {
            val pattern = s.getString("spawnPattern")?.takeIf { it.isNotBlank() } ?: continue
            if (name in templates) continue
            expandPattern(name, pattern, s, templates)?.let { templates[name] = it }
        }
        plugin.saved.putAll(templates)
        report.saved = templates.size

        // 走っていた列車
        val live = readYaml(File(tcDir, "TrainProperties.yml"))
        pendingCarts.clear()
        val pending = state.createSection("pending")
        for (name in live.getKeys(false)) {
            val s = live.getConfigurationSection(name) ?: continue
            val cartsSection = s.getConfigurationSection("carts") ?: continue
            val carts = cartsSection.getKeys(false).mapNotNull { uuid -> cartsSection.getConfigurationSection(uuid)?.let { uuid to it } }
            val key = Text.toYamlKey(name)
            val out = pending.createSection(key)
            val props = trainProps(s, carts.map { it.second }, true)
            props.save(out.createSection("properties"))
            for ((i, entry) in carts.withIndex()) {
                val (uuid, c) = entry
                val target = out.createSection("carts.$i")
                target.set("uuid", uuid)
                convertCart(c, report, modelCache).save(target.createSection("model"))
                cartProps(c).save(target.createSection("properties"))
                runCatching { pendingCarts[UUID.fromString(uuid)] = key }
            }
            report.live++
        }
        importRoutes(report)
        importDestinations(report)
        savedModels = emptyMap()
        defaults = null
        report.models = modelCache.values.count { it != null }
        state.set("imported", true)
        state.set("format", FORMAT)
        state.set("missing-schematics", report.missing.toList())
        saveState()
        convertLoaded()
        return report
    }

    /** 本体のファイルとモジュールのフォルダの、名前 → 中身の一覧 (TrainCarts のモジュールも同じ形のファイル) */
    private fun loadEntries(mainFile: String, moduleDir: String): Map<String, ConfigurationSection> {
        val modules = File(tcDir, moduleDir).walkTopDown()
            .filter { it.isFile && (it.extension.equals("yml", true) || it.extension.equals("yaml", true)) }
            .sortedBy { it.path }
        val entries = LinkedHashMap<String, ConfigurationSection>()
        for (file in sequenceOf(File(tcDir, mainFile)) + modules) {
            val yaml = readYaml(file)
            for (name in yaml.getKeys(false)) yaml.getConfigurationSection(name)?.let { entries[name] = it }
        }
        return entries
    }

    // 名前に . があっても階層に分かれないよう、区切りをほかの文字にして読む
    private fun readYaml(file: File): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.options().pathSeparator(PATH_SEPARATOR)
        if (!file.isFile) return yaml
        try {
            yaml.load(file)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "TrainCarts のファイル ${file.name} を読めませんでした", e)
        }
        return yaml
    }

    private fun cartSections(s: ConfigurationSection): List<ConfigurationSection> = children(s, "carts")

    // TrainCarts の並びは番号をキーにした表で保存される。ふつうのリストで書かれていても読む
    private fun children(s: ConfigurationSection, key: String): List<ConfigurationSection> {
        s.getConfigurationSection(key)?.let { c ->
            return c.getKeys(false).sortedBy { it.toIntOrNull() ?: 0 }.mapNotNull { c.getConfigurationSection(it) }
        }
        return s.getMapList(key).mapIndexed { i, map -> MemoryConfiguration().createSection(i.toString(), map) }
    }

    private fun convertCart(c: ConfigurationSection, report: Report, cache: HashMap<String, String?>): CartModel =
        convertModel(c.getConfigurationSection("model"), c.getBoolean("flipped"), minecartMaterial(c.getString("entityType")), report, cache)

    private fun expandPattern(name: String, pattern: String, s: ConfigurationSection, templates: Map<String, SavedTrains.Template>): SavedTrains.Template? {
        val items = SpawnPattern.parse(pattern.lowercase()) { rest ->
            templates.keys.map { it.lowercase() }.filter { rest.startsWith(it) }.maxByOrNull { it.length }
        }
        if (items.isNullOrEmpty()) {
            plugin.logger.warning("TrainCarts の保存した列車 $name の spawnPattern「$pattern」を読めませんでした")
            return null
        }
        val carts = ArrayList<Pair<CartModel, CartProperties>>()
        for (item in items) {
            when (item) {
                is SpawnPattern.Vanilla -> carts += DefaultCartModel(item.material()) to CartProperties()
                is SpawnPattern.Saved -> templates.entries.firstOrNull { it.key.equals(item.name, true) }?.value?.carts?.let { list ->
                    list.mapTo(carts) { it.first to it.second.copy() }
                }
            }
        }
        return SavedTrains.Template(trainProps(s, emptyList(), false), carts)
    }

    private fun importRoutes(report: Report) {
        val routes = readYaml(File(tcDir, "routes.yml"))
        for (name in routes.getKeys(false)) {
            val route = routes.getStringList(name)
            if (route.isEmpty()) continue
            plugin.places.setRoute(name, route)
            report.routes++
        }
    }

    private fun importDestinations(report: Report) {
        val file = File(tcDir, "destinations.dat")
        if (!file.isFile) return
        val list = try {
            TrainCartsFormat.readDestinations(file)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "TrainCarts の destinations.dat を読めませんでした", e)
            return
        }
        for (d in list) {
            val world = plugin.server.getWorld(d.world) ?: continue
            plugin.places.addDestination(d.name, BlockKey(world.uid, d.x, d.y, d.z))
            report.destinations++
        }
    }

    // ---------- 設定 ----------

    /**
     * TrainCarts の列車の設定を読む。書いていない設定は DefaultTrainProperties.yml の default から取る。
     * 行き先・経路・タグ・持ち主は TrainCarts では車両ごとの設定なので、車両のものも見る。
     *
     * allowManualMovement は読まない: これは TrainCarts の W で押す機能の設定で (既定で false)、MasconCart はこの値に
     * 関係なく運転できたので、マスコンの manualControl には写さない
     *
     * @param useOwners 持ち主を写すか。走っていた列車だけ (保存した列車の claims は保存した列車を編集できる人で、出した列車の持ち主ではない)
     */
    private fun trainProps(s: ConfigurationSection, carts: List<ConfigurationSection>, useOwners: Boolean): TrainProperties {
        val p = plugin.trains.defaultProps()
        val fallback = defaults
        fun value(key: String): Any? = s.get(key) ?: fallback?.get(key)

        (value("speedLimit") as? Number)?.let { p.speedLimit = it.toDouble() }
        // slowDown は true / false か、friction と gravity の表 (書いていないものは false)
        var friction = true
        var gravity = true
        when (val slow = value("slowDown")) {
            is Boolean -> {
                friction = slow
                gravity = slow
            }
            is ConfigurationSection -> {
                friction = slow.getBoolean("friction", false)
                gravity = slow.getBoolean("gravity", false)
            }
        }
        // friction と gravity は倍率
        val frictionFactor = (value("friction") as? Number)?.toDouble() ?: 1.0
        val gravityFactor = (value("gravity") as? Number)?.toDouble() ?: 1.0
        p.friction = if (friction) p.friction * frictionFactor else 0.0
        p.gravity = gravity && gravityFactor > 0.0
        when (val chunks = value("keepChunksLoaded")) {
            is Boolean -> p.keepChunksLoaded = chunks
            is ConfigurationSection -> p.keepChunksLoaded = !chunks.getString("mode", "disabled").equals("disabled", true)
        }
        when (val wait = value("wait")) {
            is ConfigurationSection -> p.waitDistance = wait.getDouble("distance", p.waitDistance)
            else -> (value("waitDistance") as? Number)?.let { p.waitDistance = it.toDouble() }
        }
        // ぶつかった列車と連結するか
        when (val collision = value("collision")) {
            is ConfigurationSection -> collision.getString("train")?.let { p.coupling = it.equals("LINK", true) }
            else -> (value("trainCollision") as? Boolean)?.let { p.coupling = it }
        }
        (value("invincible") as? Boolean)?.let { p.invincible = it }
        (value("displayName") as? String)?.takeIf { it.isNotBlank() }?.let { p.displayName = it }

        p.destination = sequenceOf(s).plus(carts).firstNotNullOfOrNull { c -> c.getString("destination")?.takeIf { it.isNotBlank() } }
        sequenceOf(s).plus(carts).map { it.getStringList("route") }.firstOrNull { it.isNotEmpty() }?.let { p.route += it }
        if (p.destination == null) p.destination = p.route.firstOrNull()
        p.tags += s.getStringList("tags")
        if (useOwners) {
            for (name in sequenceOf(s).plus(carts).flatMap { it.getStringList("owners") }) {
                plugin.server.getOfflinePlayerIfCached(name)?.let { p.owners += it.uniqueId }
            }
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

    /** トロッコのエンティティの種類を、既定のモデルの素材にする */
    private fun minecartMaterial(entityType: String?): Material = when (entityType?.uppercase()) {
        "MINECART_CHEST", "CHEST_MINECART" -> Material.CHEST
        "MINECART_FURNACE", "FURNACE_MINECART" -> Material.FURNACE
        "MINECART_TNT", "TNT_MINECART" -> Material.TNT
        "MINECART_HOPPER", "HOPPER_MINECART" -> Material.HOPPER
        else -> Material.IRON_BLOCK
    }

    /**
     * TrainCarts の取り付け (model の木) を、TORO-Train の車体のモデルにする。
     * 部品が 1 つだけのふつうのトロッコとスケマティックは、それぞれ専用のモデルにする (/tt blocks restore などが使える)
     */
    private fun convertModel(model: ConfigurationSection?, flipped: Boolean, material: Material, report: Report, cache: HashMap<String, String?>): CartModel {
        if (model == null) return DefaultCartModel(material)
        val flip = Quaternionf().rotateY(if (flipped) Math.PI.toFloat() else 0f)
        val physical = model.getConfigurationSection("physical")
        val cartLength = physical?.getDouble("cartLength", -1.0) ?: -1.0
        val wheelDistance = physical?.getDouble("wheelDistance", -1.0) ?: -1.0

        val pieces = ArrayList<Piece>()
        val butts = ArrayList<Vector3f>()
        walk(model, flip.transform(Vector3f()), flip, material, pieces, butts, HashSet(), report, cache)

        // 腰の位置から足の位置へ (座った人の目は腰の 1.0 上、足は目の 1.62 下)
        val seatList = butts.map { Vector3f(it).sub(0f, 0.62f, 0f) }
        val seats = if (seatList.isEmpty()) null else {
            val front = seatList.maxOf { it.z }
            val back = seatList.minOf { it.z }
            seatList.map { Seat(it, it.z >= front - 0.01f || it.z <= back + 0.01f) }
        }

        val only = pieces.singleOrNull()
        return when {
            pieces.isEmpty() -> DefaultCartModel(material)
            only is Piece.Minecart && only.position.lengthSquared() < 1e-6f && only.scale == Vector3f(1f) ->
                DefaultCartModel(only.material, if (cartLength > 0) cartLength else 1.25)
            only is Piece.Blocks -> BlockCartModel(
                only.model,
                offset = only.position,
                lengthOverride = cartLength,
                extraRotation = only.rotation,
                scale = only.scale,
                fixedSeats = seats ?: emptyList(),
                wheelDistance = wheelDistance,
            )
            else -> AttachmentCartModel(pieces, seats ?: listOf(Seat(Vector3f(0f, 0.3f, 0f), true)), cartLength, wheelDistance)
        }
    }

    /**
     * 取り付けの木をたどって、部品と座席 (腰の位置) を車体の座標で集める。
     * 親の変換は子にかかるが、大きさ (size) はその取り付けにしかかからない
     */
    private fun walk(
        node: ConfigurationSection, parentPos: Vector3f, parentRot: Quaternionf, material: Material,
        pieces: MutableList<Piece>, butts: MutableList<Vector3f>, models: MutableSet<String>,
        report: Report, cache: HashMap<String, String?>,
    ) {
        val p = pose(node)
        val pos = parentRot.transform(Vector3f(p.pos)).add(parentPos)
        val rot = Quaternionf(parentRot).mul(p.rot)
        val position = node.getConfigurationSection("position")
        when (val type = node.getString("type")?.uppercase()) {
            "SEAT" -> butts += pos
            "ENTITY" -> {
                val entity = node.getString("entityType")?.uppercase() ?: "MINECART"
                if ("MINECART" in entity) {
                    val body = if (entity == "MINECART") material else minecartMaterial(entity)
                    pieces += Piece.Minecart(body, pos, rot, p.size)
                } else {
                    report.skipped += "ENTITY:$entity"
                }
            }
            "BLOCK_DISPLAY", "BLOCK" -> node.getString("blockData")?.let { pieces += Piece.Block(it, pos, rot, p.size) }
            "ITEM" -> node.getItemStack("item")?.let { item ->
                val spec = TrainCartsFormat.itemSpec(position?.getString("transform"))
                val scale = spec.scale?.let { Vector3f(it) } ?: p.size
                pieces += Piece.Item(item, spec.display, rot.transform(Vector3f(spec.offset)).add(pos), rot, scale)
            }
            "TEXT" -> node.getString("text")?.takeIf { it.isNotBlank() }?.let { pieces += Piece.Label(it, pos, rot, p.size) }
            "WE_SCHEMATIC", "SCHEMATIC" -> node.getString("schematic")?.let { schematic ->
                cache.getOrPut(schematic.lowercase()) { importSchematic(schematic, report) }?.let { name ->
                    // TrainCarts と同じく、originX などの分だけ戻して置く
                    val origin = Vector3f(
                        position?.getDouble("originX")?.toFloat() ?: 0f,
                        position?.getDouble("originY")?.toFloat() ?: 0f,
                        position?.getDouble("originZ")?.toFloat() ?: 0f,
                    )
                    pieces += Piece.Blocks(name, Vector3f(pos).sub(rot.transform(origin)), rot, p.size)
                }
            }
            "MODEL" -> {
                val name = node.getString("modelName").orEmpty()
                val saved = savedModels.entries.firstOrNull { it.key.equals(name, true) }?.value
                // モデルが自分自身を含んでいても止まるよう、たどっているモデルを覚える
                if (saved == null) {
                    if (name.isNotBlank()) report.skipped += "MODEL:$name"
                } else if (models.add(name.lowercase())) {
                    walk(saved, pos, rot, material, pieces, butts, models, report, cache)
                    models.remove(name.lowercase())
                }
            }
            null, "EMPTY", "SEQUENCER", "HITBOX", "PLATFORM", "SOUND", "LIGHT" -> {}
            else -> report.skipped += type
        }
        for (child in children(node, "attachments")) walk(child, pos, rot, material, pieces, butts, models, report, cache)
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
        if (pendingCarts.isEmpty() && leftoverCarts.isEmpty()) return
        val minecarts = event.entities.filterIsInstance<Minecart>()
        val names = minecarts.mapNotNull { pendingCarts[it.uniqueId] }.toSet()
        val leftovers = minecarts.filter { it.uniqueId in leftoverCarts }
        if (names.isEmpty() && leftovers.isEmpty()) return
        // イベントの中ではエンティティを消さず、次の tick で置き換える
        plugin.server.scheduler.runTask(plugin, Runnable {
            names.forEach { convert(it) }
            if (leftovers.isNotEmpty()) {
                for (cart in leftovers) {
                    cart.remove()
                    leftoverCarts.remove(cart.uniqueId)
                }
                saveLeftovers()
            }
        })
    }

    private fun saveLeftovers() {
        state.set("leftovers", leftoverCarts.map { it.toString() })
        saveState()
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
                    val trainName = Text.fromYamlKey(name).takeIf { plugin.trains.byName(it) == null }
                    val train = plugin.trains.create(path, models, trainName, props, cartProps)
                    plugin.logger.info("TrainCarts の列車 $name を ${train.name} に置き換えました (${block.x}, ${block.y}, ${block.z})")
                } else {
                    plugin.logger.warning("TrainCarts の列車 $name を置く線路が見つからなかったので、トロッコを消すだけにしました (${block.x}, ${block.y}, ${block.z})")
                }
            } else {
                plugin.logger.warning("TrainCarts の列車 $name のトロッコが線路の上にないので、トロッコを消すだけにしました (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})")
            }
            entities.forEach { it.eject(); it.remove() }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "TrainCarts の列車 $name を置き換えられませんでした", e)
        }
        // 置き換えたら待ちから外す。読み込まれていない車両のトロッコは、読み込まれたときに消す
        val removed = entities.map { it.uniqueId }.toSet()
        for (c in cartSections) c.getString("uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let {
            pendingCarts.remove(it)
            if (it !in removed) leftoverCarts += it
        }
        state.set("pending.$name", null)
        saveLeftovers()
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

    private companion object {
        /** 引き継ぎの形式の版。読めるものが増えたら上げる */
        const val FORMAT = 2
        const val PATH_SEPARATOR = '\u0001'
    }
}
