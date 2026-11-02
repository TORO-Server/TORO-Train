package net.torosaba.train.listener

import net.torosaba.train.TOROTrain
import net.torosaba.train.track.RailSegment
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockSupport
import org.bukkit.block.data.Rail
import org.bukkit.block.data.type.RedstoneRail
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * 天井に付けた逆さまのレール。
 *
 * バニラでは下に支えのないレールはブロックの更新で壊れるので、逆さまのレールの更新を止めて残す。
 * 更新を止めるとパワードレールの動力も伝わらなくなるので、ここで計算し直す。
 * 天井の下の面を右クリックすると、レールをその下に逆さまに置ける (バニラでは置けないので)。
 */
class TrackListener(private val plugin: TOROTrain) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPhysics(event: BlockPhysicsEvent) {
        val block = event.block
        if (!Tag.RAILS.isTagged(block.type) || !RailSegment.isUpsideDown(block)) return
        event.isCancelled = true
        if (block.type == Material.POWERED_RAIL || block.type == Material.ACTIVATOR_RAIL) updatePower(block)
    }

    /**
     * まっすぐ並んだ同じ種類の逆さまのレールの動力を決め直す。
     * バニラと同じく、レッドストーンの信号を受けているレールから 8 本先まで動力が伝わる
     */
    private fun updatePower(start: Block) {
        val data = start.blockData as? RedstoneRail ?: return
        val axis = when (data.shape) {
            Rail.Shape.NORTH_SOUTH, Rail.Shape.ASCENDING_NORTH, Rail.Shape.ASCENDING_SOUTH -> BlockFace.SOUTH
            Rail.Shape.EAST_WEST, Rail.Shape.ASCENDING_EAST, Rail.Shape.ASCENDING_WEST -> BlockFace.EAST
            else -> return
        }
        fun sameRun(b: Block) = b.type == start.type && RailSegment.isUpsideDown(b)
        val run = ArrayList<Block>()
        var b = start
        repeat(RANGE * 2) {
            val prev = b.getRelative(axis.oppositeFace)
            if (!sameRun(prev)) return@repeat
            b = prev
        }
        while (run.size < RANGE * 4 + 1) {
            run += b
            b = b.getRelative(axis)
            if (!sameRun(b)) break
        }
        val sources = run.indices.filter { run[it].isBlockIndirectlyPowered }
        for ((i, rail) in run.withIndex()) {
            val powered = sources.any { kotlin.math.abs(it - i) <= RANGE }
            val d = rail.blockData as? RedstoneRail ?: continue
            if (d.isPowered == powered) continue
            d.isPowered = powered
            rail.setBlockData(d, false)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.blockFace != BlockFace.DOWN || event.hand != EquipmentSlot.HAND) return
        if (!plugin.settings.upsideDownRails) return
        val item = event.item ?: return
        if (!Tag.RAILS.isTagged(item.type)) return
        val ceiling = event.clickedBlock ?: return
        val target = ceiling.getRelative(BlockFace.DOWN)
        if (!target.type.isAir) return
        if (!ceiling.blockData.isFaceSturdy(BlockFace.DOWN, BlockSupport.CENTER)) return
        // 下にレールを支えるブロックがあれば、バニラのとおりに置かれる
        if (target.getRelative(BlockFace.DOWN).blockData.isFaceSturdy(BlockFace.UP, BlockSupport.RIGID)) return
        val player = event.player
        event.isCancelled = true

        val check = BlockPlaceEvent(target, target.state, ceiling, item, player, true, EquipmentSlot.HAND)
        plugin.server.pluginManager.callEvent(check)
        if (check.isCancelled || !check.canBuild()) return

        val data = item.type.createBlockData() as Rail
        val facing = player.facing
        data.shape = if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) Rail.Shape.NORTH_SOUTH else Rail.Shape.EAST_WEST
        // 更新ありで置くと、バニラの処理で隣のレールとつながる形になる (支えがなくて壊れる更新は onPhysics で止める)
        target.setBlockData(data, true)
        plugin.router.clear()
        if (player.gameMode != GameMode.CREATIVE) item.amount -= 1
        player.swingMainHand()
        target.world.playSound(target.location.add(0.5, 0.5, 0.5), data.soundGroup.placeSound, 1f, 0.8f)
    }

    private companion object {
        const val RANGE = 8
    }
}
