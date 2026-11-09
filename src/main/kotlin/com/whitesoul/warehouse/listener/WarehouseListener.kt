package com.whitesoul.warehouse.listener

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.placeholder.WarehousePlaceholders
import com.whitesoul.warehouse.service.WarehouseRemarkInputService
import com.whitesoul.warehouse.service.WarehouseService
import com.whitesoul.warehouse.service.WarehouseSessionService
import com.whitesoul.warehouse.ui.PageUiConfig
import com.whitesoul.warehouse.util.DebugLogger
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginEnableEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submitAsync
import taboolib.module.nms.getName
import taboolib.platform.util.sendLang

object WarehouseListener {

    @SubscribeEvent
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!WarehouseService.isAvailable() || !player.hasPermission("soulwarehouse.use")) return
        // 异步预热缓存，避免后续自动拾取/打开菜单时在主线程触发数据库 IO。
        submitAsync {
            WarehouseService.preloadOwner(player.uniqueId, player.name)
        }
    }

    /** PlaceholderAPI 在本插件之后启用（如 reload）时兜底注册变量。 */
    @SubscribeEvent
    fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name.equals("PlaceholderAPI", ignoreCase = true)) {
            WarehousePlaceholders.setup()
        }
    }

    @SubscribeEvent(EventPriority.MONITOR)
    fun onPickup(event: EntityPickupItemEvent) {
        if (event.isCancelled) {
            return
        }
        val player = event.entity as? Player ?: return
        if (!WarehouseService.isAvailable() || !WarehouseService.isOwnerLoaded(player.uniqueId) || !player.hasPermission("soulwarehouse.use")) {
            return
        }
        // 玩家个人关闭了自动拾取入仓
        if (!WarehouseService.isAutoPickup(player)) {
            return
        }
        if (WarehouseSessionService.hasSession(player)) {
            // 玩家正在仓库界面时，菜单持有的 snapshot 会在关闭时回写，直接自动入仓会被覆盖。
            return
        }
        val itemEntity = event.item
        val itemName = itemEntity.itemStack.getName()
        DebugLogger.log { "onPickup 开始: player=${player.name}, item=$itemName x${itemEntity.itemStack.amount}" }
        val result = WarehouseService.storeItem(player, itemEntity.itemStack, PageUiConfig.storageCapacity)
        if (result.storedAmount <= 0) {
            DebugLogger.log { "onPickup 未存入: player=${player.name}, item=$itemName" }
            return
        }
        DebugLogger.log { "onPickup 存入: player=${player.name}, item=$itemName, stored=${result.storedAmount}, remaining=${result.remaining?.amount ?: 0}" }
        player.sendLang("auto-store-message", itemName, result.storedAmount)
        PluginConfig.autoStoreSound?.let { sound ->
            player.playSound(player.location, sound.sound, sound.volume, sound.pitch)
        }
        // 取消原拾取流程，改为“仓库优先，背包兜底，地面保留剩余”。
        event.isCancelled = true
        val remaining = result.remaining
        if (remaining == null) {
            itemEntity.remove()
            return
        }
        val leftovers = player.inventory.addItem(remaining).values
        if (leftovers.isEmpty()) {
            itemEntity.remove()
            return
        }
        val leftoverAmount = leftovers.sumOf { it.amount }
        itemEntity.itemStack = remaining.clone().apply {
            amount = leftoverAmount
        }
    }

    @SubscribeEvent
    fun onQuit(event: PlayerQuitEvent) {
        handleLeave(event.player)
    }

    @SubscribeEvent
    fun onKick(event: PlayerKickEvent) {
        handleLeave(event.player)
    }

    private fun handleLeave(player: Player) {
        val uuid = player.uniqueId
        val playerName = player.name
        WarehouseRemarkInputService.clear(player)
        WarehouseSessionService.end(player)
        // 退出事件位于主线程，数据库落盘和缓存驱逐放到异步任务。
        submitAsync {
            DebugLogger.timed("handleLeave save player=$playerName") {
                WarehouseService.saveOwnerPages(uuid, force = true)
                WarehouseService.saveProfile(uuid, force = true)
            }
            WarehouseService.evictOwner(uuid)
        }
    }
}
