package net.torosaba.train

import net.torosaba.train.command.TrainCommand
import net.torosaba.train.compat.TrainCartsImport
import net.torosaba.train.listener.SignListener
import net.torosaba.train.listener.TrackListener
import net.torosaba.train.listener.TrainListener
import net.torosaba.train.model.ModelLibrary
import net.torosaba.train.render.CartVisual
import net.torosaba.train.route.Router
import net.torosaba.train.signs.MutexZones
import net.torosaba.train.signs.NamedPlaces
import net.torosaba.train.signs.SignIndex
import net.torosaba.train.track.RailSegment
import net.torosaba.train.track.TrackGraph
import net.torosaba.train.track.TrackNetwork
import net.torosaba.train.train.BlockTrains
import net.torosaba.train.train.SavedTrains
import net.torosaba.train.train.TrainManager
import net.torosaba.train.util.Text
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.logging.Level

/**
 * TORO-Train: Display エンティティで描く列車のプラグイン。
 * 線路 (バニラのレールと自由な線路) の上を列車が走り、看板で駅・分岐・発車などを制御する。
 */
class TOROTrain : JavaPlugin() {
    lateinit var settings: Settings
        private set
    lateinit var network: TrackNetwork
        private set
    lateinit var graph: TrackGraph
        private set
    lateinit var models: ModelLibrary
        private set
    lateinit var trains: TrainManager
        private set
    lateinit var saved: SavedTrains
        private set
    lateinit var places: NamedPlaces
        private set
    lateinit var blockTrains: BlockTrains
        private set
    lateinit var router: Router
        private set
    lateinit var trainCarts: TrainCartsImport
        private set

    val signIndex = SignIndex()
    val mutexes = MutexZones()

    private val wandKey = NamespacedKey(this, "wand")

    override fun onEnable() {
        saveDefaultConfig()
        settings = Settings(config)
        RailSegment.allowUpsideDown = settings.upsideDownRails
        network = TrackNetwork(File(dataFolder, "tracks.yml")).also { it.load() }
        graph = TrackGraph(network)
        models = ModelLibrary(File(dataFolder, "models"), logger)
        saved = SavedTrains(File(dataFolder, "saved-trains.yml")).also { it.load() }
        places = NamedPlaces(File(dataFolder, "names.yml")).also { it.load() }
        router = Router(this)
        blockTrains = BlockTrains(this)
        trains = TrainManager(this)

        removeLeftoverEntities()
        try {
            trains.loadAll()
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "列車を読み込めませんでした", e)
        }

        server.pluginManager.registerEvents(TrainListener(this), this)
        server.pluginManager.registerEvents(SignListener(this), this)
        server.pluginManager.registerEvents(TrackListener(this), this)
        trainCarts = TrainCartsImport(this)
        server.pluginManager.registerEvents(trainCarts, this)
        // TrainCarts のデータの引き継ぎは、サーバーが起動しきってから行う
        server.scheduler.runTask(this, Runnable {
            try {
                trainCarts.autoImport()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "TrainCarts のデータを引き継げませんでした", e)
            }
        })
        val command = TrainCommand(this)
        getCommand("toro-train")?.apply {
            setExecutor(command)
            tabCompleter = command
        }
        server.scheduler.runTaskTimer(this, Runnable { trains.tick() }, 1L, 1L)
    }

    override fun onDisable() {
        if (!::trains.isInitialized) return
        trains.shutdown()
        network.save()
        places.saveIfDirty()
    }

    // 前回の終了が異常だったときに残った、列車の部品のエンティティを消す
    private fun removeLeftoverEntities() {
        for (world in server.worlds) for (entity in world.entities) {
            if (entity.persistentDataContainer.has(CartVisual.PART_KEY)) entity.remove()
        }
    }

    fun reloadAll() {
        reloadConfig()
        settings = Settings(config)
        RailSegment.allowUpsideDown = settings.upsideDownRails
        models.clearCache()
        saved.load()
        signIndex.clear()
        router.clear()
        for (train in trains.trains.values) for (cart in train.carts) cart.visual?.rebuild()
    }

    fun debug(message: () -> String) {
        if (settings.debug) logger.info("[debug] ${message()}")
    }

    fun saveAllData() {
        trains.saveAll()
        network.save()
        places.saveIfDirty()
    }

    fun wandItem(): ItemStack = ItemStack(Material.BLAZE_ROD).apply {
        editMeta { meta ->
            meta.displayName(Text.color("&6TORO-Train の範囲選択"))
            meta.persistentDataContainer.set(wandKey, PersistentDataType.BYTE, 1)
        }
    }

    fun isWand(item: ItemStack): Boolean =
        item.type == Material.BLAZE_ROD && item.persistentDataContainer.has(wandKey)
}
