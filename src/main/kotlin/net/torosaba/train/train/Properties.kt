package net.torosaba.train.train

import net.torosaba.train.util.Text
import org.bukkit.configuration.ConfigurationSection
import java.util.Locale
import java.util.UUID

/** 列車全体の設定 */
class TrainProperties {
    /** 最高速度 (ブロック/tick) */
    var speedLimit = 0.4
    /** 加速度 (ブロック/tick²)。発車や加速の看板で使う */
    var acceleration = 0.004
    /** 減速度 (ブロック/tick²)。駅に止まるときやブレーキで使う */
    var deceleration = 0.008
    /** 1 tick ごとに失う速さの割合 */
    var friction = 0.0015
    /** 坂で重力を受けるか */
    var gravity = true
    /** 前の列車と空ける距離 (ブロック)。0 なら止まらずにぶつかる */
    var waitDistance = 2.0
    /** ほかの列車にぶつかったら連結するか */
    var coupling = false
    /** 走っている間、まわりのチャンクを読み込んだままにするか */
    var keepChunksLoaded = false
    /** 乗った人がマスコン (W/S) で運転できるか */
    var manualControl = true
    /** 線路の終わりで止まらずに、線路から外れて進むか (外れると重力で落ち、地面を滑り、線路に落ちればまた乗る) */
    var derail = true
    /** 誰でも乗れるか */
    var allowEnter = true
    /** 降りられるか */
    var allowExit = true
    /** プレイヤーが壊せないか */
    var invincible = false
    /** 行き先 */
    var destination: String? = null
    /** 順に向かう行き先の並び */
    val route = ArrayList<String>()
    val tags = LinkedHashSet<String>()
    val owners = LinkedHashSet<UUID>()
    /** 案内に使う名前 */
    var displayName: String? = null

    fun copyFrom(other: TrainProperties) {
        val section = org.bukkit.configuration.MemoryConfiguration()
        other.save(section)
        load(section)
    }

    fun save(s: ConfigurationSection) {
        s.set("speed-limit", speedLimit)
        s.set("acceleration", acceleration)
        s.set("deceleration", deceleration)
        s.set("friction", friction)
        s.set("gravity", gravity)
        s.set("wait-distance", waitDistance)
        s.set("coupling", coupling)
        s.set("keep-chunks-loaded", keepChunksLoaded)
        s.set("manual-control", manualControl)
        s.set("derail", derail)
        s.set("allow-enter", allowEnter)
        s.set("allow-exit", allowExit)
        s.set("invincible", invincible)
        s.set("destination", destination)
        s.set("route", route.toList())
        s.set("tags", tags.toList())
        s.set("owners", owners.map { it.toString() })
        s.set("display-name", displayName)
    }

    fun load(s: ConfigurationSection) {
        speedLimit = s.getDouble("speed-limit", speedLimit)
        acceleration = s.getDouble("acceleration", acceleration)
        deceleration = s.getDouble("deceleration", deceleration)
        friction = s.getDouble("friction", friction)
        gravity = s.getBoolean("gravity", gravity)
        waitDistance = s.getDouble("wait-distance", waitDistance)
        coupling = s.getBoolean("coupling", coupling)
        keepChunksLoaded = s.getBoolean("keep-chunks-loaded", keepChunksLoaded)
        manualControl = s.getBoolean("manual-control", manualControl)
        derail = s.getBoolean("derail", derail)
        allowEnter = s.getBoolean("allow-enter", allowEnter)
        allowExit = s.getBoolean("allow-exit", allowExit)
        invincible = s.getBoolean("invincible", invincible)
        destination = s.getString("destination")
        route.clear(); route += s.getStringList("route")
        tags.clear(); tags += s.getStringList("tags")
        owners.clear(); owners += s.getStringList("owners").mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        displayName = s.getString("display-name")
    }
}

/** 車両ごとの設定 */
class CartProperties {
    val tags = LinkedHashSet<String>()
    var allowEnter = true
    var allowExit = true

    fun save(s: ConfigurationSection) {
        s.set("tags", tags.toList())
        s.set("allow-enter", allowEnter)
        s.set("allow-exit", allowExit)
    }

    fun load(s: ConfigurationSection) {
        tags.clear(); tags += s.getStringList("tags")
        allowEnter = s.getBoolean("allow-enter", true)
        allowExit = s.getBoolean("allow-exit", true)
    }
}

/**
 * 名前で読み書きできる設定の一覧。コマンドと property の看板で使う。
 */
object PropertyKeys {
    class Key(val name: String, val aliases: List<String>, val cart: Boolean, val get: (Train, Cart?) -> String, val set: (Train, Cart?, String) -> Boolean)

    private fun bool(text: String): Boolean? = when (text.lowercase(Locale.ROOT)) {
        "true", "yes", "on", "1", "はい" -> true
        "false", "no", "off", "0", "いいえ" -> false
        else -> null
    }

    private fun trainBool(name: String, vararg aliases: String, prop: (TrainProperties) -> kotlin.reflect.KMutableProperty0<Boolean>) =
        Key(name, aliases.toList(), false, { t, _ -> prop(t.props).get().toString() }, { t, _, v -> bool(v)?.let { prop(t.props).set(it); true } ?: false })

    private fun trainDouble(name: String, vararg aliases: String, speed: Boolean = false, prop: (TrainProperties) -> kotlin.reflect.KMutableProperty0<Double>) =
        Key(name, aliases.toList(), false, { t, _ -> prop(t.props).get().let { if (speed) "${it} (${Text.speed(it)})" else it.toString() } }, { t, _, v ->
            (if (speed) Text.parseSpeed(v) else v.toDoubleOrNull())?.takeIf { it >= 0 }?.let { prop(t.props).set(it); true } ?: false
        })

    val keys: List<Key> = listOf(
        trainDouble("speedlimit", "maxspeed", "speed-limit", speed = true) { it::speedLimit },
        trainDouble("acceleration", "accel", speed = true) { it::acceleration },
        trainDouble("deceleration", "decel", "brake", speed = true) { it::deceleration },
        trainDouble("friction", "slowdown") { it::friction },
        trainDouble("waitdistance", "wait-distance", "spacing") { it::waitDistance },
        trainBool("gravity") { it::gravity },
        trainBool("coupling", "link") { it::coupling },
        trainBool("keepchunksloaded", "keep-chunks-loaded", "chunks") { it::keepChunksLoaded },
        trainBool("manualcontrol", "manual", "mascon") { it::manualControl },
        trainBool("invincible") { it::invincible },
        trainBool("derail", "offrail", "leavetrack") { it::derail },
        Key("allowenter", listOf("enter", "playerenter"), true, { t, c -> (c?.props?.allowEnter ?: t.props.allowEnter).toString() }, { t, c, v ->
            val b = bool(v) ?: return@Key false
            if (c != null) c.props.allowEnter = b else t.props.allowEnter = b
            true
        }),
        Key("allowexit", listOf("exit", "playerexit"), true, { t, c -> (c?.props?.allowExit ?: t.props.allowExit).toString() }, { t, c, v ->
            val b = bool(v) ?: return@Key false
            if (c != null) c.props.allowExit = b else t.props.allowExit = b
            true
        }),
        Key("destination", listOf("dest"), false, { t, _ -> t.props.destination ?: "なし" }, { t, _, v ->
            t.props.destination = v.takeUnless { it.isBlank() || it.equals("none", true) || it == "なし" }
            true
        }),
        Key("route", emptyList(), false, { t, _ -> t.props.route.joinToString(" ") }, { t, _, v ->
            t.props.route.clear()
            t.props.route += v.split(' ', ',').filter { it.isNotBlank() }
            t.props.destination = t.props.route.firstOrNull()
            true
        }),
        Key("name", listOf("displayname"), false, { t, _ -> t.props.displayName ?: t.name }, { t, _, v ->
            t.props.displayName = v.takeIf { it.isNotBlank() }
            true
        }),
    )

    fun find(name: String): Key? {
        val n = name.lowercase(Locale.ROOT)
        return keys.firstOrNull { it.name == n || n in it.aliases }
    }
}
