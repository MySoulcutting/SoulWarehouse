package com.whitesoul.warehouse.ui

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.service.WarehouseService
import com.whitesoul.warehouse.service.WarehouseSessionService
import com.whitesoul.warehouse.util.cloneOrNull
import org.bukkit.entity.Player
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.StorableChest
import taboolib.platform.util.sendLang

object WarehousePageMenu {

    fun openSelf(player: Player, page: Int) {
        if (!WarehouseService.isAvailable()) {
            player.sendLang("storage-unavailable", WarehouseService.getLastError() ?: "unknown")
            return
        }
        val profile = WarehouseService.touchProfile(player)
        if (!WarehouseService.isPageAccessible(player, page)) {
            player.sendLang("page-locked", page)
            return
        }
        val snapshot = WarehouseService.snapshotPageContents(player.uniqueId, page)
        val remark = WarehouseService.getPageRemark(player.uniqueId, page)
        val activeSlots = PageUiConfig.activeStorageSlots
        val inactiveSlots = PageUiConfig.inactiveStorageSlots
        val backgroundSlots = PageUiConfig.backgroundSlots
        // snapshot 是本次菜单会话的唯一编辑源，关闭时统一回写到缓存页数据。
        val activeSlotIndex = activeSlots.withIndex().associate { it.value to it.index }
        val activeSlotSet = activeSlots.toSet()
        WarehouseSessionService.beginTransition(player, page)
        player.openMenu<StorableChest>(PageUiConfig.formatTitle(mapOf("player" to player.name, "page" to page))) {
            rows(PageUiConfig.rows)
            handLocked(false)
            autoStack(true)

            rule {
                checkSlot { _, itemStack, slot ->
                    slot in activeSlotSet && WarehouseService.isItemAllowed(itemStack)
                }
                writeItem { inventory, itemStack, slot, _ ->
                    inventory.setItem(slot, itemStack.cloneOrNull())
                    activeSlotIndex[slot]?.let { index ->
                        snapshot[index] = itemStack.cloneOrNull()
                    }
                }
                firstSlot { inventory, itemStack ->
                    MenuSupport.firstStorageSlot(inventory, itemStack, activeSlots)
                }
                mergeSlots { inventory, itemStack ->
                    MenuSupport.mergeStorageSlots(inventory, itemStack, activeSlots)
                }
            }

            onBuild { _, inventory ->
                activeSlots.forEachIndexed { index, slot ->
                    inventory.setItem(slot, snapshot[index])
                }
                inactiveSlots.forEach { slot ->
                    inventory.setItem(slot, PageUiConfig.disabledStorage.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))
                }
                backgroundSlots.forEach { slot ->
                    inventory.setItem(slot, PageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))
                }
            }

            if (page <= 1) {
                set(PageUiConfig.previousButton.slot!!, PageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE)) { isCancelled = true }
            }

            if (!(page < profile.maxPages && page < PluginConfig.maxPages)) {
                set(PageUiConfig.nextButton.slot!!, PageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE)) { isCancelled = true }
            }

            if (page > 1) {
                set(
                    PageUiConfig.previousButton.slot!!,
                    PageUiConfig.previousButton.buildItem(
                        placeholders = mapOf("page" to (page - 1)),
                        fallbackMaterial = org.bukkit.Material.ARROW,
                    ),
                ) {
                    isCancelled = true
                    MenuSupport.persistPageContents(player.uniqueId, page, snapshot, true)
                    WarehouseSessionService.beginTransition(player, page - 1)
                    MenuSupport.reopen(clicker) {
                        openSelf(player, page - 1)
                    }
                }
            }

            set(
                PageUiConfig.currentButton.slot!!,
                PageUiConfig.currentButton.buildItem(
                    placeholders = mapOf(
                        "page" to page,
                        "max_pages" to profile.maxPages,
                        "page_size" to MenuSupport.storageCapacity(),
                        "remark" to remark,
                    ),
                    skipBlankKeys = setOf("remark"),
                    fallbackMaterial = org.bukkit.Material.CHEST,
                ),
            ) {
                isCancelled = true
            }

            set(
                PageUiConfig.backButton.slot!!,
                PageUiConfig.backButton.buildItem(fallbackMaterial = org.bukkit.Material.BOOK),
            ) {
                isCancelled = true
                MenuSupport.persistPageContents(player.uniqueId, page, snapshot, true)
                WarehouseSessionService.beginTransition(player, null)
                MenuSupport.reopen(clicker) {
                    WarehouseHomeMenu.openSelf(player)
                }
            }

            set(
                PageUiConfig.closeButton.slot!!,
                PageUiConfig.closeButton.buildItem(fallbackMaterial = org.bukkit.Material.BARRIER),
            ) {
                isCancelled = true
                clicker.closeInventory()
            }

            if (page < profile.maxPages && page < PluginConfig.maxPages) {
                set(
                    PageUiConfig.nextButton.slot!!,
                    PageUiConfig.nextButton.buildItem(
                        placeholders = mapOf("page" to (page + 1)),
                        fallbackMaterial = org.bukkit.Material.ARROW,
                    ),
                ) {
                    isCancelled = true
                    MenuSupport.persistPageContents(player.uniqueId, page, snapshot, true)
                    WarehouseSessionService.beginTransition(player, page + 1)
                    MenuSupport.reopen(clicker) {
                        openSelf(player, page + 1)
                    }
                }
            }

            onClose { event ->
                val viewer = event.player as? Player ?: return@onClose
                if (WarehouseSessionService.isNavigating(viewer)) {
                    // 切页时会先 close 当前菜单，这种 close 不能当成真正退出仓库。
                    return@onClose
                }
                MenuSupport.persistPageContents(viewer.uniqueId, page, snapshot, true)
                WarehouseSessionService.end(viewer)
            }
        }
        WarehouseSessionService.completeOpen(player, page)
    }
}
