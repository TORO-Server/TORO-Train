package net.torosaba.train.train

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.torosaba.train.TOROTrain
import net.torosaba.train.model.CartModel
import net.torosaba.train.render.CartVisual
import net.torosaba.train.signs.ActionSign
import net.torosaba.train.signs.Blocker
import net.torosaba.train.signs.Mutex
import net.torosaba.train.signs.SignContext
import net.torosaba.train.signs.SignTypes
import net.torosaba.train.signs.Switcher
import net.torosaba.train.track.Chooser
import net.torosaba.train.track.DirectedSegment
import net.torosaba.train.track.FreeSegment
import net.torosaba.train.track.Next
import net.torosaba.train.track.RailSegment
import net.torosaba.train.track.TrackBlocks
import net.torosaba.train.track.TrackPath
import net.torosaba.train.track.TrackSegment
import net.torosaba.train.util.BlockKey
import net.torosaba.train.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.Tag
import org.bukkit.block.data.AnaloguePowerable
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.RedstoneRail
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.joml.Vector3d
import java.io.File
import java.util.UUID
import java.util.logging.Level
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * すべての列車を持ち、毎 tick 動かす。看板の判定、列車どうしの間隔と連結、保存と読み込みもここで行う。
 */
class TrainManager(private val plugin: TOROTrain) {
    val trains = LinkedHashMap<UUID, Train>()

    /** Display エンティティ (部品・座席・当たり判定) から車両を引く表 */
    val entityCarts = HashMap<UUID, Cart>()

    /** プラグインが乗客を降ろしている間は true (降りるのを止める処理を飛ばす) */
    var internalDismount = false

    /** プレイヤーが選んでいる列車 */
    val selected = HashMap<UUID, UUID>()

    // ブロック → そこにいる列車
    private val occupancy = HashMap<BlockKey, Train>()
    // 分岐器を、先に向きを決めた列車が通り過ぎるまで押さえておく
    private val switchLocks = HashMap<BlockKey, UUID>()
    private val chunkTickets = HashMap<UUID, Set<Pair<UUID, Long>>>()
    // 保存されていたが、ワールドがないなどで作れなかった列車 (消さずに書き戻す)
    private val pendingRaw = HashMap<String, Map<String, Any?>>()
    private val lastInput = HashMap<UUID, org.bukkit.Input>()
    private var tick = 0L

    private val settings get() = plugin.settings
    private val graph get() = plugin.graph

    // ---------- 毎 tick ----------

    fun tick() {
        tick++
        occupancy.clear()
        for (train in trains.values) for (key in train.occupied) occupancy[key] = train

        for (train in trains.values.toList()) {
            if (train.removed) continue
            try {
                step(train)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "列車 ${train.name} の処理で問題が起きたので止めました", e)
                train.speed = 0.0
                train.action = null
            }
        }
        for (train in trains.values) {
            for (cart in train.carts) {
                if (cart.visual == null) cart.visual = CartVisual(plugin, cart)
                cart.visual!!.update()
            }
            if (tick % 4 == 0L) showDriverHud(train)
        }
        if (tick % 20 == 0L) updateChunkTickets()
        if (settings.autosaveTicks > 0 && tick % settings.autosaveTicks == 0L) saveAll()
    }

    private fun headLoaded(train: Train): Boolean {
        val p = train.path.locate(0.0).point()
        return train.world.isChunkLoaded(kotlin.math.floor(p.x).toInt() shr 4, kotlin.math.floor(p.z).toInt() shr 4)
    }

    private fun step(train: Train) {
        val p = train.props
        // 読み込まれていない場所の列車は止めておく (チャンクを読み込んだままにする列車は除く)
        if (!p.keepChunksLoaded && !headLoaded(train)) {
            train.blockedBy = "読み込まれていない場所"
            return
        }
        train.blockedBy = null
        if (train.derailed) {
            freeStep(train)
            return
        }

        // 1. 運転
        val driver = driverOf(train)
        if (driver == null) train.notch = 0
        val manual = driver != null && p.manualControl && train.action == null
        if (manual) {
            train.speed += train.tickNotchSpeed()
            train.resumeSpeed = 0.0
        }

        // 2. 摩擦・重力・パワードレール
        val action = train.action
        if (action == null || !action.holdsSpeed) {
            train.speed *= (1 - p.friction)
            if (p.gravity) train.speed -= settings.slopeGravity * slope(train)
            poweredRails(train)
            if (!manual && train.resumeSpeed > train.speed) {
                train.speed = min(train.resumeSpeed, train.speed + p.acceleration)
                if (train.speed >= train.resumeSpeed - 1e-6) train.resumeSpeed = 0.0
            }
        }

        // 3. 動作 (駅に止まる、発車する など)。すぐ終わる動作は同じ tick の中で続けて行う
        var current = train.action
        var guard = 0
        while (current != null && guard++ < 8) {
            if (!current.tick(train)) break
            current = current.then
            train.action = current
        }

        // 4. 最高速度
        val limit = min(p.speedLimit, settings.maxSpeed)
        if (train.speed > limit) train.speed = max(limit, train.speed - p.deceleration * 2)
        if (train.speed < 0) {
            if (train.action == null && p.gravity) {
                // 坂で止まって転がり落ちる
                train.reverse()
                train.speed = -train.speed
            } else {
                train.speed = 0.0
            }
        }
        if (train.speed < 1e-4 && train.action == null && train.resumeSpeed == 0.0) train.speed = 0.0

        // 5. 前の列車や信号
        prepareSwitches(train)
        val cap = obstacleCap(train)
        if (train.removed) return
        if (train.speed > cap) {
            if (!manual && train.action == null) train.resumeSpeed = max(train.resumeSpeed, train.speed)
            train.speed = cap
        }

        // 6. 進める
        val chooser = chooserFor(train)
        val (moved, stop) = train.path.advance(train.speed) { seg -> graph.next(seg, chooser, p.keepChunksLoaded) }
        when (stop) {
            Next.End -> if (p.derail && train.speed > 1e-3) {
                // 線路から外れて、そのままの勢いで進む
                train.freeVelocity = train.headTangent().mul(train.speed)
                train.freeTicks = 0
                train.derailedFrom = train.path.head.segment
                train.action = null
                train.resumeSpeed = 0.0
                train.notch = 0
            } else {
                train.speed = 0.0
                train.resumeSpeed = 0.0
                train.blockedBy = "線路の終わり"
            }
            Next.Unloaded -> train.blockedBy = "読み込まれていない場所"
            else -> {}
        }
        train.lastMoved = moved
        train.action?.moved(train, moved)
        train.path.trim(train.length() + 4)
        train.computePoses()

        // 7. 占めているブロックと看板
        updateOccupancy(train, moved)
    }

    // ---------- 線路から外れた列車 ----------

    /**
     * 線路から外れた列車を 1 tick 動かす。先頭は重力で落ち、ブロックに当たると止まり、地面では滑って遅くなる。
     * 線路 (レール・感圧板・ハシゴ・自由な線路) の近くを通れば、その線路に乗る。後ろの車両は先頭が動いた道をたどる。
     */
    private fun freeStep(train: Train) {
        val v = train.freeVelocity ?: return
        val world = train.world
        train.freeTicks++
        train.action = null
        train.resumeSpeed = 0.0
        train.notch = 0
        val start = train.path.locate(0.0).point()

        val onGround = solidTop(world, start.x, start.y - 0.05, start.z) != null
        if (onGround) {
            v.x *= settings.freeGroundFriction
            v.z *= settings.freeGroundFriction
            if (v.y < 0) v.y = 0.0
        } else {
            v.y -= settings.freeGravity
        }
        v.mul(settings.freeAirDrag)
        if (onGround && v.lengthSquared() < 1e-5) v.zero()

        // 少しずつ動かして、ブロックに当たる軸の速さを消す。線路に近づいたら乗る
        val cur = Vector3d(start)
        val steps = max(1, kotlin.math.ceil(v.length() / 0.25).toInt())
        var landed: net.torosaba.train.track.TrackPos? = null
        val recent = train.derailedFrom.takeIf { train.freeTicks < 10 }
        for (i in 0 until steps) {
            val stepY = v.y / steps
            if (stepY != 0.0) {
                val top = solidTop(world, cur.x, cur.y + stepY, cur.z)
                if (top != null) {
                    if (stepY < 0) cur.y = top + 1e-3
                    v.y = 0.0
                } else {
                    cur.y += stepY
                }
            }
            val stepX = v.x / steps
            if (stepX != 0.0) {
                if (solidTop(world, cur.x + stepX, cur.y, cur.z) != null) v.x = 0.0 else cur.x += stepX
            }
            val stepZ = v.z / steps
            if (stepZ != 0.0) {
                if (solidTop(world, cur.x, cur.y, cur.z + stepZ) != null) v.z = 0.0 else cur.z += stepZ
            }
            if (!world.isChunkLoaded(kotlin.math.floor(cur.x).toInt() shr 4, kotlin.math.floor(cur.z).toInt() shr 4)) {
                v.zero()
                train.blockedBy = "読み込まれていない場所"
                break
            }
            if (v.lengthSquared() > 0) {
                landed = graph.nearest(world, cur, 0.45) { it == recent || it is FreeSegment }
                if (landed != null) break
            }
        }
        // 止まったときに線路のそばにいれば (線路の終わりの壁に当たったときなど)、その線路に戻る
        if (landed == null && v.lengthSquared() == 0.0) landed = graph.nearest(world, cur, 0.45) { it is FreeSegment }

        val path = train.path
        val moved: Double
        if (landed != null) {
            // 線路に乗る。速度のうち線路に沿った分だけが残る
            val tangent = landed.tangent()
            val along = v.dot(tangent)
            val forward = (if (v.lengthSquared() > 0) Vector3d(v) else train.headTangent()).dot(tangent) >= 0
            val seg = if (forward) landed.segment else landed.segment.flip()
            val offset = if (forward) landed.offset else landed.segment.length - landed.offset
            val onTrack = landed.point()
            val bridge = FreeSegment(world, start, onTrack)
            if (bridge.length > 1e-6) path.segments += DirectedSegment(bridge, false)
            path.segments += seg
            path.headOffset = offset
            moved = bridge.length
            train.speed = kotlin.math.abs(along)
            train.freeVelocity = null
            train.derailedFrom = null
            plugin.debug { "${train.name} が線路 ${seg} に乗りました" }
        } else {
            moved = start.distance(cur)
            if (moved > 1e-6) {
                path.segments += DirectedSegment(FreeSegment(world, start, Vector3d(cur)), false)
                path.headOffset = moved
            }
            train.speed = v.length()
            if (train.blockedBy == null) train.blockedBy = if (train.speed < 1e-4) "線路の外で止まっている" else "線路から外れている"
        }
        train.lastMoved = moved
        path.trim(train.length() + 4)
        train.computePoses()
        updateOccupancy(train, moved)
    }

    /** 点がブロックの当たり判定の中にあれば、その当たり判定の上面の高さ */
    private fun solidTop(world: World, x: Double, y: Double, z: Double): Double? {
        val bx = kotlin.math.floor(x).toInt()
        val by = kotlin.math.floor(y).toInt()
        val bz = kotlin.math.floor(z).toInt()
        if (!world.isChunkLoaded(bx shr 4, bz shr 4)) return null
        val block = world.getBlockAt(bx, by, bz)
        if (!block.type.isCollidable) return null
        for (box in block.collisionShape.boundingBoxes) {
            if (box.contains(x - bx, y - by, z - bz)) return by + box.maxY
        }
        return null
    }

    private fun slope(train: Train): Double {
        if (train.carts.isEmpty()) return 0.0
        var sum = 0.0
        val t = Vector3d()
        for (cart in train.carts) sum += train.path.locate(cart.frontDistance + cart.length / 2).tangent(t).y
        return sum / train.carts.size
    }

    private fun poweredRails(train: Train) {
        var boost = false
        var brake = false
        for (key in train.occupied) {
            val block = key.blockIfLoaded() ?: continue
            if (block.type != Material.POWERED_RAIL) continue
            if ((block.blockData as RedstoneRail).isPowered) boost = true else brake = true
        }
        if (boost && train.speed > 0.001 && train.speed < settings.poweredRailMaxSpeed) {
            train.speed = min(settings.poweredRailMaxSpeed, train.speed + settings.poweredRailBoost)
        } else if (brake && !boost) {
            train.speed = max(0.0, train.speed - settings.poweredRailBrake)
            train.resumeSpeed = 0.0
        }
    }

    /** 分岐で進む向き。行き先があれば経路探索で、なければ既定の選び方 */
    private fun chooserFor(train: Train): Chooser? {
        val dest = train.props.destination ?: return null
        return Chooser { options -> plugin.router.choose(options, dest) }
    }

    /** 列車がこれから通る分岐器の向きを、入る前に変えておく */
    private fun prepareSwitches(train: Train) {
        val look = max(2.0, train.speed * 4 + 1.5)
        val chooser = chooserFor(train)
        val next = { seg: DirectedSegment -> graph.next(seg, chooser) }
        repeat(3) {
            var changed = false
            train.path.scanAhead(look, next) { seg, start ->
                if (start <= 0) return@scanAhead true
                val rail = seg.segment as? RailSegment ?: return@scanAhead true
                val key = rail.key
                val lock = switchLocks[key]
                if (lock != null && lock != train.id) return@scanAhead true
                val sign = plugin.signIndex.signsFor(key).mapNotNull { ActionSign.parse(it) }
                    .firstOrNull { (it.type == "switcher" || it.type == "switch") && it.scope == ActionSign.Scope.TRAIN && it.isActive() }
                    ?: return@scanAhead true
                switchLocks[key] = train.id
                if (Switcher.apply(plugin, train, seg, sign)) {
                    changed = true
                    return@scanAhead false
                }
                true
            }
            if (!changed) return
        }
    }

    /** 前の列車や信号の手前で止まれる速さ */
    private fun obstacleCap(train: Train): Double {
        val p = train.props
        val decel = max(p.deceleration, 1e-4)
        val look = train.speed * train.speed / (2 * decel) + p.waitDistance + train.speed * 2 + 1.5
        var cap = Double.MAX_VALUE
        val chooser = chooserFor(train)
        val blocks = ArrayList<BlockKey>()
        fun brake(d: Double) = if (d <= 0) 0.0 else sqrt(2 * decel * d)
        train.path.scanAhead(look, { graph.next(it, chooser) }) { seg, start ->
            blocks.clear()
            seg.triggerBlocks(max(0.0, -start), seg.length, blocks)
            for (key in blocks) {
                val other = occupancy[key]
                if (other != null && other !== train && !other.removed) {
                    if (p.coupling && other.props.coupling) {
                        val gap = contactGap(train, other)
                        if (gap < 0.35) {
                            couple(train, other)
                            return@scanAhead false
                        }
                        cap = min(cap, max(brake(gap - 0.2), 0.03))
                    } else {
                        cap = min(cap, brake(max(0.0, start) - p.waitDistance))
                    }
                    train.blockedBy = "前の列車 (${other.displayName()})"
                    return@scanAhead false
                }
                if (start < 0) continue
                for (signBlock in plugin.signIndex.signsFor(key)) {
                    val sign = ActionSign.parse(signBlock) ?: continue
                    if (sign.scope != ActionSign.Scope.TRAIN) continue
                    val type = SignTypes.find(sign.type) ?: continue
                    val reason = type.blocks(plugin, sign, train) ?: continue
                    cap = min(cap, brake(start - 0.3))
                    train.blockedBy = reason
                    return@scanAhead false
                }
            }
            true
        }
        return cap
    }

    // 先頭と、ほかの列車の近いほうの端との距離
    private fun contactGap(train: Train, other: Train): Double {
        val head = train.path.locate(0.0).point()
        val a = other.path.locate(0.0).point()
        val b = other.path.locate(other.length()).point()
        return sqrt(min(head.distanceSquared(a), head.distanceSquared(b)))
    }

    // ---------- 占めているブロックと看板 ----------

    private fun collectBlocks(path: TrackPath, from: Double, to: Double, out: MutableCollection<BlockKey>) {
        var base = path.headOffset
        for (i in path.segments.indices.reversed()) {
            val seg = path.segments[i]
            val a = max(0.0, base - to)
            val b = min(seg.length, base - from)
            if (a <= b) seg.triggerBlocks(a, b, out)
            if (base - seg.length > to) break
            if (i > 0) base += path.segments[i - 1].length
        }
    }

    /** 作ったばかりの列車の、占めているブロックを看板を働かせずに決める */
    fun initOccupancy(train: Train) {
        train.occupied.clear()
        for (cart in train.carts) {
            cart.occupied.clear()
            collectBlocks(train.path, cart.frontDistance, cart.frontDistance + cart.length, cart.occupied)
            train.occupied += cart.occupied
        }
    }

    private fun updateOccupancy(train: Train, moved: Double) {
        val prevTrain = HashSet(train.occupied)
        val nowTrain = HashSet<BlockKey>()
        val sweptTrain = HashSet<BlockKey>()
        val cartEvents = ArrayList<Triple<Cart, Set<BlockKey>, Set<BlockKey>>>()
        for (cart in train.carts) {
            val now = HashSet<BlockKey>()
            collectBlocks(train.path, cart.frontDistance, cart.frontDistance + cart.length, now)
            // 1 tick の間に通り過ぎたブロックも、入ったことにする
            val swept = HashSet(now)
            if (moved > 0) collectBlocks(train.path, cart.frontDistance, cart.frontDistance + cart.length + moved, swept)
            val entered = swept - cart.occupied
            val left = cart.occupied - now
            cart.occupied.clear()
            cart.occupied += now
            nowTrain += now
            sweptTrain += swept
            if (entered.isNotEmpty() || left.isNotEmpty()) cartEvents += Triple(cart, entered, left)
        }
        val trainEntered = sweptTrain - prevTrain
        val trainLeft = prevTrain - nowTrain
        train.occupied.clear()
        train.occupied += nowTrain

        for (key in trainEntered) {
            detectorRail(key, true)
            fireSigns(train, null, key, enter = true)
            if (train.removed) return
        }
        for ((cart, entered, left) in cartEvents) {
            for (key in entered) fireSigns(train, cart, key, enter = true)
            for (key in left) fireSigns(train, cart, key, enter = false)
            if (train.removed) return
        }
        for (key in trainLeft) {
            detectorRail(key, false)
            if (switchLocks[key] == train.id) switchLocks.remove(key)
            fireSigns(train, null, key, enter = false)
            if (train.removed) return
        }
    }

    /**
     * バニラのディテクターレールと感圧板を、列車がいるあいだ入れる。
     * 感圧板はトロッコが押せるもの (木の感圧板と重量感圧板) だけで、石の感圧板は生き物にしか反応しないので押さない
     */
    private fun detectorRail(key: BlockKey, on: Boolean) {
        val block = key.blockIfLoaded() ?: return
        val type = block.type
        if (type != Material.DETECTOR_RAIL && !Tag.WOODEN_PRESSURE_PLATES.isTagged(type) &&
            type != Material.LIGHT_WEIGHTED_PRESSURE_PLATE && type != Material.HEAVY_WEIGHTED_PRESSURE_PLATE
        ) return
        if (!on && occupancy[key] != null && occupancy[key]?.occupied?.contains(key) == true) return
        when (val data = block.blockData) {
            is Powerable -> {
                if (data.isPowered == on) return
                data.isPowered = on
                block.setBlockData(data, true)
            }
            is AnaloguePowerable -> {
                // 重量感圧板は乗っている数で強さが変わる。列車 1 本を 1 つと数える
                val power = if (on) 1 else 0
                if (data.power == power) return
                data.power = power
                block.setBlockData(data, true)
            }
        }
    }

    private fun fireSigns(train: Train, cart: Cart?, rail: BlockKey, enter: Boolean) {
        val scope = if (cart == null) ActionSign.Scope.TRAIN else ActionSign.Scope.CART
        for (block in plugin.signIndex.signsFor(rail)) {
            val sign = ActionSign.parse(block) ?: continue
            if (sign.scope != scope) continue
            val type = SignTypes.find(sign.type) ?: continue
            if (type != Blocker && type != Mutex && !sign.isActive()) continue
            if (enter && cart == null && train.skipSigns > 0 && type.name != "skip") {
                train.skipSigns--
                continue
            }
            if (type == Switcher) {
                val lock = switchLocks[rail]
                if (lock != null && lock != train.id) continue
            }
            val ctx = SignContext(plugin, sign, train, cart, rail)
            plugin.debug { "${train.name}${cart?.let { " の ${it.index + 1} 両目" } ?: ""} が ${sign.type} ($rail) に${if (enter) "入りました" else "出ました"}" }
            try {
                if (enter) type.onEnter(ctx) else type.onLeave(ctx)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "看板 ${sign.type} (${sign.key}) の処理で問題が起きました", e)
            }
            if (train.removed) return
        }
    }

    /** 列車の前 distance ブロックに、ほかの列車がいないか */
    fun clearAhead(train: Train, distance: Double): Boolean {
        var clear = true
        val blocks = ArrayList<BlockKey>()
        train.path.scanAhead(distance, { graph.next(it, chooserFor(train)) }) { seg, start ->
            blocks.clear()
            seg.triggerBlocks(max(0.0, -start), seg.length, blocks)
            if (blocks.any { occupancy[it]?.let { o -> o !== train } == true }) {
                clear = false
                return@scanAhead false
            }
            true
        }
        return clear
    }

    // ---------- 運転 ----------

    /** 運転席に座っているプレイヤー */
    fun driverOf(train: Train): Player? {
        for (cart in listOfNotNull(train.carts.firstOrNull(), train.carts.lastOrNull())) {
            val visual = cart.visual ?: continue
            for (player in visual.passengers()) {
                if (visual.seatOf(player)?.driver == true && player.hasPermission("torotrain.drive")) return player
            }
        }
        return null
    }

    /** マスコンの操作 (乗っているプレイヤーの W/S/ジャンプ) */
    fun onInput(player: Player, input: org.bukkit.Input) {
        val previous = lastInput.put(player.uniqueId, input)
        val vehicle = player.vehicle ?: return
        val cart = entityCarts[vehicle.uniqueId] ?: return
        val train = cart.train
        if (driverOf(train) != player || !train.props.manualControl || train.derailed) return
        val forward = input.isForward && previous?.isForward != true
        val backward = input.isBackward && previous?.isBackward != true
        val jump = input.isJump && previous?.isJump != true
        if (forward) {
            if (train.notch < 5) train.notch++
            train.action = null
        }
        if (backward) {
            if (train.notch <= -8 && train.speed < 1e-3) {
                train.reverse()
                train.notch = 0
                player.sendActionBar(Text.color("&e進行方向を反対にしました"))
            } else if (train.notch > -8) {
                train.notch--
            }
            train.action = null
        }
        if (jump) {
            val sound = Sound.sound(Key.key("minecraft:block.note_block.didgeridoo"), Sound.Source.MASTER, 3f, 0.7f)
            val c = cart.position
            train.world.playSound(sound, c.x, c.y, c.z)
        }
    }

    fun forgetInput(player: Player) = lastInput.remove(player.uniqueId)

    private fun notchText(notch: Int) = when {
        notch > 0 -> "&aP$notch"
        notch == 0 -> "&fN"
        notch <= -8 -> "&4EB"
        else -> "&cB${-notch}"
    }

    private fun showDriverHud(train: Train) {
        val driver = driverOf(train) ?: return
        if (!train.props.manualControl) return
        val dest = train.props.destination?.let { " &7行先 &f$it" } ?: ""
        val state = train.action?.describe()?.let { " &7| &e$it" } ?: train.blockedBy?.let { " &7| &c$it" } ?: ""
        driver.sendActionBar(Text.color(
            "${notchText(train.notch)} &f${Text.speed(train.speed)} &7(制限 ${Text.speed(train.props.speedLimit)})$dest$state",
        ))
    }

    // ---------- 作る・消す・つなぐ ----------

    /**
     * 線路の上に列車の道を作る。start の offset の位置が先頭になり、behind だけ後ろまでの線路をたどる。
     */
    fun buildPath(start: DirectedSegment, offset: Double, behind: Double): TrackPath {
        val world = start.segment.world
        val path = TrackPath(world)
        path.segments += start.flip()
        path.headOffset = start.length - offset
        val (walked, _) = path.advance(behind) { graph.next(it, null, true) }
        path.reverse(walked)
        return path
    }

    /** 列車を作って登録する */
    fun create(path: TrackPath, models: List<CartModel>, name: String? = null, props: TrainProperties? = null, cartProps: List<CartProperties?> = emptyList()): Train {
        val id = UUID.randomUUID()
        val train = Train(id, name ?: nextName(), path, props ?: defaultProps())
        train.gap = settings.cartGap
        for ((i, model) in models.withIndex()) {
            train.add(Cart(UUID.randomUUID(), model, cartProps.getOrNull(i) ?: CartProperties())) { it.model.length(plugin.models) }
        }
        train.computePoses()
        initOccupancy(train)
        trains[id] = train
        return train
    }

    fun defaultProps() = TrainProperties().also {
        it.speedLimit = settings.defaultSpeedLimit
        it.derail = settings.derail
    }

    private fun nextName(): String {
        var n = trains.size + 1
        while (trains.values.any { it.name == "train$n" }) n++
        return "train$n"
    }

    /** 線路のブロックの上に列車を置く道を作る。face の向きに進むように置く (null なら線路が続くほう) */
    fun pathAt(block: Block, face: BlockFace?, length: Double, look: Vector3d? = null): TrackPath? {
        val seg: TrackSegment = TrackBlocks.at(block).firstOrNull()
            ?: plugin.network.nodesAt(block.world.uid, Vector3d(block.x + 0.5, block.y + RailSegment.HEIGHT, block.z + 0.5)).firstOrNull()
                ?.let { n -> n.links.firstNotNullOfOrNull { plugin.network.nodes[it] }?.let { plugin.network.segment(n, it) } }
            ?: return null
        val forward = DirectedSegment(seg, false)
        val dir = face?.let { Vector3d(it.modX.toDouble(), it.modY.toDouble(), it.modZ.toDouble()) } ?: look
        val chosen = if (dir != null) {
            if (forward.tangent(forward.length / 2).dot(dir) >= 0) forward else forward.flip()
        } else {
            // 線路が続いているほうへ
            if (graph.next(forward) is Next.Found) forward else forward.flip()
        }
        return buildPath(chosen, chosen.length / 2, length + 2)
    }

    fun spawnSaved(name: String, block: Block, face: BlockFace?, speed: Double?, look: Vector3d? = null): Train? {
        val template = plugin.saved.get(name) ?: return null
        val models = template.carts.map { it.first }
        val length = models.sumOf { it.length(plugin.models) + settings.cartGap }
        val path = pathAt(block, face, length, look) ?: return null
        val props = defaultProps().also { it.copyFrom(template.props) }
        val train = create(path, models, null, props, template.carts.map { it.second })
        if (speed != null && speed > 0) train.action = LaunchAction(speed, null)
        return train
    }

    fun destroy(train: Train) {
        if (train.removed) return
        train.removed = true
        for (cart in train.carts) {
            val riders = cart.passengers()
            cart.visual?.let { visual ->
                val exits = riders.associateWith { visual.exitLocation(visual.seatOf(it)) }
                visual.despawn()
                exits.forEach { (p, loc) -> p.teleport(loc) }
            }
            cart.visual = null
        }
        trains.remove(train.id)
        plugin.mutexes.releaseAll(train.id)
        switchLocks.values.removeIf { it == train.id }
        releaseTickets(train.id)
        for (key in train.occupied) detectorRail(key, false)
        selected.values.removeIf { it == train.id }
    }

    /** 車両を一両だけ消す。最後の一両なら列車ごと消える。残りの車両は前へ詰める */
    fun removeCart(cart: Cart) {
        val train = cart.train
        if (train.removed) return
        if (train.carts.size <= 1) {
            destroy(train)
            return
        }
        val visual = cart.visual
        if (visual != null) {
            val exits = cart.passengers().associateWith { visual.exitLocation(visual.seatOf(it)) }
            visual.despawn()
            exits.forEach { (p, loc) -> p.teleport(loc) }
        }
        cart.visual = null
        train.carts.remove(cart)
        train.layout()
        train.computePoses()
        initOccupancy(train)
    }

    fun destroyAll(world: World?): Int {
        val targets = trains.values.filter { world == null || it.world == world }
        targets.forEach { destroy(it) }
        return targets.size
    }

    fun eject(cart: Cart) {
        val visual = cart.visual ?: return
        val exits = visual.passengers().associateWith { visual.exitLocation(visual.seatOf(it)) }
        visual.ejectAll()
        exits.forEach { (p, loc) -> p.teleport(loc) }
    }

    /** 列車 a の先頭を、列車 b の端に連結する */
    fun couple(a: Train, b: Train) {
        if (a === b || a.removed || b.removed) return
        val aHead = a.path.locate(0.0).point()
        val bHead = b.path.locate(0.0).point()
        val bTail = b.path.locate(b.length()).point()
        // b の最後尾が a の先頭のほうを向くようにする
        if (bHead.distanceSquared(aHead) < bTail.distanceSquared(aHead)) b.reverse()
        val merged = TrackPath(b.world)
        merged.segments += a.path.segments
        val bSegs = b.path.segments
        val seam = bSegs.indexOfFirst { it == a.path.head }
        merged.segments += bSegs.subList(if (seam >= 0) seam + 1 else 0, bSegs.size)
        merged.headOffset = b.path.headOffset
        if (seam == bSegs.lastIndex) merged.headOffset = max(b.path.headOffset, a.path.headOffset)
        b.path = merged
        for (cart in a.carts) {
            cart.train = b
            b.carts += cart
        }
        a.carts.clear()
        b.layout()
        b.props.tags += a.props.tags
        b.speed = 0.0
        b.resumeSpeed = 0.0
        b.action = null
        a.removed = true
        trains.remove(a.id)
        plugin.mutexes.releaseAll(a.id)
        releaseTickets(a.id)
        selected.replaceAll { _, id -> if (id == a.id) b.id else id }
        b.computePoses()
        initOccupancy(b)
        b.passengers().forEach { it.sendActionBar(Text.color("&a連結しました")) }
    }

    /** index 番目より後ろの車両を切り離して、別の列車にする */
    fun split(train: Train, index: Int): Train? {
        if (index <= 0 || index >= train.carts.size) return null
        val back = train.carts.subList(index, train.carts.size).toList()
        val path = train.path.copy()
        path.retreat(back.first().frontDistance)
        val props = TrainProperties().also { it.copyFrom(train.props) }
        val other = Train(UUID.randomUUID(), nextName(), path, props)
        other.gap = train.gap
        train.carts.removeAll(back.toSet())
        for (cart in back) {
            cart.train = other
            other.carts += cart
        }
        train.layout()
        other.layout()
        train.computePoses()
        other.computePoses()
        initOccupancy(train)
        initOccupancy(other)
        trains[other.id] = other
        return other
    }

    /** 列車を別の線路のブロックへ移す (ワールドを越えてもよい)。乗客も一緒に移る */
    fun teleport(train: Train, rail: BlockKey, face: BlockFace?) {
        if (train.removed) return
        val world = rail.world() ?: return
        val block = world.getBlockAt(rail.x, rail.y, rail.z)
        val path = pathAt(block, face, train.length()) ?: return
        val riders = train.carts.map { cart ->
            val r = cart.visual?.takeRiders() ?: emptyMap()
            cart.visual?.despawn()
            r
        }
        for (key in train.occupied) detectorRail(key, false)
        train.path = path
        train.freeVelocity = null
        train.derailedFrom = null
        train.layout()
        train.computePoses()
        initOccupancy(train)
        train.action = null
        for ((i, cart) in train.carts.withIndex()) {
            val r = riders[i]
            if (r.isEmpty()) continue
            if (cart.visual == null) cart.visual = CartVisual(plugin, cart)
            r.values.forEach { it.teleport(org.bukkit.Location(world, cart.position.x, cart.position.y + 1, cart.position.z)) }
            cart.visual!!.restoreRiders(r)
        }
    }

    // ---------- 選択 ----------

    fun trainOf(player: Player): Train? {
        player.vehicle?.let { v -> entityCarts[v.uniqueId]?.let { return it.train } }
        selected[player.uniqueId]?.let { id -> trains[id]?.let { return it } }
        return nearest(player.location.toVector().toVector3d(), player.world, 16.0)
    }

    fun cartOf(player: Player): Cart? = player.vehicle?.let { entityCarts[it.uniqueId] }

    fun nearest(p: Vector3d, world: World, radius: Double): Train? =
        trains.values.filter { it.world == world }
            .flatMap { t -> t.carts.map { t to it.position.distanceSquared(p) } }
            .filter { it.second <= radius * radius }
            .minByOrNull { it.second }?.first

    fun byName(name: String): Train? =
        trains.values.firstOrNull { it.name.equals(name, true) } ?: runCatching { trains[UUID.fromString(name)] }.getOrNull()

    // ---------- チャンク ----------

    private fun updateChunkTickets() {
        val r = settings.chunkRadius
        for (train in trains.values) {
            if (!train.props.keepChunksLoaded) {
                releaseTickets(train.id)
                continue
            }
            val want = HashSet<Pair<UUID, Long>>()
            for (cart in train.carts) {
                val cx = kotlin.math.floor(cart.position.x).toInt() shr 4
                val cz = kotlin.math.floor(cart.position.z).toInt() shr 4
                for (dx in -r..r) for (dz in -r..r) want += train.world.uid to chunkKey(cx + dx, cz + dz)
            }
            val have = chunkTickets[train.id] ?: emptySet()
            for ((w, k) in want - have) Bukkit.getWorld(w)?.getChunkAt(chunkX(k), chunkZ(k))?.addPluginChunkTicket(plugin)
            for ((w, k) in have - want) Bukkit.getWorld(w)?.getChunkAt(chunkX(k), chunkZ(k))?.removePluginChunkTicket(plugin)
            chunkTickets[train.id] = want
        }
    }

    private fun releaseTickets(train: UUID) {
        val have = chunkTickets.remove(train) ?: return
        // ほかの列車がまだ使っているチャンクは残す
        val inUse = chunkTickets.values.flatten().toSet()
        for ((w, k) in have - inUse) {
            val world = Bukkit.getWorld(w) ?: continue
            if (world.isChunkLoaded(chunkX(k), chunkZ(k))) world.getChunkAt(chunkX(k), chunkZ(k)).removePluginChunkTicket(plugin)
        }
    }

    private fun chunkKey(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    private fun chunkX(k: Long) = (k shr 32).toInt()
    private fun chunkZ(k: Long) = k.toInt()

    // ---------- 保存 ----------

    private val file get() = File(plugin.dataFolder, "trains.yml")

    fun saveAll() {
        val yaml = YamlConfiguration()
        for (train in trains.values) {
            val s = yaml.createSection("trains.${train.id}")
            writeTrain(train, s)
        }
        for ((id, raw) in pendingRaw) yaml.createSection("trains.$id", raw)
        try {
            plugin.dataFolder.mkdirs()
            yaml.save(file)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "trains.yml を保存できませんでした", e)
        }
        plugin.places.saveIfDirty()
    }

    private fun writeTrain(train: Train, s: ConfigurationSection) {
        s.set("name", train.name)
        s.set("world", train.world.uid.toString())
        s.set("speed", train.speed)
        s.set("segments", train.path.segments.map { "${it.segment.id}${if (it.reversed) "<" else ">"}" })
        s.set("head-offset", train.path.headOffset)
        train.freeVelocity?.let { s.set("free-velocity", listOf(it.x, it.y, it.z)) }
        val head = train.path.locate(0.0).point()
        s.set("head.x", head.x)
        s.set("head.y", head.y)
        s.set("head.z", head.z)
        train.props.save(s.createSection("properties"))
        for ((i, cart) in train.carts.withIndex()) {
            val c = s.createSection("carts.$i")
            c.set("id", cart.id.toString())
            c.set("flipped", cart.flipped)
            cart.model.save(c.createSection("model"))
            cart.props.save(c.createSection("properties"))
        }
    }

    fun loadAll() {
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("trains") ?: return
        for (key in section.getKeys(false)) {
            val s = section.getConfigurationSection(key) ?: continue
            val train = try {
                readTrain(UUID.fromString(key), s)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "列車 $key を読み込めませんでした", e)
                null
            }
            if (train == null) {
                pendingRaw[key] = toMap(s)
                continue
            }
            trains[train.id] = train
        }
        plugin.logger.info("列車を ${trains.size} 本読み込みました" + if (pendingRaw.isNotEmpty()) " (${pendingRaw.size} 本は保留)" else "")
    }

    private fun toMap(s: ConfigurationSection): Map<String, Any?> =
        s.getKeys(false).associateWith { k -> s.get(k).let { if (it is ConfigurationSection) toMap(it) else it } }

    private fun readTrain(id: UUID, s: ConfigurationSection): Train? {
        val world = Bukkit.getWorld(UUID.fromString(s.getString("world"))) ?: return null
        val path = TrackPath(world)
        for (text in s.getStringList("segments")) {
            val reversed = text.endsWith("<")
            val segId = text.dropLast(1)
            val seg = graph.segmentById(world, segId)
            if (seg == null) {
                // 線路が変わっていたら、そこから先は覚えていた道を捨てる
                path.segments.clear()
                continue
            }
            path.segments += DirectedSegment(seg, reversed)
        }
        val props = TrainProperties().also { it.load(s.getConfigurationSection("properties") ?: s.createSection("properties")) }
        val cartSections = s.getConfigurationSection("carts")?.let { c -> c.getKeys(false).sortedBy { it.toIntOrNull() ?: 0 }.mapNotNull { c.getConfigurationSection(it) } } ?: emptyList()
        if (cartSections.isEmpty()) return null

        if (path.segments.isEmpty()) {
            // 覚えていた先頭の位置に最も近い線路から作り直す
            val head = Vector3d(s.getDouble("head.x"), s.getDouble("head.y"), s.getDouble("head.z"))
            world.getChunkAt(kotlin.math.floor(head.x).toInt() shr 4, kotlin.math.floor(head.z).toInt() shr 4)
            val pos = graph.nearest(world, head, 2.0) ?: return null
            val rebuilt = buildPath(pos.segment, pos.offset, 64.0)
            path.segments += rebuilt.segments
            path.headOffset = rebuilt.headOffset
        } else {
            path.headOffset = s.getDouble("head-offset").coerceIn(0.0, path.head.length)
        }

        val train = Train(id, s.getString("name") ?: nextName(), path, props)
        train.gap = settings.cartGap
        for (c in cartSections) {
            val cart = Cart(
                runCatching { UUID.fromString(c.getString("id")) }.getOrElse { UUID.randomUUID() },
                CartModel.load(c.getConfigurationSection("model")),
                CartProperties().also { p -> c.getConfigurationSection("properties")?.let { p.load(it) } },
            )
            cart.flipped = c.getBoolean("flipped")
            train.add(cart) { it.model.length(plugin.models) }
        }
        // 覚えていた道が列車より短ければ、後ろへ線路をたどって足す
        if (path.covered() < train.length()) {
            val rebuilt = buildPath(path.head, path.headOffset, train.length() + 2)
            path.segments.clear()
            path.segments += rebuilt.segments
            path.headOffset = rebuilt.headOffset
        }
        train.speed = s.getDouble("speed")
        val free = s.getDoubleList("free-velocity")
        if (free.size == 3 && path.head.segment is FreeSegment) {
            train.freeVelocity = Vector3d(free[0], free[1], free[2])
            train.freeTicks = 10
        }
        train.computePoses()
        initOccupancy(train)
        return train
    }

    /** 終了のとき。保存してから見た目を消す */
    fun shutdown() {
        saveAll()
        for (train in trains.values) for (cart in train.carts) {
            cart.visual?.despawn()
            cart.visual = null
        }
        for (id in chunkTickets.keys.toList()) releaseTickets(id)
    }
}
