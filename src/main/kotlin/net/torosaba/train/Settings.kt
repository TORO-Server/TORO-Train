package net.torosaba.train

import org.bukkit.configuration.file.FileConfiguration

/** config.yml の値 */
class Settings(c: FileConfiguration) {
    /** Display エンティティの移動と回転を滑らかにする tick 数 */
    val interpolationTicks = c.getInt("render.interpolation-ticks", 2).coerceIn(0, 10)
    /** Display エンティティが見える距離の倍率 (1.0 で 64 ブロック) */
    val viewRange = c.getDouble("render.view-range", 2.0).toFloat()
    /** 座席のエンティティを、座る人の足の位置からどれだけ上に置くか */
    val seatEntityOffset = c.getDouble("render.seat-entity-offset", 0.6)
    /** MarumaSign の看板のアイテムを、アイテムのディスプレイが回す分だけ逆に回すか */
    val flipSignItems = c.getBoolean("render.marumasign-item-flip", true)
    /** 1 両に描くブロックの上限 */
    val maxModelBlocks = c.getInt("render.max-model-blocks", 8192)

    val cartGap = c.getDouble("train.cart-gap", 0.3)
    val defaultSpeedLimit = c.getDouble("train.default-speed-limit", 0.4)
    val maxSpeed = c.getDouble("train.max-speed", 3.0)
    val slopeGravity = c.getDouble("train.slope-gravity", 0.012)
    val poweredRailBoost = c.getDouble("train.powered-rail.boost", 0.02)
    val poweredRailMaxSpeed = c.getDouble("train.powered-rail.max-speed", 0.4)
    val poweredRailBrake = c.getDouble("train.powered-rail.brake", 0.03)
    val chunkRadius = c.getInt("train.keep-chunks-radius", 2)

    val stationDelayTicks = c.getInt("signs.station-default-delay", 100)
    val routeSearchDistance = c.getDouble("route.max-distance", 5000.0)

    val autosaveTicks = c.getLong("storage.autosave-seconds", 300) * 20

    /** 看板の処理などをログに出す */
    val debug = c.getBoolean("debug", false)
}
