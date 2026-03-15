package com.whitesoul.warehouse.command

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.config.PluginLang
import com.whitesoul.warehouse.service.WarehouseSaveService
import com.whitesoul.warehouse.service.WarehouseService
import com.whitesoul.warehouse.service.WarehouseSessionService
import com.whitesoul.warehouse.ui.WarehouseHomeMenu
import com.whitesoul.warehouse.ui.WarehouseInspectMenu
import com.whitesoul.warehouse.ui.WarehousePageMenu
import com.whitesoul.warehouse.ui.WarehouseUiConfigs
import com.whitesoul.warehouse.util.PlayerLookup
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import taboolib.platform.util.sendLang

@CommandHeader(name = "warehouse", aliases = ["sw"], description = "SoulWarehouse command")
object WarehouseCommand {

    @CommandBody
    val main = mainCommand {
        execute<CommandSender> { sender, _, _ ->
            val player = sender as? Player ?: run {
                sender.sendLang("player-only")
                return@execute
            }
            if (!player.hasPermission("soulwarehouse.use")) {
                player.sendLang("no-permission")
                return@execute
            }
            WarehouseHomeMenu.openSelf(player)
        }
    }

    @CommandBody
    val open = subCommand {
        dynamic("target") {
            suggestionUncheck<CommandSender> { _, _ ->
                ((1..PluginConfig.maxPages).map { it.toString() } + PlayerLookup.suggestNames()).distinct().sorted()
            }
            execute<CommandSender> { sender, _, target ->
                handleOpen(sender, target, null)
            }
            dynamic("page", optional = true) {
                suggestionUncheck<CommandSender> { _, _ ->
                    (1..PluginConfig.maxPages).map { it.toString() }
                }
                execute<CommandSender> { sender, ctx, page ->
                    handleOpen(sender, ctx["target"], page)
                }
            }
        }
    }

    @CommandBody
    val reload = subCommand {
        execute<CommandSender> { sender, _, _ ->
            if (!sender.hasPermission("soulwarehouse.admin.reload")) {
                sender.sendLang("no-permission")
                return@execute
            }
            // reload 前关闭所有正在使用仓库菜单的玩家界面，避免旧缓存被菜单关闭回写覆盖新数据。
            org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
                if (WarehouseSessionService.hasSession(player)) {
                    WarehouseSessionService.beginTransition(player)
                    player.closeInventory()
                    WarehouseSessionService.end(player)
                }
            }
            PluginConfig.reload()
            WarehouseUiConfigs.reload()
            PluginLang.reload()
            // 数据库 reload（含保存+关闭+重连）放到异步，完成后回主线程通知。
            submitAsync {
                WarehouseService.reload()
                WarehouseSaveService.restart()
                submit {
                    if (WarehouseService.isAvailable()) {
                        sender.sendLang("command-reload-done")
                    } else {
                        sender.sendLang("storage-unavailable", WarehouseService.getLastError() ?: "unknown")
                    }
                }
            }
        }
    }

    @CommandBody
    val saveall = subCommand {
        execute<CommandSender> { sender, _, _ ->
            if (!sender.hasPermission("soulwarehouse.admin.saveall")) {
                sender.sendLang("no-permission")
                return@execute
            }
            submitAsync {
                val saved = WarehouseService.saveDirtyPages(force = true)
                WarehouseService.saveDirtyProfiles(force = true)
                submit { sender.sendLang("command-saveall-done", saved) }
            }
        }
    }

    @CommandBody
    val page = subCommand {
        // /warehouse page set <player> <pages>
        literal("set") {
            dynamic("player") {
                suggestionUncheck<CommandSender> { _, _ -> PlayerLookup.suggestNames() }
                dynamic("pages") {
                    suggestionUncheck<CommandSender> { _, _ -> (1..PluginConfig.maxPages).map { it.toString() } }
                    execute<CommandSender> { sender, ctx, pages ->
                        handlePageModify(sender, ctx["player"], pages, "set")
                    }
                }
            }
        }
        // /warehouse page add <player> <amount>
        literal("add") {
            dynamic("player") {
                suggestionUncheck<CommandSender> { _, _ -> PlayerLookup.suggestNames() }
                dynamic("amount") {
                    suggestionUncheck<CommandSender> { _, _ -> listOf("1", "2", "3", "5") }
                    execute<CommandSender> { sender, ctx, amount ->
                        handlePageModify(sender, ctx["player"], amount, "add")
                    }
                }
            }
        }
        // /warehouse page remove <player> <amount>
        literal("remove") {
            dynamic("player") {
                suggestionUncheck<CommandSender> { _, _ -> PlayerLookup.suggestNames() }
                dynamic("amount") {
                    suggestionUncheck<CommandSender> { _, _ -> listOf("1", "2", "3", "5") }
                    execute<CommandSender> { sender, ctx, amount ->
                        handlePageModify(sender, ctx["player"], amount, "remove")
                    }
                }
            }
        }
    }

    private fun handlePageModify(sender: CommandSender, playerName: String, valueStr: String, action: String) {
        if (!sender.hasPermission("soulwarehouse.admin.page")) {
            sender.sendLang("no-permission")
            return
        }
        val value = valueStr.toIntOrNull()
        if (value == null || value < 0) {
            sender.sendLang("command-page-invalid-number", valueStr)
            return
        }
        val target = PlayerLookup.findOfflinePlayer(playerName) ?: run {
            sender.sendLang("player-not-found", playerName)
            return
        }
        val targetName = target.name ?: target.uniqueId.toString()
        val result = when (action) {
            "set" -> WarehouseService.setMaxPages(target.uniqueId, value)
            "add" -> WarehouseService.addMaxPages(target.uniqueId, value)
            "remove" -> WarehouseService.removeMaxPages(target.uniqueId, value)
            else -> return
        }
        submitAsync { WarehouseService.saveProfile(target.uniqueId, force = true) }
        sender.sendLang("command-page-$action", targetName, result)
    }

    private fun handleOpen(sender: CommandSender, target: String, pageArg: String?) {
        val player = sender as? Player ?: run {
            sender.sendLang("player-only")
            return
        }
        if (pageArg == null) {
            // open 只有一个参数时，优先按“打开自己的某一页”解析；失败后再视为管理员查看玩家。
            val selfPage = target.toIntOrNull()
            if (selfPage != null) {
                if (!player.hasPermission("soulwarehouse.use")) {
                    player.sendLang("no-permission")
                    return
                }
                val page = parsePage(player, target) ?: return
                WarehousePageMenu.openSelf(player, page)
                return
            }
            if (!player.hasPermission("soulwarehouse.admin.open")) {
                player.sendLang("no-permission")
                return
            }
            val targetPlayer = PlayerLookup.findOfflinePlayer(target) ?: run {
                player.sendLang("player-not-found", target)
                return
            }
            WarehouseInspectMenu.openHome(player, targetPlayer)
            return
        }
        if (!player.hasPermission("soulwarehouse.admin.open")) {
            player.sendLang("no-permission")
            return
        }
        val page = parsePage(player, pageArg) ?: return
        val targetPlayer = PlayerLookup.findOfflinePlayer(target) ?: run {
            player.sendLang("player-not-found", target)
            return
        }
        WarehouseInspectMenu.openPage(player, targetPlayer, page)
    }

    private fun parsePage(sender: CommandSender, raw: String): Int? {
        val page = raw.toIntOrNull()
        if (page == null || page !in 1..PluginConfig.maxPages) {
            sender.sendLang("page-invalid", PluginConfig.maxPages)
            return null
        }
        return page
    }
}
