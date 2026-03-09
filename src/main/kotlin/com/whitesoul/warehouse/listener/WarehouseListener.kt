package com.whitesoul.warehouse.listener

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.service.WarehouseRemarkInputService
import com.whitesoul.warehouse.service.WarehouseService
import com.whitesoul.warehouse.service.WarehouseSessionService
import com.whitesoul.warehouse.ui.PageUiConfig
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerPickupItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.event.SubscribeEvent

object WarehouseListener {

    @SubscribeEvent
    fun onPickup(event: PlayerPickupItemEvent) {
        if (event.isCancelled || !PluginConfig.autoStoreDrops) {
            return
        }
        val player = event.player
        if (!WarehouseService.isAvailable() || !player.hasPermission("soulwarehouse.use")) {
            return
        }
        if (WarehouseSessionService.hasSession(player)) {
            // 玩家正在仓库界面时，菜单持有的 snapshot 会在关闭时回写，直接自动入仓会被覆盖。
            return
        }
        val itemEntity = event.item
        val result = WarehouseService.storeItem(player, itemEntity.itemStack, PageUiConfig.storageCapacity)
        if (result.storedAmount <= 0) {
            return
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
        if (PluginConfig.saveOnQuit) {
            WarehouseService.saveOwnerPages(player.uniqueId, force = true)
            WarehouseService.saveOwnerPageRemarks(player.uniqueId, force = true)
            WarehouseService.saveProfile(player.uniqueId, force = true)
        }
        WarehouseRemarkInputService.clear(player)
        WarehouseSessionService.end(player)
    }
}
