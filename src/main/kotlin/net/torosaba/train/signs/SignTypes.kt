package net.torosaba.train.signs

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.title.Title
import net.torosaba.train.TOROTrain
import net.torosaba.train.track.DirectedSegment
import net.torosaba.train.track.RailSegment
import net.torosaba.train.train.Cart
import net.torosaba.train.train.LaunchAction
import net.torosaba.train.train.LaunchDirection
import net.torosaba.train.train.PropertyKeys
import net.torosaba.train.train.StopAfter
import net.torosaba.train.train.Train
import net.torosaba.train.train.TrainAction
import net.torosaba.train.train.WaitAction
import net.torosaba.train.util.BlockKey
import net.torosaba.train.util.Text
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Rail
import org.bukkit.block.data.type.Switch
import org.bukkit.block.data.type.WallSign
import org.bukkit.entity.Player
import java.time.Duration
import java.util.Locale

/** 看板が働いたときの情報 */
class SignContext(
    val plugin: TOROTrain,
    val sign: ActionSign,
    val train: Train,
    /** [cart] の看板なら、その車両 */
    val cart: Cart?,
    /** 看板が働く線路のブロック */
    val rail: BlockKey,
) {
    /** 看板の対象の乗客 */
    fun passengers(): List<Player> = cart?.passengers() ?: train.passengers()
}

/** 看板の種類 */
abstract class SignType(vararg names: String) {
    val names: List<String> = names.toList()
    val name: String get() = names.first()

    /** 置くための権限 */
    val permission: String get() = "torotrain.sign.$name"

    abstract val usage: String

    /** 置いたときに確かめる。だめなら理由を返す */
    open fun validate(sign: ActionSign): String? = null

    /** 列車 ([train]) か車両 ([cart]) が線路のブロックに入ったとき */
    open fun onEnter(ctx: SignContext) {}

    /** 列車か車両が線路のブロックから出たとき */
    open fun onLeave(ctx: SignContext) {}

    /** 看板のレッドストーンの信号が変わったとき */
    open fun onPower(plugin: TOROTrain, sign: ActionSign, powered: Boolean) {}

    /** 先の線路を調べるときに、列車を止めるべきなら理由を返す (信号機のように手前で止まる) */
    open fun blocks(plugin: TOROTrain, sign: ActionSign, train: Train): String? = null
}

/** すべての看板の種類 */
object SignTypes {
    val all: List<SignType> = listOf(
        Station, DestinationSign, Switcher, Launch, SpeedLimit, PropertySign, TagSign, Eject, Enter,
        Blocker, WaitSign, Mutex, Announce, SoundSign, Effect, Teleport, Spawn, Destroy, Reverse, Skip,
        Detector, RouteSign,
    )

    private val byName: Map<String, SignType> = all.flatMap { t -> t.names.map { it to t } }.toMap()

    fun find(name: String): SignType? = byName[name.lowercase(Locale.ROOT)]

    // ---------- 共通の部品 ----------

    /** 看板が付いているブロックの周りのレバーを切り替える (看板からレッドストーンの信号を出す) */
    fun output(sign: Block, on: Boolean) {
        val data = sign.blockData
        val attached = if (data is WallSign) sign.getRelative(data.facing.oppositeFace) else sign.getRelative(BlockFace.DOWN)
        for (base in listOf(sign, attached)) {
            for (face in arrayOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
                val b = base.getRelative(face)
                if (b.type != Material.LEVER) continue
                val lever = b.blockData as Switch
                if (lever.isPowered == on) continue
                lever.isPowered = on
                b.setBlockData(lever, true)
            }
        }
    }

    /** 条件の文字列 (tag=a&!dest=b など) を列車に当てはめる */
    fun matches(train: Train, cart: Cart?, condition: String): Boolean {
        val c = condition.trim()
        if (c.isEmpty() || c == "*" || c.equals("default", true)) return true
        return c.split('&').all { part ->
            var p = part.trim()
            val negate = p.startsWith('!')
            if (negate) p = p.substring(1)
            val result = when {
                p.startsWith("#") -> hasTag(train, cart, p.substring(1))
                p.startsWith("@") -> train.props.destination.equals(p.substring(1), true)
                p.contains('=') -> {
                    val (k, v) = p.split('=', limit = 2)
                    when (k.lowercase(Locale.ROOT)) {
                        "tag", "t" -> hasTag(train, cart, v)
                        "dest", "destination", "d" -> train.props.destination.equals(v, true)
                        "name", "n" -> train.name.equals(v, true)
                        "carts", "count" -> train.carts.size.toString() == v
                        "passengers", "p" -> (train.passengers().size > 0).toString() == v.lowercase()
                        else -> false
                    }
                }
                else -> hasTag(train, cart, p)
            }
            result != negate
        }
    }

    private fun hasTag(train: Train, cart: Cart?, tag: String) =
        if (cart != null) tag in cart.props.tags || tag in train.props.tags else train.hasTag(tag)

    fun direction(text: String?): LaunchDirection? = LaunchDirection.parse(text)

    /** 先頭からその線路のブロックまでの道のり。分からなければ 0 */
    fun distanceTo(train: Train, rail: BlockKey) = train.distanceToBlock(rail) ?: 0.0

    /** 動作を順につなげる */
    fun chain(vararg actions: TrainAction?): TrainAction? {
        val list = actions.filterNotNull()
        if (list.isEmpty()) return null
        for (i in 0 until list.size - 1) list[i].andThen(list[i + 1])
        return list[0]
    }

    /** すぐに処理を 1 回して終わる動作 */
    class Run(private val block: (Train) -> Unit) : TrainAction() {
        override fun tick(train: Train): Boolean {
            block(train)
            return true
        }

        override val holdsSpeed: Boolean get() = false
    }
}

/**
 * 駅。列車を止めて待ち、発車させる。
 * 3 行目: 駅の名前 (行き先の名前にもなる)
 * 4 行目: 停車時間 (秒)、発車の向き (continue / reverse / north など)、
 *         power (信号が来るまで待つ)、head / center / tail (どこを駅に合わせるか)、speed=速さ
 */
object Station : SignType("station", "st") {
    override val usage = "3 行目: 駅名 / 4 行目: 停車秒数 向き [power] [head|center|tail] [speed=速さ]"

    override fun validate(sign: ActionSign): String? {
        for (token in sign.line4.split(' ').filter { it.isNotBlank() }) {
            if (parseOption(token) == null) return "4 行目の「$token」が分かりません"
        }
        return null
    }

    private sealed interface Opt {
        class Delay(val ticks: Int) : Opt
        class Dir(val dir: LaunchDirection) : Opt
        class Align(val value: String) : Opt
        class Speed(val value: Double) : Opt
        data object Power : Opt
    }

    private fun parseOption(token: String): Opt? {
        val t = token.lowercase(Locale.ROOT)
        Text.parseTicks(t)?.let { return Opt.Delay(it) }
        if (t == "power" || t == "redstone") return Opt.Power
        if (t in listOf("head", "center", "tail")) return Opt.Align(t)
        if (t.startsWith("speed=")) return Text.parseSpeed(t.removePrefix("speed="))?.let { Opt.Speed(it) }
        return LaunchDirection.parse(t)?.let { Opt.Dir(it) }
    }

    override fun onEnter(ctx: SignContext) {
        if (ctx.cart != null) return
        val train = ctx.train
        val name = ctx.sign.line3
        if (name.isNotBlank()) {
            ctx.plugin.places.addDestination(name, ctx.rail)
            train.arrive(name)
        }
        var delay = ctx.plugin.settings.stationDelayTicks
        var dir: LaunchDirection = LaunchDirection.Continue
        var align = "center"
        var speed: Double? = null
        var power = false
        for (token in ctx.sign.line4.split(' ').filter { it.isNotBlank() }) {
            when (val o = parseOption(token)) {
                is Opt.Delay -> delay = o.ticks
                is Opt.Dir -> dir = o.dir
                is Opt.Align -> align = o.value
                is Opt.Speed -> speed = o.value
                Opt.Power -> power = true
                null -> {}
            }
        }
        val toStation = SignTypes.distanceTo(train, ctx.rail)
        val remaining = when (align) {
            "head" -> toStation
            "tail" -> toStation + train.length()
            else -> toStation + train.length() / 2
        }.coerceAtLeast(0.0)
        val launchSpeed = speed ?: train.speed.takeIf { it > 0.05 } ?: train.props.speedLimit
        val sign = ctx.sign
        train.action = SignTypes.chain(
            StopAfter(remaining),
            SignTypes.Run { t ->
                SignTypes.output(sign.block, true)
                if (name.isNotBlank()) {
                    val title = Title.title(Text.color("&f$name"), Text.color("&7到着"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500)))
                    t.passengers().forEach { it.showTitle(title) }
                }
            },
            WaitAction(delay, "停車中", if (power) ({ sign.isPowered() }) else null),
            SignTypes.Run { SignTypes.output(sign.block, false) },
            LaunchAction(launchSpeed, null, dir),
        )
    }
}

/**
 * 行き先。3 行目の名前の場所として経路探索に使う。
 * 4 行目があれば、行き先がない (またはここが行き先の) 列車の行き先をそれにする。
 */
object DestinationSign : SignType("destination", "dest") {
    override val usage = "3 行目: この場所の名前 / 4 行目: 次の行き先 (省略可)"

    override fun validate(sign: ActionSign) = if (sign.line3.isBlank()) "3 行目に場所の名前を書いてください" else null

    override fun onEnter(ctx: SignContext) {
        val name = ctx.sign.line3
        ctx.plugin.places.addDestination(name, ctx.rail)
        val train = ctx.train
        val reached = train.props.destination.equals(name, true)
        train.arrive(name)
        if (ctx.sign.line4.isNotBlank() && (reached || train.props.destination == null)) {
            train.props.destination = ctx.sign.line4
        }
    }
}

/**
 * 分岐器。列車が来る前に (先の線路を調べるときに) レールの形を変える。
 * 3, 4 行目: 「向き:条件」。向きは left / right / straight か n / e / s / w。条件を満たす最初の行の向きに切り替える。
 * どの行にも当てはまらず、列車に行き先があれば、経路探索で向きを決める。
 */
object Switcher : SignType("switcher", "switch") {
    override val usage = "3, 4 行目: 向き:条件 (例: left:tag=急行, right:@東京, straight)。空なら行き先で自動"

    override fun validate(sign: ActionSign): String? {
        for (line in listOf(sign.line3, sign.line4)) {
            if (line.isBlank()) continue
            val dir = line.split(':', ' ', limit = 2)[0]
            if (relative(dir, BlockFace.NORTH) == null) return "「$dir」は向きではありません (left / right / straight / n / e / s / w)"
        }
        return null
    }

    private fun relative(dir: String, heading: BlockFace): BlockFace? = when (dir.lowercase(Locale.ROOT)) {
        "l", "left", "左" -> left(heading)
        "r", "right", "右" -> left(heading).oppositeFace
        "f", "st", "straight", "forward", "直進" -> heading
        "n", "north" -> BlockFace.NORTH
        "e", "east" -> BlockFace.EAST
        "s", "south" -> BlockFace.SOUTH
        "w", "west" -> BlockFace.WEST
        else -> null
    }

    private fun left(heading: BlockFace) = when (heading) {
        BlockFace.NORTH -> BlockFace.WEST
        BlockFace.WEST -> BlockFace.SOUTH
        BlockFace.SOUTH -> BlockFace.EAST
        else -> BlockFace.NORTH
    }

    /**
     * 列車がこれから seg (分岐器のレール) に入るとして、レールの形を決めて変える。変えたら true。
     */
    fun apply(plugin: TOROTrain, train: Train, seg: DirectedSegment, sign: ActionSign): Boolean {
        val rail = seg.segment as? RailSegment ?: return false
        val block = rail.world.getBlockAt(rail.x, rail.y, rail.z)
        val entry = if (seg.reversed) rail.faceB else rail.faceA
        val heading = entry.oppositeFace
        var target: BlockFace? = null
        for (line in listOf(sign.line3, sign.line4)) {
            if (line.isBlank()) continue
            val parts = line.split(':', limit = 2)
            val dirText = parts[0].trim().split(' ', limit = 2)
            val condition = if (parts.size > 1) parts[1] else dirText.getOrElse(1) { "" }
            val face = relative(dirText[0], heading) ?: continue
            if (SignTypes.matches(train, null, condition)) {
                target = face
                break
            }
        }
        if (target == null) {
            val dest = train.props.destination ?: return false
            target = plugin.router.chooseFace(train, block, entry, dest) ?: return false
        }
        val shape = RailSegment.shapeBetween(entry, target) ?: return false
        val data = block.blockData as? Rail ?: return false
        if (shape !in data.shapes || data.shape == shape) return false
        data.shape = shape
        block.setBlockData(data, false)
        return true
    }

    override fun onEnter(ctx: SignContext) {
        val head = ctx.train.path.head
        val rail = head.segment as? RailSegment ?: return
        if (rail.key != ctx.rail) return
        if (apply(ctx.plugin, ctx.train, head, ctx.sign)) ctx.train.path.refreshHead(ctx.plugin.graph)
    }
}

/** 発車・加速。3 行目: 速さ / 4 行目: 向き と 加速度 */
object Launch : SignType("launch", "launcher") {
    override val usage = "3 行目: 速さ (0.4 や 60km) / 4 行目: 向き 加速度"

    override fun validate(sign: ActionSign) =
        if (Text.parseSpeed(sign.line3) == null) "3 行目に速さを書いてください (例: 0.4 / 60km)" else null

    override fun onEnter(ctx: SignContext) {
        val speed = Text.parseSpeed(ctx.sign.line3) ?: return
        val opts = ctx.sign.line4.split(' ').filter { it.isNotBlank() }
        val dir = opts.firstNotNullOfOrNull { LaunchDirection.parse(it) } ?: LaunchDirection.Continue
        val accel = opts.firstNotNullOfOrNull { t -> t.toDoubleOrNull().takeIf { LaunchDirection.parse(t) == null } }
        ctx.train.action = LaunchAction(speed, accel, dir)
    }
}

/** 最高速度を変える。3 行目: 速さ */
object SpeedLimit : SignType("speed", "speedlimit", "maxspeed") {
    override val usage = "3 行目: 最高速度 (0.4 や 60km)"

    override fun validate(sign: ActionSign) = if (Text.parseSpeed(sign.line3) == null) "3 行目に速さを書いてください" else null

    override fun onEnter(ctx: SignContext) {
        Text.parseSpeed(ctx.sign.line3)?.let { ctx.train.props.speedLimit = it }
    }
}

/** 設定を変える。3, 4 行目: 「名前 値」 */
object PropertySign : SignType("property", "prop", "set") {
    override val usage = "3, 4 行目: 設定の名前 値 (例: gravity false)"

    override fun validate(sign: ActionSign): String? {
        for (line in listOf(sign.line3, sign.line4)) {
            if (line.isBlank()) continue
            val key = line.split(' ', limit = 2)[0]
            if (PropertyKeys.find(key) == null) return "設定「$key」はありません"
        }
        return null
    }

    override fun onEnter(ctx: SignContext) {
        for (line in listOf(ctx.sign.line3, ctx.sign.line4)) {
            if (line.isBlank()) continue
            val parts = line.split(' ', limit = 2)
            PropertyKeys.find(parts[0])?.set?.invoke(ctx.train, ctx.cart, parts.getOrElse(1) { "" })
        }
    }
}

/** タグを付け外しする。3, 4 行目: +タグ (付ける) / -タグ (外す) / =タグ (それだけにする) */
object TagSign : SignType("tag", "tags", "settag") {
    override val usage = "3, 4 行目: +付けるタグ -外すタグ =置き換えるタグ"

    override fun onEnter(ctx: SignContext) {
        val tags = ctx.cart?.props?.tags ?: ctx.train.props.tags
        for (token in ctx.sign.text34.split(' ').filter { it.isNotBlank() }) {
            when (token[0]) {
                '+' -> tags += token.substring(1)
                '-' -> tags -= token.substring(1)
                '=' -> { tags.clear(); tags += token.substring(1) }
                else -> tags += token
            }
        }
    }
}

/** 乗客を降ろす */
object Eject : SignType("eject") {
    override val usage = "3, 4 行目は使いません"

    override fun onEnter(ctx: SignContext) {
        val carts = ctx.cart?.let { listOf(it) } ?: ctx.train.carts
        for (cart in carts) ctx.plugin.trains.eject(cart)
    }
}

/** 近くのプレイヤーを乗せる。3 行目: 半径 (既定 3) */
object Enter : SignType("enter", "board") {
    override val usage = "3 行目: 乗せる範囲の半径"

    override fun onEnter(ctx: SignContext) {
        val radius = ctx.sign.line3.toDoubleOrNull() ?: 3.0
        val center = ctx.rail.center()
        val world = ctx.train.world
        for (player in world.getNearbyPlayers(org.bukkit.Location(world, center.x, center.y, center.z), radius)) {
            if (player.isInsideVehicle || !player.hasPermission("torotrain.ride")) continue
            val carts = ctx.cart?.let { listOf(it) } ?: ctx.train.carts
            carts.firstOrNull { it.props.allowEnter && it.visual?.seat(player, null) == true }
        }
    }
}

/** 信号機。信号があるあいだ ([!train] なら信号がないあいだ)、手前で列車を止める */
object Blocker : SignType("blocker", "signal") {
    override val usage = "レッドストーンの信号があるあいだ手前で止める ([!train] で逆)"

    override fun blocks(plugin: TOROTrain, sign: ActionSign, train: Train): String? {
        val stop = if (sign.power == ActionSign.PowerMode.UNPOWERED) !sign.isPowered() else sign.isPowered()
        return if (stop) "信号" else null
    }
}

/** 前の列車との間隔を待つ。3 行目: 空いているべき距離 / 4 行目: 空いてから待つ秒数 */
object WaitSign : SignType("wait") {
    override val usage = "3 行目: 空いているべき距離 / 4 行目: 空いてから待つ秒数"

    override fun onEnter(ctx: SignContext) {
        if (ctx.cart != null) return
        val distance = ctx.sign.line3.toDoubleOrNull() ?: 20.0
        val delay = Text.parseTicks(ctx.sign.line4) ?: 0
        val train = ctx.train
        val speed = train.speed.takeIf { it > 0.05 } ?: train.props.speedLimit
        val trains = ctx.plugin.trains
        if (trains.clearAhead(train, distance)) return
        train.action = SignTypes.chain(
            StopAfter(0.0),
            WaitAction(0, "前の列車待ち") { trains.clearAhead(train, distance) },
            WaitAction(delay, "待機"),
            LaunchAction(speed, null),
        )
    }
}

/**
 * 排他の区間。入口の看板 (4 行目 enter) から出口の看板 (4 行目 leave) までは 1 本の列車しか入れない。
 * 3 行目: 区間の名前
 */
object Mutex : SignType("mutex") {
    override val usage = "3 行目: 区間の名前 / 4 行目: enter (入口) か leave (出口)"

    override fun validate(sign: ActionSign) = if (sign.line3.isBlank()) "3 行目に区間の名前を書いてください" else null

    private fun isLeave(sign: ActionSign) = sign.line4.equals("leave", true) || sign.line4.equals("exit", true)

    override fun blocks(plugin: TOROTrain, sign: ActionSign, train: Train): String? {
        if (isLeave(sign)) return null
        val holder = plugin.mutexes.holder(sign.line3) ?: return null
        return if (holder != train.id) "区間 ${sign.line3}" else null
    }

    override fun onEnter(ctx: SignContext) {
        if (isLeave(ctx.sign)) ctx.plugin.mutexes.release(ctx.sign.line3, ctx.train.id)
        else ctx.plugin.mutexes.tryAcquire(ctx.sign.line3, ctx.train.id)
    }
}

/** 乗客にメッセージを出す。3, 4 行目: 文章 (& の色記号を使える)。2 行目に title と書くと画面の中央に出す */
object Announce : SignType("announce", "message", "msg") {
    override val usage = "3, 4 行目: 文章。2 行目に title を足すと画面の中央に出す"

    override fun onEnter(ctx: SignContext) {
        val text = Text.color(ctx.sign.text34)
        val title = ctx.sign.args.any { it.equals("title", true) }
        for (p in ctx.passengers()) {
            if (title) p.showTitle(Title.title(text, net.kyori.adventure.text.Component.empty()))
            else p.sendMessage(text)
        }
    }
}

/** 音を鳴らす。3 行目: 音の名前 / 4 行目: 音量 高さ。2 行目に world と書くと周りにも聞こえる */
object SoundSign : SignType("sound", "playsound") {
    override val usage = "3 行目: 音の名前 (block.note_block.bell など) / 4 行目: 音量 高さ"

    override fun validate(sign: ActionSign) = if (Key.parseable(sign.line3.lowercase())) null else "3 行目の音の名前が正しくありません"

    override fun onEnter(ctx: SignContext) {
        val nums = ctx.sign.line4.split(' ').mapNotNull { it.toFloatOrNull() }
        val key = ctx.sign.line3.lowercase()
        if (!Key.parseable(key)) return
        val sound = Sound.sound(Key.key(key), Sound.Source.MASTER, nums.getOrElse(0) { 1f }, nums.getOrElse(1) { 1f })
        if (ctx.sign.args.any { it.equals("world", true) }) {
            val c = ctx.rail.center()
            ctx.train.world.playSound(sound, c.x, c.y, c.z)
        } else {
            ctx.passengers().forEach { it.playSound(sound, Sound.Emitter.self()) }
        }
    }
}

/** パーティクルを出す。3 行目: 種類 / 4 行目: 数 */
object Effect : SignType("effect", "particle") {
    override val usage = "3 行目: パーティクルの種類 (cloud など) / 4 行目: 数"

    private fun particle(name: String) = runCatching { Particle.valueOf(name.uppercase(Locale.ROOT)) }.getOrNull()

    override fun validate(sign: ActionSign) = if (particle(sign.line3) == null) "パーティクル「${sign.line3}」はありません" else null

    override fun onEnter(ctx: SignContext) {
        val particle = particle(ctx.sign.line3) ?: return
        if (particle.dataType != Void::class.java) return
        val count = ctx.sign.line4.toIntOrNull() ?: 10
        val c = ctx.rail.center()
        ctx.train.world.spawnParticle(particle, c.x, c.y + 0.5, c.z, count, 0.3, 0.3, 0.3, 0.02)
    }
}

/** 別の場所の出入口へ列車を移す。3 行目: 行き先の出入口の名前 / 4 行目: この出入口の名前 */
object Teleport : SignType("teleport", "portal", "tp") {
    override val usage = "3 行目: 移る先の出入口の名前 / 4 行目: この出入口の名前。2 行目に向き (north など) を足せる"

    override fun onEnter(ctx: SignContext) {
        if (ctx.cart != null) return
        if (ctx.sign.line4.isNotBlank()) ctx.plugin.places.setPortal(ctx.sign.line4, ctx.rail)
        val target = ctx.plugin.places.portal(ctx.sign.line3) ?: return
        if (target == ctx.rail) return
        val face = ctx.sign.args.firstNotNullOfOrNull { (LaunchDirection.parse(it) as? LaunchDirection.Compass)?.face }
        // 看板の処理の途中で列車を動かさないよう、次の tick に移す
        ctx.plugin.server.scheduler.runTask(ctx.plugin, Runnable { ctx.plugin.trains.teleport(ctx.train, target, face) })
    }
}

/**
 * 信号が来たら、保存した列車を出す。3 行目: 保存した列車の名前 / 4 行目: 向き 速さ
 */
object Spawn : SignType("spawn", "spawner") {
    override val usage = "3 行目: 保存した列車の名前 / 4 行目: 向き 速さ。信号が来たときに出す"

    override fun validate(sign: ActionSign) = if (sign.line3.isBlank()) "3 行目に保存した列車の名前を書いてください" else null

    override fun onPower(plugin: TOROTrain, sign: ActionSign, powered: Boolean) {
        if (!powered) return
        val rail = plugin.signIndex.railFor(sign.block) ?: return
        val opts = sign.line4.split(' ').filter { it.isNotBlank() }
        val face = opts.firstNotNullOfOrNull { (LaunchDirection.parse(it) as? LaunchDirection.Compass)?.face }
        val speed = opts.firstNotNullOfOrNull { t -> Text.parseSpeed(t).takeIf { LaunchDirection.parse(t) == null } }
        val train = plugin.trains.spawnSaved(sign.line3, rail, face, speed)
        plugin.debug { if (train != null) "看板 (${sign.key}) が ${train.name} を出しました" else "看板 (${sign.key}) は列車を出せませんでした (保存した列車 ${sign.line3} か、線路 ${rail.x},${rail.y},${rail.z} がない)" }
    }
}

/** 列車を消す (乗客は降ろす) */
object Destroy : SignType("destroy", "remove") {
    override val usage = "3, 4 行目: 条件 (省略可。例: tag=回送)"

    override fun onEnter(ctx: SignContext) {
        if (ctx.cart != null) return
        val cond = ctx.sign.text34
        if (cond.isNotBlank() && !SignTypes.matches(ctx.train, null, cond)) return
        ctx.plugin.server.scheduler.runTask(ctx.plugin, Runnable { ctx.plugin.trains.destroy(ctx.train) })
    }
}

/** 列車の向きを反対にする (速さはそのまま) */
object Reverse : SignType("reverse", "flip") {
    override val usage = "3, 4 行目: 条件 (省略可)"

    override fun onEnter(ctx: SignContext) {
        if (ctx.cart != null) return
        val cond = ctx.sign.text34
        if (cond.isNotBlank() && !SignTypes.matches(ctx.train, null, cond)) return
        ctx.plugin.server.scheduler.runTask(ctx.plugin, Runnable { ctx.train.reverse() })
    }
}

/** この先の看板をいくつか無視させる。3 行目: 枚数 */
object Skip : SignType("skip") {
    override val usage = "3 行目: 無視させる看板の枚数 / 4 行目: 条件 (省略可)"

    override fun onEnter(ctx: SignContext) {
        if (ctx.sign.line4.isNotBlank() && !SignTypes.matches(ctx.train, ctx.cart, ctx.sign.line4)) return
        ctx.train.skipSigns = ctx.sign.line3.toIntOrNull() ?: 1
    }
}

/** 列車がいるあいだ、看板の周りのレバーを入れる。3 行目: 条件 (省略可) */
object Detector : SignType("detector", "detect") {
    override val usage = "3 行目: 条件 (省略可)。列車がいるあいだ周りのレバーを入れる"

    override fun onEnter(ctx: SignContext) {
        if (SignTypes.matches(ctx.train, ctx.cart, ctx.sign.line3)) SignTypes.output(ctx.sign.block, true)
    }

    override fun onLeave(ctx: SignContext) {
        SignTypes.output(ctx.sign.block, false)
    }
}

/** 経路を決める。3, 4 行目: 行き先を空白で区切って並べる */
object RouteSign : SignType("route") {
    override val usage = "3, 4 行目: 行き先を空白で区切って並べる"

    override fun onEnter(ctx: SignContext) {
        val route = ctx.sign.text34.split(' ', ',').filter { it.isNotBlank() }
        ctx.train.props.route.clear()
        ctx.train.props.route += route
        ctx.train.props.destination = route.firstOrNull()
    }
}
