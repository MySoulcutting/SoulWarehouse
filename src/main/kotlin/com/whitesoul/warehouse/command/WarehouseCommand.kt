package com.whitesoul.warehouse.command

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.config.PluginLang
import com.whitesoul.warehouse.service.WarehouseSaveService
import com.whitesoul.warehouse.service.WarehouseRemarkInputService
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
import taboolib.expansion.createHelper
import taboolib.platform.util.sendLang

@CommandHeader(name = "warehouse", aliases = ["sw"], description = "SoulWarehouse command")
object WarehouseCommand {

    @CommandBody
    val main = mainCommand {
       createHelper()
    }
    

    @CommandBody(permission = "soulwarehouse.admin.open")
    val use = subCommand {
        dynamic("target") {
            suggestionUncheck<CommandSender> { _, _ -> PlayerLookup.suggestNames() }
            execute<CommandSender> { sender, _, target ->
                val targetPlayer = PlayerLookup.findOnlinePlayer(target) ?: run {
                    sender.sendLang("player-not-found", target)
                    return@execute
                }
                WarehouseHomeMenu.openSelf(targetPlayer)
                sender.sendLang("command-use-opened", targetPlayer.name)
            }
        }
    }

    @CommandBody(permission = "soulwarehouse.admin.open")
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

    @CommandBody(permission = "soulwarehouse.admin.reload")
    val reload = subCommand {
        execute<CommandSender> { sender, _, _ ->
            // reload 前关闭所有正在使用仓库菜单的玩家界面，避免旧缓存被菜单关闭回写覆盖新数据。
            org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
                if (WarehouseSessionService.hasSession(player)) {
                    WarehouseSessionService.beginTransition(player)
                    player.closeInventory()
                    WarehouseSessionService.end(player)
                }
            }
            val onlinePlayers = org.bukkit.Bukkit.getOnlinePlayers().map { it.uniqueId to it.name }
            WarehouseRemarkInputService.clearAll()
            WarehouseSaveService.stop()
            // 保存旧缓存、加载配置和候选数据库连接在同一重载事务内完成。
            submitAsync {
                val result = WarehouseService.reload {
                    PluginConfig.reload()
                    WarehouseUiConfigs.reload()
                    PluginLang.reload()
                }
                if (result.successful) {
                    onlinePlayers.forEach { (uuid, name) -> WarehouseService.preloadOwner(uuid, name) }
                }
                WarehouseSaveService.restart()
                submit {
                    if (result.successful) {
                        sender.sendLang("command-reload-done")
                    } else {
                        sender.sendLang("command-reload-failed", result.error ?: "unknown")
                    }
                }
            }
        }
    }

    @CommandBody(permission = "soulwarehouse.admin.saveall")
    val saveall = subCommand {
        execute<CommandSender> { sender, _, _ ->
            submitAsync {
                val pages = WarehouseService.saveDirtyPages(force = true)
                val profiles = WarehouseService.saveDirtyProfiles(force = true)
                submit {
                    val error = pages.error ?: profiles.error
                    if (error != null) {
                        sender.sendLang("storage-unavailable", error)
                    } else if (pages.successful && profiles.successful) {
                        sender.sendLang("command-saveall-done", pages.saved, profiles.saved)
                    } else {
                        sender.sendLang("command-saveall-failed", pages.saved, pages.failed, profiles.saved, profiles.failed)
                    }
                }
            }
        }
    }

    @CommandBody(permission = "soulwarehouse.admin.page")
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
        val value = valueStr.toIntOrNull()
        if (value == null || value < 0) {
            sender.sendLang("command-page-invalid-number", valueStr)
            return
        }
        val target = PlayerLookup.findOfflinePlayer(playerName) ?: run {
            sender.sendLang("player-not-found", playerName)
            return
        }
        val uuid = target.uniqueId
        val targetName = target.name ?: uuid.toString()
        submitAsync {
            if (!WarehouseService.preloadOwner(uuid, targetName)) {
                val error = WarehouseService.getOwnerLoadError(uuid) ?: WarehouseService.getLastError() ?: "unknown"
                submit { sender.sendLang("storage-unavailable", error) }
                return@submitAsync
            }
            val result = when (action) {
                "set" -> WarehouseService.setMaxPages(uuid, value)
                "add" -> WarehouseService.addMaxPages(uuid, value)
                "remove" -> WarehouseService.removeMaxPages(uuid, value)
                else -> return@submitAsync
            }
            val saved = WarehouseService.saveProfile(uuid, force = true)
            submit {
                if (saved) {
                    sender.sendLang("command-page-$action", targetName, result)
                } else {
                    sender.sendLang("command-page-save-failed", targetName)
                }
            }
        }
    }

    private fun handleOpen(sender: CommandSender, target: String, pageArg: String?) {
        val player = sender as? Player ?: run {
            sender.sendLang("player-only")
            return
        }
        if (pageArg == null) {
            // open 只有一个参数时，优先按"打开自己的某一页"解析；失败后再视为管理员查看玩家。
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
            val targetPlayer = PlayerLookup.findOfflinePlayer(target) ?: run {
                player.sendLang("player-not-found", target)
                return
            }
            WarehouseInspectMenu.openHome(player, targetPlayer)
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
