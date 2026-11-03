package net.torosaba.train.render

import io.papermc.paper.entity.TeleportFlag
import net.torosaba.train.TOROTrain
import net.torosaba.train.model.BlockPart
import net.torosaba.train.model.ItemPart
import net.torosaba.train.model.Part
import net.torosaba.train.model.PartTransform
import net.torosaba.train.model.Seat
import net.torosaba.train.model.TextPart
import net.torosaba.train.train.Cart
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 車両 1 両の見た目。Display エンティティで描き、プレイヤーが乗る座席と、クリックを受ける当たり判定も持つ。
 *
 * 部品はすべて、何も描かない「根」のディスプレイの乗客にする。乗客は根と同じ位置に描かれるので、
 * 移動のときは根を 1 回テレポートするだけでよい (部品の位置は各部品の変換で表す)。
 * 車体が回ったときだけ、各部品の変換を送り直す。
 * エンティティはワールドに保存しない (persistent = false)。列車の状態はプラグインが保存し、必要なときに作り直す。
 */
class CartVisual(private val plugin: TOROTrain, val cart: Cart) {
    private var root: BlockDisplay? = null
    private val parts = ArrayList<Pair<Display, PartTransform>>()
    private val seatEntities = ArrayList<Pair<Seat, ItemDisplay>>()
    private val hitboxes = ArrayList<Pair<Interaction, Float>>()
    private val lastRotation = Quaternionf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
    private val lastPosition = Vector3d(Double.NaN, 0.0, 0.0)
    private var seatList: List<Seat> = emptyList()

    // 作り直すときに、座っていた人を同じ座席に戻すための記録
    private var pendingRiders: Map<Int, Player> = emptyMap()

    val isSpawned: Boolean get() = root?.isValid == true

    private fun location(world: World, p: Vector3d) = Location(world, p.x, p.y, p.z, 0f, 0f)

    private fun <T : Entity> tag(entity: T): T {
        entity.isPersistent = false
        entity.persistentDataContainer.set(PART_KEY, PersistentDataType.STRING, cart.train.id.toString())
        plugin.trains.entityCarts[entity.uniqueId] = cart
        return entity
    }

    /** チャンクが読み込まれていれば、エンティティがなければ作る。描ける状態なら true */
    fun ensure(): Boolean {
        if (!cart.poseValid) return false
        val world = cart.train.world
        val p = cart.position
        if (!world.isChunkLoaded(floor(p.x).toInt() shr 4, floor(p.z).toInt() shr 4)) {
            if (root != null) despawn()
            return false
        }
        if (isSpawned) return true
        if (root != null) despawn()
        spawn(world)
        return true
    }

    private fun spawn(world: World) {
        val s = plugin.settings
        val lib = plugin.models
        val loc = location(world, cart.position)
        val rot = cart.rotation
        val newRoot = world.spawn(loc, BlockDisplay::class.java) { e ->
            tag(e)
            e.teleportDuration = s.interpolationTicks
            e.viewRange = s.viewRange
        }
        root = newRoot

        var partList: List<Part> = cart.model.parts(lib, s.flipSignItems)
        if (partList.size > s.maxModelBlocks) partList = partList.subList(0, s.maxModelBlocks)
        for (part in partList) {
            val entity: Display = when (part) {
                is BlockPart -> world.spawn(loc, BlockDisplay::class.java) { e -> e.block = part.data }
                is ItemPart -> world.spawn(loc, ItemDisplay::class.java) { e ->
                    e.setItemStack(part.item)
                    e.itemDisplayTransform = part.display
                }
                is TextPart -> world.spawn(loc, TextDisplay::class.java) { e ->
                    e.text(part.text)
                    e.backgroundColor = Color.fromARGB(0, 0, 0, 0)
                    e.lineWidth = 400
                    e.alignment = TextDisplay.TextAlignment.CENTER
                    if (part.glowing) e.brightness = Display.Brightness(15, 15)
                }
            }
            tag(entity)
            entity.viewRange = s.viewRange
            entity.transformation = part.transform.transformed(rot)
            entity.interpolationDuration = s.interpolationTicks
            newRoot.addPassenger(entity)
            parts += entity to part.transform
        }

        seatList = cart.model.seats(lib)
        for ((index, seat) in seatList.withIndex()) {
            val seatLoc = location(world, seatPosition(seat))
            val e = world.spawn(seatLoc, ItemDisplay::class.java) { d ->
                tag(d)
                d.teleportDuration = s.interpolationTicks
            }
            seatEntities += seat to e
            pendingRiders[index]?.let { player ->
                if (player.isOnline && player.world == world) {
                    player.teleport(seatLoc)
                    e.addPassenger(player)
                }
            }
        }
        pendingRiders = emptyMap()

        val length = cart.model.length(lib).toFloat()
        val width = cart.model.width(lib).toFloat().coerceIn(0.5f, 4f)
        val height = cart.model.height(lib).toFloat().coerceIn(0.5f, 8f)
        val count = max(1, ceil(length / width).toInt()).coerceIn(1, 64)
        for (i in 0 until count) {
            val z = -length / 2 + width / 2 + if (count > 1) (length - width) * i / (count - 1) else (length - width) / 2
            val hitLoc = location(world, hitboxPosition(z))
            val e = world.spawn(hitLoc, Interaction::class.java) { d ->
                tag(d)
                d.interactionWidth = width
                d.interactionHeight = height
                d.isResponsive = true
            }
            hitboxes += e to z
        }
        lastRotation.set(rot)
        lastPosition.set(cart.position)
    }

    private fun seatPosition(seat: Seat): Vector3d {
        val v = cart.rotation.transform(Vector3f(seat.position))
        return Vector3d(cart.position).add(v.x.toDouble(), v.y + plugin.settings.seatEntityOffset, v.z.toDouble())
    }

    private fun hitboxPosition(z: Float): Vector3d {
        val v = cart.rotation.transform(Vector3f(0f, 0f, z))
        return Vector3d(cart.position).add(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
    }

    /**
     * 車両の今の位置と向きに合わせる。
     * 乗客ごとテレポートする指定は、Paper の新しい版では既定の動きになって非推奨だが、古い動きの版でも乗客が外れないよう付けておく
     */
    @Suppress("DEPRECATION")
    fun update() {
        if (!ensure()) return
        val world = cart.train.world
        val root = root ?: return
        val moved = lastPosition.distanceSquared(cart.position) > 1e-8
        if (moved) {
            root.teleport(location(world, cart.position), TeleportFlag.EntityState.RETAIN_PASSENGERS)
            lastPosition.set(cart.position)
        }
        val rotated = !lastRotation.equals(cart.rotation, 1e-5f)
        if (rotated) {
            val ticks = plugin.settings.interpolationTicks
            for ((entity, transform) in parts) {
                entity.interpolationDelay = 0
                entity.interpolationDuration = ticks
                entity.transformation = transform.transformed(cart.rotation)
            }
            lastRotation.set(cart.rotation)
        }
        if (moved || rotated) {
            for ((seat, entity) in seatEntities) {
                entity.teleport(location(world, seatPosition(seat)), TeleportFlag.EntityState.RETAIN_PASSENGERS)
            }
            for ((entity, z) in hitboxes) entity.teleport(location(world, hitboxPosition(z)))
        }
    }

    fun despawn() {
        val all = listOfNotNull(root) + parts.map { it.first } + seatEntities.map { it.second } + hitboxes.map { it.first }
        plugin.trains.internalDismount = true
        try {
            for (e in all) {
                plugin.trains.entityCarts.remove(e.uniqueId)
                e.eject()
                e.remove()
            }
        } finally {
            plugin.trains.internalDismount = false
        }
        root = null
        parts.clear()
        seatEntities.clear()
        hitboxes.clear()
        lastRotation.set(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        lastPosition.set(Double.NaN, 0.0, 0.0)
    }

    /** 見た目を作り直す (モデルを変えたとき)。座っていた人は同じ座席に戻す */
    fun rebuild() {
        val riders = HashMap<Int, Player>()
        for ((index, pair) in seatEntities.withIndex()) {
            (pair.second.passengers.firstOrNull() as? Player)?.let { riders[index] = it }
        }
        despawn()
        pendingRiders = riders
        ensure()
    }

    /** 座っている人を降ろして、作り直すときに戻すよう覚える (ワールドを越えて移るとき) */
    fun takeRiders(): Map<Int, Player> {
        val riders = HashMap<Int, Player>()
        for ((index, pair) in seatEntities.withIndex()) {
            (pair.second.passengers.firstOrNull() as? Player)?.let { riders[index] = it }
        }
        return riders
    }

    fun restoreRiders(riders: Map<Int, Player>) {
        pendingRiders = riders
    }

    fun passengers(): List<Player> = seatEntities.mapNotNull { it.second.passengers.firstOrNull() as? Player }

    fun seatOf(player: Player): Seat? = seatEntities.firstOrNull { it.second.passengers.contains(player) }?.first

    fun isSeatEntity(entity: Entity): Boolean = seatEntities.any { it.second == entity }

    /** 空いている座席のうち、near に最も近いものに座らせる */
    fun seat(player: Player, near: Vector3d?): Boolean {
        val free = seatEntities.filter { it.second.passengers.isEmpty() }
        if (free.isEmpty()) return false
        val target = if (near == null) free.first() else free.minBy { (seat, _) -> seatPosition(seat).distanceSquared(near) }
        player.leaveVehicle()
        return target.second.addPassenger(player)
    }

    fun ejectAll() {
        for ((_, entity) in seatEntities) {
            plugin.trains.internalDismount = true
            try {
                entity.eject()
            } finally {
                plugin.trains.internalDismount = false
            }
        }
    }

    /** 降りた人を置く場所。車体の横の、空いている場所 */
    fun exitLocation(seat: Seat?): Location {
        val world = cart.train.world
        val width = cart.model.width(plugin.models).toFloat()
        val z = seat?.position?.z ?: 0f
        for (side in floatArrayOf(1f, -1f)) {
            val v = cart.rotation.transform(Vector3f(side * (width / 2 + 0.6f), 0.1f, z))
            val p = Vector3d(cart.position).add(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
            val loc = location(world, p)
            val block = loc.block
            if (block.isPassable && block.getRelative(0, 1, 0).isPassable) return loc
        }
        val top = Vector3d(cart.position).add(0.0, cart.model.height(plugin.models) + 0.1, 0.0)
        return location(world, top)
    }

    companion object {
        val PART_KEY: NamespacedKey = NamespacedKey.fromString("toro-train:part")!!
    }
}
