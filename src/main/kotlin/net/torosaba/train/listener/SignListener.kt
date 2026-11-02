package net.torosaba.train.listener

import net.torosaba.train.TOROTrain
import net.torosaba.train.signs.ActionSign
import net.torosaba.train.signs.DestinationSign
import net.torosaba.train.signs.SignTypes
import net.torosaba.train.signs.Station
import net.torosaba.train.signs.Teleport
import net.torosaba.train.track.TrackBlocks
import net.torosaba.train.util.BlockKey
import net.torosaba.train.util.Text
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.persistence.PersistentDataType

/** 看板を置く・消すときの確認と、レッドストーンの信号で働く看板 */
class SignListener(private val plugin: TOROTrain) : Listener {
    // 看板ごとの、前に見たレッドストーンの信号
    private val lastPower = HashMap<BlockKey, Boolean>()

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        if (event.side != Side.FRONT) return
        val lines = event.lines().map { Text.plain(it) }
        if (lines.isEmpty() || !ActionSign.isHeader(lines[0])) return
        val player = event.player
        val sign = ActionSign.parse(event.block, lines)
        val type = sign?.let { SignTypes.find(it.type) }
        if (sign == null || type == null) {
            player.sendMessage(Text.color("&6[TORO-Train] &c看板の種類が分かりません。/tt signs で一覧を見られます"))
            event.isCancelled = true
            return
        }
        if (!player.hasPermission(type.permission) && !player.hasPermission("torotrain.sign.*")) {
            player.sendMessage(Text.color("&6[TORO-Train] &c${type.name} の看板を置く権限がありません"))
            event.isCancelled = true
            return
        }
        type.validate(sign)?.let {
            player.sendMessage(Text.color("&6[TORO-Train] &c$it"))
            player.sendMessage(Text.color("&7 ${type.usage}"))
            event.isCancelled = true
            return
        }
        plugin.signIndex.invalidateAround(BlockKey.of(event.block))
        register(event.block, sign)
        player.sendMessage(Text.color("&6[TORO-Train] &a${type.name} の看板を置きました &7(${type.usage})"))
    }

    // 行き先と出入口の名前を覚える
    private fun register(block: Block, sign: ActionSign) {
        val rail = plugin.signIndex.railFor(block) ?: return
        val key = BlockKey.of(rail)
        when (SignTypes.find(sign.type)) {
            Station -> plugin.places.addDestination(sign.line3, key)
            DestinationSign -> plugin.places.addDestination(sign.line3, key)
            Teleport -> plugin.places.setPortal(sign.line4, key)
            else -> {}
        }
        plugin.router.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (Tag.ALL_SIGNS.isTagged(block.type)) {
            val sign = ActionSign.parse(block) ?: return
            plugin.signIndex.invalidateAround(BlockKey.of(block))
            // 同じ線路のほかの看板の名前は残す
            plugin.signIndex.railFor(block)?.let { rail ->
                when (SignTypes.find(sign.type)) {
                    Station, DestinationSign -> plugin.places.removeDestination(sign.line3, BlockKey.of(rail))
                    Teleport -> plugin.places.removePortal(sign.line4, BlockKey.of(rail))
                    else -> {}
                }
            }
            lastPower.remove(BlockKey.of(block))
            plugin.router.clear()
        } else if (TrackBlocks.isTrack(block)) {
            plugin.router.clear()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (TrackBlocks.isTrack(event.block)) plugin.router.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPhysics(event: BlockPhysicsEvent) {
        val block = event.block
        if (!Tag.ALL_SIGNS.isTagged(block.type)) return
        val sign = ActionSign.parse(block) ?: return
        val type = SignTypes.find(sign.type) ?: return
        if (!type.reactsToPower) return
        val key = BlockKey.of(block)
        val powered = sign.isPowered()
        // 前の信号は看板にも書いておく。再起動のあとに信号が入ったままの看板を見て、入ったと間違えないように
        val previous = lastPower.put(key, powered) ?: storedPower(block)
        plugin.debug { "看板 ${sign.type} ($key) の信号: $previous → $powered" }
        if (previous == powered) return
        // 信号の変化は、ブロックの更新が落ち着いてから処理する
        plugin.server.scheduler.runTask(plugin, Runnable {
            storePower(block, powered)
            try {
                type.onPower(plugin, sign, powered)
            } catch (e: Exception) {
                plugin.logger.warning("看板 ${sign.type} ($key) の処理で問題が起きました: ${e.message}")
            }
        })
    }

    private fun storedPower(block: Block): Boolean =
        (block.state as? Sign)?.persistentDataContainer?.get(POWER_KEY, PersistentDataType.BOOLEAN) ?: false

    private fun storePower(block: Block, powered: Boolean) {
        val state = block.state as? Sign ?: return
        state.persistentDataContainer.set(POWER_KEY, PersistentDataType.BOOLEAN, powered)
        state.update(false, false)
    }

    private companion object {
        val POWER_KEY: NamespacedKey = NamespacedKey.fromString("toro-train:powered")!!
    }
}
