package net.torosaba.train.command

import net.torosaba.train.TOROTrain
import net.torosaba.train.util.Text
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import java.util.Locale

/** /mascon on|off: プレイヤーごとにマスコンの操作を使うかを決める (D を押しても入らなくなる) */
class MasconCommand(private val plugin: TOROTrain) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(Text.color("&cプレイヤーのみ使用できます"))
            return true
        }
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "on" -> {
                plugin.mascon.setEnabled(player, true)
                player.sendMessage(Text.color("&a[Mascon] Mascon機能を有効化しました"))
            }
            "off" -> {
                plugin.mascon.setEnabled(player, false)
                player.sendMessage(Text.color("&c[Mascon] このプレイヤーではMascon機能を無効化しました"))
            }
            else -> player.sendMessage(Text.color("&e使用方法: /mascon on | off"))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> =
        if (args.size == 1) listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) } else emptyList()
}
