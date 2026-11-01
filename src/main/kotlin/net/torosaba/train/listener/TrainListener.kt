package net.torosaba.train.listener

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.torosaba.train.TOROTrain
import net.torosaba.train.model.DefaultCartModel
import net.torosaba.train.track.TrackBlocks
import net.torosaba.train.util.Text
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/** 列車に乗る・降りる・壊す・運転する操作と、範囲を選ぶ道具 */
class TrainListener(private val plugin: TOROTrain) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        val entity = event.rightClicked as? Interaction ?: return
        val cart = plugin.trains.entityCarts[entity.uniqueId] ?: return
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val train = cart.train
        if (player.vehicle?.let { plugin.trains.entityCarts[it.uniqueId] } != null) return
        if (!player.hasPermission("torotrain.ride")) return
        if (!train.props.allowEnter || !cart.props.allowEnter) {
            player.sendActionBar(Text.color("&cこの車両には乗れません"))
            return
        }
        val near = player.eyeLocation.toVector().toVector3d()
        if (cart.visual?.seat(player, near) != true) player.sendActionBar(Text.color("&c空いている座席がありません"))
    }

    @EventHandler(ignoreCancelled = true)
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        val cart = plugin.trains.entityCarts[event.attacked.uniqueId] ?: return
        event.isCancelled = true
        val player = event.player
        val train = cart.train
        // 普通のトロッコと同じに、叩くと叩いた車両を一両だけ壊す (最後の一両なら列車ごと。列車ぜんぶは /tt destroy)
        // しゃがんで叩くと、壊さずに列車を選ぶ
        if (player.isSneaking) {
            plugin.trains.selected[player.uniqueId] = train.id
            player.sendActionBar(Text.color("&7列車 &f${train.displayName()} &7を選びました (しゃがまずに叩くと、この車両を壊します)"))
            return
        }
        val admin = player.hasPermission("torotrain.admin")
        if (!player.hasPermission("torotrain.destroy") || (!admin && (train.props.invincible || !train.isOwner(player)))) {
            player.sendActionBar(Text.color("&cこの列車は消せません"))
            return
        }
        val last = train.carts.size <= 1
        val drop = cart.model is DefaultCartModel && player.gameMode != GameMode.CREATIVE
        val at = cart.visual?.exitLocation(null)
        plugin.trains.removeCart(cart)
        if (drop && at != null) at.world.dropItemNaturally(at, ItemStack(Material.MINECART))
        player.sendActionBar(Text.color(if (last) "&a列車 ${train.displayName()} を消しました" else "&a車両を一両消しました"))
    }

    @EventHandler(ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        if (plugin.trains.internalDismount) return
        val player = event.entity as? Player ?: return
        val cart = plugin.trains.entityCarts[event.dismounted.uniqueId] ?: return
        val visual = cart.visual ?: return
        if (!visual.isSeatEntity(event.dismounted)) return
        val canExit = cart.train.props.allowExit && cart.props.allowExit
        if (!canExit && !player.hasPermission("torotrain.admin") && player.isOnline && !player.isDead) {
            event.isCancelled = true
            player.sendActionBar(Text.color("&cここでは降りられません"))
            return
        }
        // 車体の中に埋まらないよう、横に降ろす
        val exit = visual.exitLocation(visual.seatOf(player)).apply {
            yaw = player.location.yaw
            pitch = player.location.pitch
        }
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline && player.vehicle == null) player.teleport(exit) })
    }

    @EventHandler
    fun onInput(event: PlayerInputEvent) {
        plugin.trains.onInput(event.player, event.input)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.trains.forgetInput(event.player)
        plugin.trains.selected.remove(event.player.uniqueId)
        plugin.blockTrains.selections.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val block = event.clickedBlock ?: return
        val item = event.item

        // 範囲を選ぶ道具
        if (item != null && plugin.isWand(item) && player.hasPermission("torotrain.admin")) {
            val index = when (event.action) {
                Action.LEFT_CLICK_BLOCK -> 1
                Action.RIGHT_CLICK_BLOCK -> 2
                else -> return
            }
            event.isCancelled = true
            plugin.blockTrains.setPos(player.uniqueId, index, block.location)
            val sel = plugin.blockTrains.selection(player.uniqueId)
            val volume = if (sel.complete()) " (${sel.volume()} ブロック)" else ""
            player.sendMessage(Text.color("&6[TORO-Train] &a$index 点目: ${block.x}, ${block.y}, ${block.z}$volume"))
            return
        }

        // トロッコを線路に置くと、TORO-Train の列車にする
        if (event.action == Action.RIGHT_CLICK_BLOCK && item?.type == Material.MINECART &&
            plugin.config.getBoolean("train.replace-minecart", true) && player.hasPermission("torotrain.place")
        ) {
            if (!TrackBlocks.isTrack(block)) return
            event.isCancelled = true
            val model = DefaultCartModel()
            val look = player.location.direction.toVector3d()
            val path = plugin.trains.pathAt(block, null, model.length + plugin.settings.cartGap, look) ?: return
            val train = plugin.trains.create(path, listOf(model))
            train.props.owners += player.uniqueId
            plugin.trains.selected[player.uniqueId] = train.id
            if (player.gameMode != org.bukkit.GameMode.CREATIVE) item.amount -= 1
            player.sendActionBar(Text.color("&a列車 ${train.name} を置きました (右クリックで乗車、W/S で運転)"))
        }
    }
}
