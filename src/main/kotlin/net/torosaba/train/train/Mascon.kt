package net.torosaba.train.train

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.torosaba.train.TOROTrain
import net.torosaba.train.model.DefaultCartModel
import net.torosaba.train.render.CartVisual
import net.torosaba.train.track.TrackBlocks
import net.torosaba.train.util.Text
import org.bukkit.Bukkit
import org.bukkit.Input
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.joml.Vector3d
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.logging.Level
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * マスコンの速度の決まり方。MasconCart と同じ数値で、力行は段ごとの目標速度まで一定の加速度で上げ、
 * 目標より速いときとニュートラルでは少しずつ遅くなる。ブレーキは段に比例して減速し、非常ブレーキはその場で止まる。
 */
object MasconProfile {
    const val MAX_SPEED = 1.5
    const val MAX_NOTCH = 7
    const val EMERGENCY = -8

    /** 1 秒あたりの加速。P1 だけゆっくり */
    private const val ACCELERATION = 0.2
    private const val P1_ACCELERATION = 0.05

    /** ブレーキ 1 段ぶんの、1 秒あたりの減速 */
    private const val BRAKE = 0.07

    /** 0.1 秒ごとに残る速さの割合 */
    private const val COAST = 0.995

    fun update(speed: Double, notch: Int, seconds: Double, maxSpeed: Double = MAX_SPEED): Double {
        require(seconds.isFinite() && seconds > 0) { "seconds must be finite and positive" }
        val n = notch.coerceIn(EMERGENCY, MAX_NOTCH)
        val coasting = speed * COAST.pow(seconds / 0.1)
        val next = when {
            n == EMERGENCY -> 0.0
            n < 0 -> speed - BRAKE * -n * seconds
            n == 0 -> coasting
            else -> {
                val target = maxSpeed / MAX_NOTCH * n
                val acceleration = if (n == 1) P1_ACCELERATION else ACCELERATION
                if (speed < target) min(speed + acceleration * seconds, target) else coasting
            }
        }
        return next.coerceIn(0.0, maxSpeed)
    }

    fun label(notch: Int): String = when {
        notch == 0 -> "N"
        notch <= EMERGENCY -> "EB"
        notch > 0 -> "P$notch"
        else -> "B${-notch}"
    }
}

/**
 * マスコン運転 (MasconCart の操作)。乗っている人が D でマスコンを入れると、その人だけがその列車を運転し、
 * 看板の制御と最高速度の設定は運転をやめるまで効かなくなる。
 */
class MasconControl(private val plugin: TOROTrain) {
    private val file get() = File(plugin.dataFolder, "mascon.yml")

    // /mascon off にしているプレイヤー
    private val disabled = HashSet<UUID>()
    private val lastInput = HashMap<UUID, Input>()
    private val lastToggle = HashMap<UUID, Long>()
    private val lastReverse = HashMap<UUID, Long>()
    private val hudHeldUntil = HashMap<UUID, Long>()

    fun load() {
        disabled.clear()
        val source = when {
            file.exists() -> file
            // MasconCart から移るとき、/mascon off の設定を引き継ぐ
            else -> File(plugin.dataFolder.parentFile, "MasconCart/config.yml")
        }
        if (source.exists()) {
            YamlConfiguration.loadConfiguration(source).getStringList("disabledPlayers")
                .mapNotNullTo(disabled) { runCatching { UUID.fromString(it) }.getOrNull() }
        }
        if (source != file && disabled.isNotEmpty()) save()
    }

    private fun save() {
        val yaml = YamlConfiguration()
        yaml.set("disabledPlayers", disabled.map { it.toString() })
        try {
            plugin.dataFolder.mkdirs()
            yaml.save(file)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "mascon.yml を保存できませんでした", e)
        }
    }

    fun setEnabled(player: Player, enabled: Boolean) {
        val changed = if (enabled) disabled.remove(player.uniqueId) else disabled.add(player.uniqueId)
        if (!enabled) plugin.trains.cartOf(player)?.train?.takeIf { it.masconDriver == player.uniqueId }?.let { stop(it) }
        if (changed) save()
    }

    fun start(train: Train, player: UUID) {
        train.masconDriver = player
        train.notch = 0
        train.action = null
        train.resumeSpeed = 0.0
        plugin.trains.releaseSwitchLocks(train)
    }

    fun stop(train: Train) {
        train.masconDriver = null
        train.notch = 0
    }

    /** 運転している人が降りた・いなくなった・別の列車に分かれたら、運転をやめる */
    fun checkDriver(train: Train) {
        val id = train.masconDriver ?: return
        val player = Bukkit.getPlayer(id)
        if (player == null || plugin.trains.cartOf(player)?.train !== train || !train.props.manualControl || id in disabled) stop(train)
    }

    /** 運転してよい人か。driver-seat-only のときは、先頭か最後尾の車両の運転席だけ */
    private fun canDrive(player: Player, cart: Cart): Boolean {
        if (!player.hasPermission("torotrain.drive") || !cart.train.props.manualControl) return false
        if (!plugin.settings.masconDriverSeatOnly) return true
        val train = cart.train
        return (cart === train.carts.firstOrNull() || cart === train.carts.lastOrNull()) && cart.visual?.seatOf(player)?.driver == true
    }

    fun onInput(player: Player, input: Input) {
        val previous = lastInput.put(player.uniqueId, input)
        plugin.debug { "${player.name} の入力: $input (乗り物: ${player.vehicle?.type})" }
        val vehicle = player.vehicle ?: return
        if (input.isRight && previous?.isRight != true) toggle(player, vehicle)

        val cart = plugin.trains.entityCarts[vehicle.uniqueId] ?: return
        val train = cart.train
        val forward = input.isForward && previous?.isForward != true
        if (input.isSprint && previous?.isSprint != true && canDrive(player, cart)) horn(cart)
        if (train.masconDriver != player.uniqueId) {
            if (forward) pushOff(player, cart)
            return
        }
        if (train.derailed) return
        if (input.isLeft && previous?.isLeft != true) train.notch = 0
        if (forward) {
            train.notch = min(train.notch + 1, MasconProfile.MAX_NOTCH)
        } else if (input.isBackward && previous?.isBackward != true) {
            train.notch = max(train.notch - 1, MasconProfile.EMERGENCY)
        }
        if (input.isJump && previous?.isJump != true) reverseIfStopped(player, train)
    }

    private fun toggle(player: Player, vehicle: Entity) {
        val id = player.uniqueId
        if (id in disabled) return
        val now = System.currentTimeMillis()
        if (now - lastToggle.getOrDefault(id, 0L) <= TOGGLE_COOLDOWN_MS) return

        val riding = plugin.trains.entityCarts[vehicle.uniqueId]
        if (riding != null && riding.train.masconDriver == id) {
            lastToggle[id] = now
            stop(riding.train)
            player.sendMessage(Text.color("&a[Mascon] モード: OFF 看板の制御が有効"))
            return
        }
        if (riding?.train?.masconDriver != null) {
            player.sendMessage(Text.color("&c[Mascon] この列車は他のプレイヤーが操作中です"))
            return
        }
        val cart = riding ?: (vehicle as? Minecart)?.let { convertMinecart(player, it) }
        if (cart == null || cart.train.derailed || !canDrive(player, cart)) {
            player.sendMessage(Text.color("&c[Mascon] このトロッコではマスコンを開始できません"))
            return
        }
        lastToggle[id] = now
        start(cart.train, id)
        player.sendMessage(Text.color("&a[Mascon] モード: ON 看板の制御が無効"))
    }

    /** 乗っているふつうのトロッコを、同じ場所の列車にして座り直させる */
    private fun convertMinecart(player: Player, minecart: Minecart): Cart? {
        if (!plugin.settings.masconConvertMinecarts || !player.hasPermission("torotrain.place")) return null
        val at = minecart.location.block
        val block = sequenceOf(at, at.getRelative(BlockFace.DOWN)).firstOrNull { TrackBlocks.isTrack(it) } ?: return null
        val velocity = minecart.velocity
        val look = if (velocity.lengthSquared() > 1e-6) velocity.toVector3d() else player.location.direction.toVector3d()
        val model = DefaultCartModel()
        val path = plugin.trains.pathAt(block, null, model.length + plugin.settings.cartGap, Vector3d(look.x, 0.0, look.z)) ?: return null
        val train = plugin.trains.create(path, listOf(model))
        train.props.owners += player.uniqueId
        train.speed = velocity.length()
        minecart.eject()
        minecart.remove()
        val cart = train.carts.first()
        val visual = CartVisual(plugin, cart)
        cart.visual = visual
        if (visual.ensure()) visual.seat(player, null)
        return cart
    }

    /** 右クリックやジャンプで、止まっているときだけ進む向きを変える。押しっぱなしで続けて変わらないよう間を空ける */
    fun reverseIfStopped(player: Player, train: Train) {
        val now = System.currentTimeMillis()
        if (now - lastReverse.getOrDefault(player.uniqueId, 0L) < REVERSE_COOLDOWN_MS || train.derailed) return
        lastReverse[player.uniqueId] = now
        if (train.speed >= STOP_THRESHOLD) {
            player.sendMessage(Text.color("&c[Mascon] 走行中は進行方向を切り替えられません"))
            return
        }
        train.speed = 0.0
        train.reverse()
        player.sendMessage(Text.color("&b[Mascon] 進行方向を切り替えました"))
    }

    /** マスコンを入れていないときの W。止まっている列車を、見ている向きへ押し出す (ふつうのトロッコと同じ) */
    private fun pushOff(player: Player, cart: Cart) {
        val train = cart.train
        if (train.masconDriver != null || train.action != null || train.derailed || train.speed >= PUSH_THRESHOLD || !canDrive(player, cart)) return
        val look = player.location.direction
        val tangent = train.headTangent()
        if (tangent.x * look.x + tangent.z * look.z < 0) train.reverse()
        train.speed = max(train.speed, PUSH_SPEED)
    }

    private fun horn(cart: Cart) {
        plugin.debug { "${cart.train.name} の警笛" }
        val sound = Sound.sound(Key.key("minecraft:block.note_block.didgeridoo"), Sound.Source.MASTER, 3f, 0.7f)
        val c = cart.position
        cart.train.world.playSound(sound, c.x, c.y, c.z)
    }

    /** 画面の下に案内を出す。HUD で上書きされないよう、しばらく HUD を止める */
    fun notice(player: Player, text: String) {
        hudHeldUntil[player.uniqueId] = System.currentTimeMillis() + NOTICE_MS
        player.sendActionBar(Text.color(text))
    }

    fun showHud(train: Train) {
        val driver = train.masconDriver?.let { Bukkit.getPlayer(it) } ?: return
        if (System.currentTimeMillis() < hudHeldUntil.getOrDefault(driver.uniqueId, 0L)) return
        val kmh = train.speed * plugin.settings.masconDisplayKmh
        val dest = train.props.destination?.let { " &7行先 &f$it" } ?: ""
        val state = train.blockedBy?.let { " &7| &c$it" } ?: ""
        val text = String.format(Locale.ROOT, "&fノッチ: %s &f速度: %.1f km/h", coloredNotch(train.notch), kmh)
        driver.sendActionBar(Text.color("$text$dest$state"))
    }

    private fun coloredNotch(notch: Int): String {
        val color = when {
            notch > 0 -> "&a"
            notch == 0 -> "&f"
            notch <= MasconProfile.EMERGENCY -> "&4"
            else -> "&c"
        }
        return color + MasconProfile.label(notch)
    }

    fun forget(player: Player) {
        lastInput.remove(player.uniqueId)
        lastToggle.remove(player.uniqueId)
        lastReverse.remove(player.uniqueId)
        hudHeldUntil.remove(player.uniqueId)
    }

    private companion object {
        const val TOGGLE_COOLDOWN_MS = 1000L
        const val REVERSE_COOLDOWN_MS = 500L
        const val STOP_THRESHOLD = 0.0025
        const val PUSH_THRESHOLD = 0.01
        const val PUSH_SPEED = 0.1
        const val NOTICE_MS = 2000L
    }
}
