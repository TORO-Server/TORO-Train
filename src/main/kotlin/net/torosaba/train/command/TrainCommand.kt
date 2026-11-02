package net.torosaba.train.command

import net.torosaba.train.TOROTrain
import net.torosaba.train.model.AttachmentCartModel
import net.torosaba.train.model.BlockCartModel
import net.torosaba.train.model.DefaultCartModel
import net.torosaba.train.model.ItemCartModel
import net.torosaba.train.signs.SignTypes
import net.torosaba.train.track.RailSegment
import net.torosaba.train.track.TrackBlocks
import net.torosaba.train.track.TrackNode
import net.torosaba.train.train.Cart
import net.torosaba.train.train.LaunchAction
import net.torosaba.train.train.MasconProfile
import net.torosaba.train.train.PropertyKeys
import net.torosaba.train.train.Train
import net.torosaba.train.util.Text
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.Locale
import java.util.UUID

/** /tt (/toro-train) のコマンド */
class TrainCommand(private val plugin: TOROTrain) : TabExecutor {
    private class Fail(message: String) : Exception(message)

    private fun fail(message: String): Nothing = throw Fail(message)

    // プレイヤーが最後に置いた・選んだ自由な線路のノード
    private val lastNode = HashMap<UUID, Int>()

    private fun msg(sender: CommandSender, text: String) = sender.sendMessage(Text.color("&6[TORO-Train] &r$text"))

    private fun player(sender: CommandSender): Player = sender as? Player ?: fail("プレイヤーだけが使えます")

    private fun need(sender: CommandSender, permission: String) {
        if (!sender.hasPermission(permission)) fail("権限がありません ($permission)")
    }

    /** 選択や範囲を覚えるときの、送った人の ID (コンソールやコマンドブロックは CONSOLE) */
    private fun senderId(sender: CommandSender): UUID = (sender as? Player)?.uniqueId ?: CONSOLE

    private fun train(sender: CommandSender): Train {
        val p = sender as? Player
            ?: return plugin.trains.selected[CONSOLE]?.let { plugin.trains.trains[it] } ?: fail("先に /tt select <名前> で列車を選んでください")
        return plugin.trains.trainOf(p) ?: fail("列車が選ばれていません。乗るか、近くで /tt select してください")
    }

    private fun owned(sender: CommandSender): Train {
        val t = train(sender)
        if (sender is Player && !t.isOwner(sender) && !sender.hasPermission("torotrain.admin")) fail("この列車の持ち主ではありません")
        return t
    }

    /** 対象の車両。乗っている車両、なければ選んでいる列車の先頭の車両 */
    private fun cart(sender: CommandSender): Cart {
        val p = player(sender)
        return plugin.trains.cartOf(p) ?: owned(sender).carts.firstOrNull() ?: fail("車両がありません")
    }

    private fun speedArg(text: String?): Double =
        text?.let { Text.parseSpeed(it) }?.takeIf { it >= 0 } ?: fail("速さを書いてください (0.4 / 60km / 10m/s)")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        try {
            run(sender, args.toList())
        } catch (e: Fail) {
            msg(sender, "&c${e.message}")
        } catch (e: IllegalStateException) {
            msg(sender, "&c${e.message}")
        }
        return true
    }

    private fun run(sender: CommandSender, args: List<String>) {
        need(sender, "torotrain.command")
        val sub = args.firstOrNull()?.lowercase(Locale.ROOT) ?: "help"
        val rest = args.drop(1)
        when (sub) {
            "help", "?" -> help(sender)
            "create" -> create(sender, rest)
            "spawn" -> spawn(sender, rest)
            "save" -> {
                val name = rest.firstOrNull() ?: fail("/tt save <名前>")
                plugin.saved.save(name, owned(sender))
                msg(sender, "&a列車を「$name」として保存しました")
            }
            "saved" -> saved(sender, rest)
            "list" -> list(sender)
            "info" -> info(sender, train(sender))
            "select" -> select(sender, rest)
            "destroy" -> {
                val t = owned(sender)
                plugin.trains.destroy(t)
                msg(sender, "&a列車 ${t.name} を消しました")
            }
            "destroyall" -> {
                need(sender, "torotrain.admin")
                val world = rest.firstOrNull()?.let { plugin.server.getWorld(it) ?: fail("ワールド $it はありません") }
                msg(sender, "&a列車を ${plugin.trains.destroyAll(world)} 本消しました")
            }
            "speed" -> {
                val t = owned(sender)
                t.speed = speedArg(rest.firstOrNull()).coerceAtMost(plugin.settings.maxSpeed)
                t.action = null
                msg(sender, "&a速さを ${Text.speed(t.speed)} にしました")
            }
            "launch" -> {
                val t = owned(sender)
                val speed = speedArg(rest.firstOrNull())
                t.action = LaunchAction(speed, rest.getOrNull(1)?.let { Text.parseSpeed(it) })
                msg(sender, "&a${Text.speed(speed)} まで加速します")
            }
            "stop" -> {
                val t = owned(sender)
                t.speed = 0.0
                t.resumeSpeed = 0.0
                t.action = null
                msg(sender, "&a止めました")
            }
            "reverse" -> {
                owned(sender).reverse()
                msg(sender, "&a向きを反対にしました")
            }
            "property", "prop" -> property(sender, rest, null)
            "cart" -> cartCommand(sender, rest)
            "dest", "destination" -> {
                val t = owned(sender)
                t.props.destination = rest.joinToString(" ").takeUnless { it.isBlank() || it.equals("none", true) }
                msg(sender, "&a行き先: ${t.props.destination ?: "なし"}")
            }
            "route" -> {
                val t = owned(sender)
                // load <名前> は保存した経路 (TrainCarts の routes.yml から引き継いだものなど)
                val list = if (rest.firstOrNull()?.equals("load", true) == true) {
                    val name = rest.getOrNull(1) ?: fail("/tt route load <経路の名前>")
                    plugin.places.route(name) ?: fail("経路 $name はありません")
                } else if (rest.firstOrNull()?.equals("none", true) == true) {
                    emptyList()
                } else {
                    rest
                }
                t.props.route.clear()
                t.props.route += list
                t.props.destination = t.props.route.firstOrNull()
                msg(sender, "&a経路: ${t.props.route.joinToString(" → ").ifEmpty { "なし" }}")
            }
            "routes" -> msg(sender, "保存した経路: ${plugin.places.routes.keys.sorted().joinToString(", ").ifEmpty { "なし" }}")
            "dests", "destinations" -> {
                val names = plugin.places.destinations.keys.sorted() + plugin.network.nodes.values.mapNotNull { it.name }
                msg(sender, "行き先: ${names.distinct().joinToString(", ").ifEmpty { "なし" }}")
            }
            "tag" -> tag(sender, rest)
            "name" -> {
                val t = owned(sender)
                val name = rest.firstOrNull() ?: fail("/tt name <名前>")
                if (plugin.trains.byName(name)?.let { it !== t } == true) fail("その名前の列車はもうあります")
                t.name = name
                msg(sender, "&a名前を $name にしました")
            }
            "split" -> {
                val t = owned(sender)
                val index = rest.firstOrNull()?.toIntOrNull() ?: fail("/tt split <何両目から>")
                val other = plugin.trains.split(t, index - 1) ?: fail("その位置では切り離せません")
                msg(sender, "&a${index} 両目から後ろを ${other.name} として切り離しました")
            }
            "enter" -> {
                val p = player(sender)
                val t = train(sender)
                val near = p.location.toVector().toVector3d()
                val ok = t.carts.sortedBy { it.position.distanceSquared(near) }.any { it.visual?.seat(p, near) == true }
                if (!ok) fail("空いている座席がありません")
            }
            "exit" -> player(sender).leaveVehicle()
            "model" -> model(sender, rest)
            "wand" -> {
                need(sender, "torotrain.admin")
                player(sender).inventory.addItem(plugin.wandItem())
                msg(sender, "&a範囲を選ぶ道具を渡しました (左クリック: 1 点目、右クリック: 2 点目)")
            }
            "pos1", "pos2" -> {
                need(sender, "torotrain.admin")
                val block = if (rest.size >= 3) {
                    val xyz = rest.take(3).map { it.toIntOrNull() ?: fail("/tt $sub [x y z [ワールド]]") }
                    val world = rest.getOrNull(3)?.let { plugin.server.getWorld(it) ?: fail("ワールド $it はありません") }
                        ?: (sender as? Player)?.world ?: plugin.server.worlds.first()
                    world.getBlockAt(xyz[0], xyz[1], xyz[2])
                } else {
                    val p = player(sender)
                    p.getTargetBlockExact(10) ?: p.location.block
                }
                plugin.blockTrains.setPos(senderId(sender), if (sub == "pos1") 1 else 2, block.location)
                msg(sender, "&a${if (sub == "pos1") 1 else 2} 点目: ${block.x}, ${block.y}, ${block.z}")
            }
            "blocks" -> blocks(sender, rest)
            "models" -> models(sender, rest)
            "track" -> track(sender, rest)
            "signs" -> {
                for (type in SignTypes.all) msg(sender, "&e${type.names.joinToString("/")} &7- ${type.usage}")
            }
            "reload" -> {
                need(sender, "torotrain.admin")
                plugin.reloadAll()
                msg(sender, "&a設定を読み込み直しました")
            }
            "saveall" -> {
                need(sender, "torotrain.admin")
                plugin.saveAllData()
                msg(sender, "&a保存しました")
            }
            "import" -> {
                need(sender, "torotrain.admin")
                if (rest.firstOrNull()?.lowercase() != "traincarts") fail("/tt import traincarts")
                val r = plugin.trainCarts.importAll()
                msg(sender, "&aTrainCarts から引き継ぎました: 保存した列車 ${r.saved} 本、走っていた列車 ${r.live} 本 (置き換えを待っている列車 ${plugin.trainCarts.pendingCount()} 本)、モデル ${r.models} 個、経路 ${r.routes} 個、行き先 ${r.destinations} 個")
                if (r.missing.isNotEmpty()) msg(sender, "&c見つからないスケマティック: ${r.missing.joinToString(", ")}")
                if (r.skipped.isNotEmpty()) msg(sender, "&e描かなかったもの: ${r.skipped.joinToString(", ")}")
            }
            else -> fail("サブコマンド $sub はありません。/tt help")
        }
    }

    private fun help(sender: CommandSender) {
        val lines = listOf(
            "create [両数] [ブロック] &7- 見ている線路に列車を出す",
            "spawn <保存名> [速さ] / save <名前> / saved [delete <名前>]",
            "list / info / select [名前|nearest] / destroy / destroyall [ワールド]",
            "speed <速さ> / launch <速さ> [加速度] / stop / reverse",
            "property <名前> [値] / cart property <名前> [値]",
            "dest <行き先> / route <行き先...>|load <名前> / routes / dests / tag add|remove|clear <タグ>",
            "name <名前> / split <何両目から> / enter / exit / cart destroy (一両だけ消す)",
            "model default|blocks <名前>|item [大きさ]|yaw <度>|offset <x> <y> <z>|length <長さ>",
            "wand / pos1 / pos2 / blocks capture <名前> [高さ] / blocks convert [名前] / blocks restore [force]",
            "models [delete <名前>] / track ... / signs / reload / saveall / import traincarts",
        )
        msg(sender, "&eTORO-Train ${plugin.pluginMeta.version} のコマンド (/tt):")
        lines.forEach { sender.sendMessage(Text.color("&7 /tt &f$it")) }
    }

    private fun lookedRail(p: Player) = p.getTargetBlockExact(8)?.takeIf { TrackBlocks.isTrack(it) }
        ?: p.location.block.takeIf { TrackBlocks.isTrack(it) }
        ?: fail("線路を見ながら実行してください")

    /**
     * 列車を置く線路のブロックと、進む向き。引数に「at x y z [ワールド] [向き]」があればその場所、
     * なければプレイヤーが見ている線路とプレイヤーの向き
     */
    private fun placement(sender: CommandSender, args: List<String>): Triple<org.bukkit.block.Block, BlockFace?, Vector3d?> {
        val at = args.indexOfFirst { it.equals("at", true) }
        if (at < 0) {
            val p = player(sender)
            return Triple(lookedRail(p), null, p.location.direction.toVector3d())
        }
        val xyz = args.drop(at + 1).take(3).map { it.toIntOrNull() ?: fail("at <x> <y> <z> [ワールド] [向き]") }
        if (xyz.size < 3) fail("at <x> <y> <z> [ワールド] [向き]")
        val extra = args.drop(at + 4)
        val world = extra.firstNotNullOfOrNull { plugin.server.getWorld(it) } ?: (sender as? Player)?.world ?: plugin.server.worlds.first()
        val face = extra.firstOrNull { plugin.server.getWorld(it) == null }?.let { facingArg(sender, it) }
        return Triple(world.getBlockAt(xyz[0], xyz[1], xyz[2]), face, null)
    }

    private fun beforeAt(args: List<String>) = args.indexOfFirst { it.equals("at", true) }.let { if (it < 0) args else args.take(it) }

    private fun create(sender: CommandSender, args: List<String>) {
        need(sender, "torotrain.create")
        val (block, face, look) = placement(sender, args)
        val own = beforeAt(args)
        val count = own.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
        val material = own.getOrNull(1)?.let { Material.matchMaterial(it)?.takeIf { m -> m.isBlock } ?: fail("ブロック $it はありません") }
            ?: Material.IRON_BLOCK
        val models = List(count) { DefaultCartModel(material) }
        val length = models.sumOf { it.length(plugin.models) + plugin.settings.cartGap }
        val path = plugin.trains.pathAt(block, face, length, look) ?: fail("線路がありません")
        val t = plugin.trains.create(path, models)
        (sender as? Player)?.let { t.props.owners += it.uniqueId }
        plugin.trains.selected[senderId(sender)] = t.id
        msg(sender, "&a列車 ${t.name} ($count 両) を出しました")
    }

    private fun spawn(sender: CommandSender, args: List<String>) {
        need(sender, "torotrain.create")
        val own = beforeAt(args)
        val name = own.firstOrNull() ?: fail("/tt spawn <保存名> [速さ] [at x y z [ワールド] [向き]]")
        val speed = own.getOrNull(1)?.let { speedArg(it) }
        val (block, face, look) = placement(sender, args)
        // 保存した列車の名前でなければ、TrainCarts の spawn の書き方 (3m、2st など) として読む
        val t = if (plugin.saved.get(name) != null) {
            plugin.trains.spawnSaved(name, block, face, speed, look) ?: fail("線路がありません")
        } else {
            plugin.trains.spawnPattern(name, block, face, speed, look) ?: fail("保存した列車 $name はありません")
        }
        (sender as? Player)?.let { t.props.owners += it.uniqueId }
        plugin.trains.selected[senderId(sender)] = t.id
        msg(sender, "&a「$name」を ${t.name} として出しました")
    }

    private fun saved(sender: CommandSender, args: List<String>) {
        if (args.firstOrNull()?.equals("delete", true) == true) {
            val name = args.getOrNull(1) ?: fail("/tt saved delete <名前>")
            if (!plugin.saved.delete(name)) fail("保存した列車 $name はありません")
            msg(sender, "&a「$name」を消しました")
            return
        }
        msg(sender, "保存した列車: ${plugin.saved.names().joinToString(", ").ifEmpty { "なし" }}")
    }

    private fun list(sender: CommandSender) {
        val trains = plugin.trains.trains.values
        msg(sender, "列車 ${trains.size} 本:")
        for (t in trains) {
            val p = t.carts.firstOrNull()?.position ?: Vector3d()
            sender.sendMessage(Text.color(String.format(Locale.ROOT, " &f%s &7%d 両 %s %s (%.0f, %.0f, %.0f) %s",
                t.displayName(), t.carts.size, Text.speed(t.speed), t.world.name, p.x, p.y, p.z, t.props.destination?.let { "→ $it" } ?: "")))
        }
    }

    private fun info(sender: CommandSender, t: Train) {
        msg(sender, "&e${t.displayName()} &7(${t.name})")
        val head = t.path.locate(0.0).point()
        val lines = listOf(
            "車両: ${t.carts.size} 両 / 長さ ${String.format(Locale.ROOT, "%.1f", t.length())} ブロック",
            "先頭: ${String.format(Locale.ROOT, "%.2f, %.2f, %.2f", head.x, head.y, head.z)} (${t.path.head.segment})" +
                if (t.derailed) " &c線路から外れている" else "",
            "速さ: ${Text.speed(t.speed)} / 最高 ${Text.speed(t.props.speedLimit)}",
            "動作: ${t.action?.describe() ?: "なし"}${t.blockedBy?.let { " (止まっている理由: $it)" } ?: ""}",
            "マスコン: " + (t.masconDriver?.let { id ->
                String.format(Locale.ROOT, "%s / %s / %.3f ブロック/tick", plugin.server.getPlayer(id)?.name ?: id.toString(), MasconProfile.label(t.notch), t.speed)
            } ?: "なし"),
            "行き先: ${t.props.destination ?: "なし"}  経路: ${t.props.route.joinToString(" → ").ifEmpty { "なし" }}",
            "タグ: ${t.props.tags.joinToString(", ").ifEmpty { "なし" }}",
            "乗客: ${t.passengers().joinToString(", ") { it.name }.ifEmpty { "なし" }}",
        )
        lines.forEach { sender.sendMessage(Text.color(" &7$it")) }
        for ((i, cart) in t.carts.withIndex()) {
            sender.sendMessage(Text.color(" &8${i + 1}. &7${cart.model.describe()}${if (cart.flipped) " (逆向き)" else ""}"))
        }
    }

    private fun select(sender: CommandSender, args: List<String>) {
        val t = when (val name = args.firstOrNull()) {
            null, "nearest" -> {
                val p = player(sender)
                plugin.trains.nearest(p.location.toVector().toVector3d(), p.world, 64.0)
            }
            else -> plugin.trains.byName(name)
        } ?: fail("列車が見つかりません")
        plugin.trains.selected[senderId(sender)] = t.id
        msg(sender, "&a列車 ${t.displayName()} を選びました")
    }

    private fun property(sender: CommandSender, args: List<String>, cart: Cart?) {
        val t = cart?.train ?: owned(sender)
        val name = args.firstOrNull()
        if (name == null) {
            for (key in PropertyKeys.keys) sender.sendMessage(Text.color(" &e${key.name} &7= &f${key.get(t, cart)}"))
            return
        }
        val key = PropertyKeys.find(name) ?: fail("設定 $name はありません")
        if (args.size < 2) {
            msg(sender, "${key.name} = ${key.get(t, cart)}")
            return
        }
        if (!key.set(t, cart, args.drop(1).joinToString(" "))) fail("値「${args.drop(1).joinToString(" ")}」は使えません")
        msg(sender, "&a${key.name} を ${key.get(t, cart)} にしました")
    }

    private fun cartCommand(sender: CommandSender, args: List<String>) {
        val c = cart(sender)
        // 乗っているだけの人が、持ち主でない列車の車両を変えられないようにする (見るだけならよい)
        fun needOwner() {
            if (sender is Player && !c.train.isOwner(sender) && !sender.hasPermission("torotrain.admin")) fail("この列車の持ち主ではありません")
        }
        when (args.firstOrNull()?.lowercase()) {
            "property", "prop" -> {
                if (args.size >= 3) needOwner()
                property(sender, args.drop(1), c)
            }
            "tag" -> {
                if (args.getOrNull(1)?.lowercase() !in listOf(null, "list")) needOwner()
                tagOn(sender, c.props.tags, args.drop(1))
            }
            "eject" -> {
                needOwner()
                plugin.trains.eject(c)
            }
            "destroy", "remove" -> {
                needOwner()
                plugin.trains.removeCart(c)
                msg(sender, "&a車両を一両消しました")
            }
            else -> fail("/tt cart property|tag|eject|destroy ...")
        }
    }

    private fun tag(sender: CommandSender, args: List<String>) = tagOn(sender, owned(sender).props.tags, args)

    private fun tagOn(sender: CommandSender, tags: MutableSet<String>, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "add" -> tags += args.drop(1)
            "remove" -> tags -= args.drop(1).toSet()
            "clear" -> tags.clear()
            null, "list" -> {}
            else -> fail("/tt tag add|remove|clear|list <タグ>")
        }
        msg(sender, "タグ: ${tags.joinToString(", ").ifEmpty { "なし" }}")
    }

    private fun model(sender: CommandSender, args: List<String>) {
        val c = cart(sender)
        if (sender is Player && !c.train.isOwner(sender) && !sender.hasPermission("torotrain.admin")) fail("この列車の持ち主ではありません")
        val current = c.model
        when (args.firstOrNull()?.lowercase()) {
            "default" -> {
                val material = args.getOrNull(1)?.let { Material.matchMaterial(it) ?: fail("ブロック $it はありません") } ?: Material.IRON_BLOCK
                c.model = DefaultCartModel(material)
            }
            "blocks" -> {
                val name = args.getOrNull(1) ?: fail("/tt model blocks <モデルの名前>")
                if (plugin.models.get(name) == null) fail("モデル $name はありません")
                c.model = BlockCartModel(name.lowercase())
            }
            "item" -> {
                val item = player(sender).inventory.itemInMainHand.takeUnless { it.type.isAir } ?: fail("アイテムを手に持ってください")
                val scale = args.getOrNull(1)?.toFloatOrNull() ?: 1f
                c.model = ItemCartModel(ItemStack(item).asOne(), scale)
            }
            "yaw" -> {
                val yaw = args.getOrNull(1)?.toFloatOrNull() ?: fail("/tt model yaw <度>")
                c.model = when (current) {
                    is BlockCartModel -> BlockCartModel(current.name, current.offset, yaw, current.lengthOverride, current.extraRotation, current.scale, current.fixedSeats, current.wheelDistance)
                    is ItemCartModel -> ItemCartModel(current.item, current.scale, current.offset, yaw, current.length, current.width, current.height)
                    else -> fail("この車両のモデルは回せません")
                }
            }
            "offset" -> {
                val v = args.drop(1).mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 3 } ?: fail("/tt model offset <x> <y> <z>")
                val offset = Vector3f(v[0], v[1], v[2])
                c.model = when (current) {
                    is BlockCartModel -> BlockCartModel(current.name, offset, current.yaw, current.lengthOverride, current.extraRotation, current.scale, current.fixedSeats, current.wheelDistance)
                    is ItemCartModel -> ItemCartModel(current.item, current.scale, offset, current.yaw, current.length, current.width, current.height)
                    else -> fail("この車両のモデルは動かせません")
                }
            }
            "length" -> {
                val length = args.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.1 } ?: fail("/tt model length <長さ>")
                c.model = when (current) {
                    is BlockCartModel -> BlockCartModel(current.name, current.offset, current.yaw, length, current.extraRotation, current.scale, current.fixedSeats, current.wheelDistance)
                    is ItemCartModel -> ItemCartModel(current.item, current.scale, current.offset, current.yaw, length, current.width, current.height)
                    is DefaultCartModel -> DefaultCartModel(current.material, length)
                    is AttachmentCartModel -> current.withLength(length)
                }
            }
            else -> {
                msg(sender, "モデル: ${current.describe()}")
                return
            }
        }
        c.length = c.model.length(plugin.models)
        c.train.layout()
        msg(sender, "&aモデルを ${c.model.describe()} にしました")
    }

    /** 向きの引数 (north など)。なければプレイヤーの向いている方角 */
    private fun facingArg(sender: CommandSender, text: String?): BlockFace {
        text?.let { t ->
            return listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
                .firstOrNull { it.name.equals(t, true) || it.name.first().toString().equals(t, true) } ?: fail("向き $t が分かりません (north / east / south / west)")
        }
        return (sender as? Player)?.facing ?: fail("向き (north / east / south / west) を指定してください")
    }

    private fun blocks(sender: CommandSender, args: List<String>) {
        need(sender, "torotrain.admin")
        val id = senderId(sender)
        when (args.firstOrNull()?.lowercase()) {
            "capture" -> {
                val name = args.getOrNull(1) ?: fail("/tt blocks capture <名前> [線路からの高さ] [向き]")
                if (plugin.models.exists(name)) fail("モデル $name はもうあります")
                val y = args.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                val facing = facingArg(sender, args.getOrNull(3))
                val count = plugin.blockTrains.capture(id, name, facing, y)
                msg(sender, "&aモデル $name を保存しました ($count ブロック、前は ${facing.name.lowercase()})")
            }
            "convert" -> {
                val facing = facingArg(sender, args.getOrNull(2))
                val look = (sender as? Player)?.location?.direction?.toVector3d()
                    ?: Vector3d(facing.modX.toDouble(), 0.0, facing.modZ.toDouble())
                val t = plugin.blockTrains.convert(id, args.getOrNull(1)?.takeUnless { it == "-" }, look.setComponent(1, 0.0))
                (sender as? Player)?.let { t.props.owners += it.uniqueId }
                plugin.trains.selected[id] = t.id
                msg(sender, "&aブロックを列車 ${t.name} にしました。/tt blocks restore で元のブロックに戻せます")
            }
            "restore" -> {
                val t = owned(sender)
                val count = plugin.blockTrains.restore(t, args.getOrNull(1).equals("force", true))
                msg(sender, "&a列車をブロックに戻しました ($count ブロック)")
            }
            else -> fail("/tt blocks capture|convert|restore")
        }
    }

    private fun models(sender: CommandSender, args: List<String>) {
        if (args.firstOrNull()?.equals("delete", true) == true) {
            need(sender, "torotrain.admin")
            val name = args.getOrNull(1) ?: fail("/tt models delete <名前>")
            val used = plugin.trains.trains.values.any { t -> t.carts.any { it.model.usesBlockModel(name) } }
            if (used) fail("モデル $name は走っている列車が使っています")
            if (!plugin.models.delete(name)) fail("モデル $name はありません")
            msg(sender, "&aモデル $name を消しました")
            return
        }
        msg(sender, "モデル: ${plugin.models.names().joinToString(", ").ifEmpty { "なし" }}")
    }

    // ---------- 自由な線路 ----------

    private fun node(p: Player): TrackNode =
        lastNode[p.uniqueId]?.let { plugin.network.nodes[it] }?.takeIf { it.world == p.world.uid }
            ?: fail("ノードが選ばれていません (/tt track select)")

    private fun track(sender: CommandSender, args: List<String>) {
        need(sender, "torotrain.admin")
        val p = player(sender)
        val network = plugin.network
        when (args.firstOrNull()?.lowercase()) {
            "add" -> {
                val mode = args.getOrNull(1)?.lowercase()
                val pos = when (mode) {
                    "here" -> p.location.toVector().toVector3d()
                    "rail" -> {
                        val rail = RailSegment.of(lookedRail(p)) ?: fail("線路を見ながら実行してください")
                        val eye = p.location.toVector().toVector3d()
                        val a = rail.end(0)
                        val b = rail.end(1)
                        if (a.distanceSquared(eye) <= b.distanceSquared(eye)) a else b
                    }
                    else -> {
                        val b = p.getTargetBlockExact(32) ?: fail("ブロックを見ながら実行してください")
                        Vector3d(b.x + 0.5, b.y + 1 + RailSegment.HEIGHT, b.z + 0.5)
                    }
                }
                val previous = lastNode[p.uniqueId]?.let { network.nodes[it] }
                val node = network.add(p.world, pos)
                if (previous != null && previous.world == node.world && previous.pos.distance(node.pos) < 128 && "nolink" !in args) {
                    network.link(previous, node)
                }
                lastNode[p.uniqueId] = node.id
                msg(sender, "&aノード ${node.id} を置きました${if (previous != null && node.id in previous.links) " (${previous.id} とつなぎました)" else ""}")
            }
            "select" -> {
                val target = args.getOrNull(1)?.toIntOrNull()?.let { network.nodes[it] }
                    ?: network.nearest(p.world, p.location.toVector().toVector3d(), 16.0) ?: fail("近くにノードがありません")
                lastNode[p.uniqueId] = target.id
                msg(sender, "&aノード ${target.id} を選びました")
            }
            "remove" -> {
                val n = node(p)
                network.remove(n)
                lastNode.remove(p.uniqueId)
                msg(sender, "&aノード ${n.id} を消しました")
            }
            "link", "unlink" -> {
                val a = args.getOrNull(1)?.toIntOrNull()?.let { network.nodes[it] } ?: fail("/tt track ${args[0]} <ノード> [ノード]")
                val b = args.getOrNull(2)?.toIntOrNull()?.let { network.nodes[it] } ?: node(p)
                if (args[0].equals("link", true)) network.link(a, b) else network.unlink(a, b)
                msg(sender, "&aノード ${a.id} と ${b.id} を${if (args[0].equals("link", true)) "つなぎました" else "切り離しました"}")
            }
            "move" -> {
                val n = node(p)
                network.move(n, p.location.toVector().toVector3d())
                msg(sender, "&aノード ${n.id} を動かしました")
            }
            "dir" -> {
                val n = node(p)
                n.direction = if (args.getOrNull(1).equals("auto", true)) null else p.location.direction.toVector3d()
                network.changed()
                msg(sender, "&aノード ${n.id} の向きを${if (n.direction == null) "自動" else "見ている向き"}にしました")
            }
            "roll" -> {
                val n = node(p)
                n.roll = args.getOrNull(1)?.toDoubleOrNull() ?: fail("/tt track roll <度>")
                network.changed()
                msg(sender, "&aノード ${n.id} の傾きを ${n.roll} 度にしました")
            }
            "name" -> {
                val n = node(p)
                n.name = args.getOrNull(1)?.takeUnless { it.equals("none", true) }
                network.changed()
                msg(sender, "&aノード ${n.id} の名前: ${n.name ?: "なし"}")
            }
            "switch" -> {
                val n = node(p)
                val from = args.getOrNull(1)?.toIntOrNull() ?: fail("/tt track switch <来るノード (レールなら 0)> <進むノード>")
                val to = args.getOrNull(2)?.toIntOrNull() ?: fail("/tt track switch <来るノード> <進むノード>")
                if (to !in n.links) fail("ノード $to は ${n.id} につながっていません")
                n.switches[from] = to
                msg(sender, "&aノード ${n.id}: $from から来たら $to へ進みます")
            }
            "list" -> {
                val near = network.nodes.values.filter { it.world == p.world.uid }
                    .sortedBy { it.pos.distanceSquared(p.location.toVector().toVector3d()) }.take(15)
                for (n in near) sender.sendMessage(Text.color(String.format(Locale.ROOT, " &f%d &7(%.1f, %.1f, %.1f) → %s %s",
                    n.id, n.pos.x, n.pos.y, n.pos.z, n.links.joinToString(","), n.name ?: "")))
            }
            "show" -> {
                val seconds = args.getOrNull(1)?.toIntOrNull() ?: 10
                showTrack(p, seconds)
            }
            else -> fail("/tt track add [here|rail|nolink] | select [id] | remove | link <a> [b] | unlink <a> [b] | move | dir [auto] | roll <度> | name <名前> | switch <来る> <進む> | list | show [秒]")
        }
        network.save()
    }

    private fun showTrack(p: Player, seconds: Int) {
        var left = seconds * 4
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            if (left-- <= 0 || !p.isOnline) {
                task.cancel()
                return@runTaskTimer
            }
            val eye = p.location.toVector().toVector3d()
            val tmp = Vector3d()
            for (n in plugin.network.nodes.values) {
                if (n.world != p.world.uid || n.pos.distanceSquared(eye) > 96.0 * 96.0) continue
                p.spawnParticle(Particle.END_ROD, n.pos.x, n.pos.y + 0.2, n.pos.z, 1, 0.0, 0.0, 0.0, 0.0)
                for (o in n.links) {
                    if (o < n.id) continue
                    val other = plugin.network.nodes[o] ?: continue
                    val seg = plugin.network.segment(n, other)
                    var s = 0.0
                    while (s < seg.length) {
                        seg.point(s, tmp)
                        p.spawnParticle(Particle.ELECTRIC_SPARK, tmp.x, tmp.y, tmp.z, 1, 0.0, 0.0, 0.0, 0.0)
                        s += 0.5
                    }
                }
            }
        }, 0L, 5L)
    }

    // ---------- 補完 ----------

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        val subs = listOf(
            "help", "create", "spawn", "save", "saved", "list", "info", "select", "destroy", "destroyall", "speed", "launch",
            "stop", "reverse", "property", "cart", "dest", "route", "routes", "dests", "tag", "name", "split", "enter", "exit", "model",
            "wand", "pos1", "pos2", "blocks", "models", "track", "signs", "reload", "saveall", "import",
        )
        val last = args.lastOrNull() ?: ""
        val options: List<String> = when (args.size) {
            1 -> subs
            2 -> when (args[0].lowercase()) {
                "spawn" -> plugin.saved.names()
                "saved" -> listOf("delete")
                "select" -> listOf("nearest") + plugin.trains.trains.values.map { it.name }
                "property", "prop" -> PropertyKeys.keys.map { it.name }
                "cart" -> listOf("property", "tag", "eject", "destroy")
                "dest" -> plugin.places.destinations.keys.toList() + listOf("none")
                "route" -> plugin.places.destinations.keys.toList() + listOf("none", "load")
                "import" -> listOf("traincarts")
                "tag" -> listOf("add", "remove", "clear", "list")
                "model" -> listOf("default", "blocks", "item", "yaw", "offset", "length")
                "blocks" -> listOf("capture", "convert", "restore")
                "models" -> listOf("delete")
                "track" -> listOf("add", "select", "remove", "link", "unlink", "move", "dir", "roll", "name", "switch", "list", "show")
                "destroyall" -> plugin.server.worlds.map { it.name }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "route" -> if (args[1].equals("load", true)) plugin.places.routes.keys.toList() else emptyList()
                "model" -> if (args[1].equals("blocks", true)) plugin.models.names() else emptyList()
                "models", "saved" -> if (args[1].equals("delete", true)) (if (args[0] == "models") plugin.models.names() else plugin.saved.names()) else emptyList()
                "blocks" -> if (args[1].equals("restore", true)) listOf("force") else emptyList()
                "track" -> when (args[1].lowercase()) {
                    "add" -> listOf("here", "rail", "nolink")
                    "dir" -> listOf("auto", "look")
                    else -> emptyList()
                }
                "cart" -> if (args[1].lowercase() in listOf("property", "prop")) PropertyKeys.keys.map { it.name } else emptyList()
                else -> emptyList()
            }
            else -> emptyList()
        }
        return options.filter { it.startsWith(last, ignoreCase = true) }
    }

    companion object {
        /** プレイヤー以外 (コンソール、コマンドブロック) の選択を覚える ID */
        val CONSOLE: UUID = UUID(0L, 0L)
    }
}
