package com.whitesoul.warehouse.listener

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.service.WarehouseRemarkInputService
import com.whitesoul.warehouse.service.WarehouseService
import com.whitesoul.warehouse.service.WarehouseSessionService
import com.whitesoul.warehouse.ui.PageUiConfig
import com.whitesoul.warehouse.util.DebugLogger
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerPickupItemEvent
import org.bukkit.event.player.PlayerQuitEvent
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
            WarehouseService.preloadOwner(player.uniqueId)
        }
    }

    @SubscribeEvent(EventPriority.MONITOR)
    fun onPickup(event: PlayerPickupItemEvent) {
        if (event.isCancelled) {
            return
        }
        val player = event.player
        if (!WarehouseService.isAvailable() || !player.hasPermission("soulwarehouse.use")) {
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
        // 下线/被踢统一走同一套收尾逻辑，保证会话与缓存都能被清理。
        DebugLogger.timed("handleLeave save player=${player.name}") {
            WarehouseService.saveOwnerPages(player.uniqueId, force = true)
            WarehouseService.saveProfile(player.uniqueId, force = true)
        }
        WarehouseRemarkInputService.clear(player)
        WarehouseSessionService.end(player)
        // 保存完毕后驱逐缓存，防止离线玩家数据长期驻留内存。
        WarehouseService.evictOwner(player.uniqueId)
    }
}
