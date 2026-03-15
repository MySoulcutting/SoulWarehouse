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
        val activeSlotIndex = activeSlots.withIndex().associate { it.value to it.index }
        val activeSlotSet = activeSlots.toSet()

        val bgChar = PageUiConfig.background.char
        val prevChar = PageUiConfig.previousButton.char
        val nextChar = PageUiConfig.nextButton.char

        WarehouseSessionService.beginTransition(player, page)
        player.openMenu<StorableChest>(PageUiConfig.formatTitle(mapOf("player" to player.name, "page" to page))) {
            map(*PageUiConfig.layoutLines.toTypedArray())
            rows(PageUiConfig.rows)
            handLocked(false)
            autoStack(true)

            // 背景填充（通过 char 设置，map 会自动填充所有 B 字符位置）
            set(bgChar, PageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))

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

            // 存储区物品通过 onBuild 填充（每个槽位内容不同）
            onBuild { _, inventory ->
                activeSlots.forEachIndexed { index, slot ->
                    inventory.setItem(slot, snapshot[index])
                }
                inactiveSlots.forEach { slot ->
                    inventory.setItem(slot, PageUiConfig.disabledStorage.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))
                }
            }

            // 上一页按钮
            if (page > 1) {
                set(
                    prevChar,
                    PageUiConfig.previousButton.buildItem(
                        placeholders = mapOf("page" to (page - 1)),
                        fallbackMaterial = org.bukkit.Material.ARROW,
                    ),
                )
                onClick(prevChar) {
                    it.isCancelled = true
                    MenuSupport.persistPageContents(player.uniqueId, page, snapshot, true)
                    WarehouseSessionService.beginTransition(player, page - 1)
                    MenuSupport.reopen(it.clicker) { openSelf(player, page - 1) }
                }
            }
            // 没有上一页时，上一页按钮位置显示背景
            if (page <= 1) {
                set(prevChar, PageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))
                onClick(prevChar) { it.isCancelled = true }
            }

            // 当前页信息
            set(
                PageUiConfig.currentButton.char,
                PageUiConfig.currentButton.buildItem(
                    placeholders = mapOf(
                        "page" to page, "max_pages" to profile.maxPages,
                        "page_size" to MenuSupport.storageCapacity(), "remark" to remark,
                    ),
                    skipBlankKeys = setOf("remark"),
                    fallbackMaterial = org.bukkit.Material.CHEST,
                ),
            )
            onClick(PageUiConfig.currentButton.char) { it.isCancelled = true }

            // 返回主页
            set(PageUiConfig.backButton.char, PageUiConfig.backButton.buildItem(fallbackMaterial = org.bukkit.Material.BOOK))
            onClick(PageUiConfig.backButton.char) {
                it.isCancelled = true
                MenuSupport.persistPageContents(player.uniqueId, page, snapshot, true)
                WarehouseSessionService.beginTransition(player, null)
                MenuSupport.reopen(it.clicker) { WarehouseHomeMenu.openSelf(player) }
            }

            // 关闭菜单
            set(PageUiConfig.closeButton.char, PageUiConfig.closeButton.buildItem(fallbackMaterial = org.bukkit.Material.BARRIER))
            onClick(PageUiConfig.closeButton.char) {
                it.isCancelled = true
                it.clicker.closeInventory()
            }

            // 下一页按钮
            if (page < profile.maxPages && page < PluginConfig.maxPages) {
                set(
                    nextChar,
                    PageUiConfig.nextButton.buildItem(
                        placeholders = mapOf("page" to (page + 1)),
                        fallbackMaterial = org.bukkit.Material.ARROW,
                    ),
                )
                onClick(nextChar) {
                    it.isCancelled = true
                    MenuSupport.persistPageContents(player.uniqueId, page, snapshot, true)
                    WarehouseSessionService.beginTransition(player, page + 1)
                    MenuSupport.reopen(it.clicker) { openSelf(player, page + 1) }
                }
            }
            if (!(page < profile.maxPages && page < PluginConfig.maxPages)) {
                set(nextChar, PageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))
                onClick(nextChar) { it.isCancelled = true }
            }

            onClose { event ->
                val viewer = event.player as? Player ?: return@onClose
                if (WarehouseSessionService.isNavigating(viewer)) return@onClose
                MenuSupport.persistPageContents(viewer.uniqueId, page, snapshot, true)
                WarehouseSessionService.end(viewer)
            }
        }
        WarehouseSessionService.completeOpen(player, page)
    }
}
