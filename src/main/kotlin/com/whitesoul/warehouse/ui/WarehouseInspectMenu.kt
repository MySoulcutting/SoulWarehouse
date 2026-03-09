package com.whitesoul.warehouse.ui

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.service.WarehouseService
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Chest
import taboolib.platform.util.sendLang

object WarehouseInspectMenu {

    fun openHome(viewer: Player, target: OfflinePlayer) {
        if (!WarehouseService.isAvailable()) {
            viewer.sendLang("storage-unavailable", WarehouseService.getLastError() ?: "unknown")
            return
        }
        val targetName = target.name ?: target.uniqueId.toString()
        val profile = WarehouseService.getProfile(target)
        val pageSize = MenuSupport.storageCapacity()
        val pageSlots = InspectHomeUiConfig.pageSlots
        val interactiveSlots = (pageSlots + InspectHomeUiConfig.controlSlots).toSet()
        viewer.openMenu<Chest>(InspectHomeUiConfig.formatTitle(mapOf("player" to targetName, "target" to targetName))) {
            rows(InspectHomeUiConfig.rows)
            handLocked(true)
            onClick(lock = true) {}

            repeat(InspectHomeUiConfig.rows * 9) { slot ->
                if (slot !in interactiveSlots) {
                    set(slot, InspectHomeUiConfig.filler.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))
                }
            }

            pageSlots.forEachIndexed { index, slot ->
                val page = index + 1
                if (WarehouseService.isInspectPageAccessible(profile, page)) {
                    val used = WarehouseService.getPageUsedSlots(target.uniqueId, page, pageSize)
                    val remark = WarehouseService.getPageRemark(target.uniqueId, page)
                    set(
                        slot,
                        InspectHomeUiConfig.pageItem.buildItem(
                            placeholders = mapOf(
                                "page" to page,
                                "used" to used,
                                "page_size" to pageSize,
                                "player" to targetName,
                                "target" to targetName,
                                "remark" to remark,
                            ),
                            skipBlankKeys = setOf("remark"),
                            fallbackMaterial = org.bukkit.Material.ENDER_CHEST,
                        ),
                    ) {
                        isCancelled = true
                        MenuSupport.reopen(clicker) {
                            openPage(viewer, target, page)
                        }
                    }
                } else {
                    set(
                        slot,
                        InspectHomeUiConfig.lockedPageItem.buildItem(
                            placeholders = mapOf("page" to page),
                            fallbackMaterial = org.bukkit.Material.BARRIER,
                        ),
                    ) {
                        isCancelled = true
                        viewer.sendLang("page-locked", page)
                    }
                }
            }

            set(
                InspectHomeUiConfig.profileButton.slot!!,
                InspectHomeUiConfig.profileButton.buildItem(
                    placeholders = mapOf(
                        "player" to targetName,
                        "target" to targetName,
                        "max_pages" to profile.maxPages,
                        "page_size" to pageSize,
                    ),
                    fallbackMaterial = org.bukkit.Material.PAPER,
                ),
            ) {
                isCancelled = true
            }

            set(
                InspectHomeUiConfig.readonlyButton.slot!!,
                InspectHomeUiConfig.readonlyButton.buildItem(
                    placeholders = mapOf("player" to targetName, "target" to targetName),
                    fallbackMaterial = org.bukkit.Material.BOOK,
                ),
            ) {
                isCancelled = true
            }

            set(
                InspectHomeUiConfig.closeButton.slot!!,
                InspectHomeUiConfig.closeButton.buildItem(fallbackMaterial = org.bukkit.Material.BARRIER),
            ) {
                isCancelled = true
                clicker.closeInventory()
            }
        }
    }

    fun openPage(viewer: Player, target: OfflinePlayer, page: Int) {
        if (!WarehouseService.isAvailable()) {
            viewer.sendLang("storage-unavailable", WarehouseService.getLastError() ?: "unknown")
            return
        }
        val targetName = target.name ?: target.uniqueId.toString()
        val profile = WarehouseService.getProfile(target)
        if (!WarehouseService.isInspectPageAccessible(profile, page)) {
            viewer.sendLang("page-locked", page)
            return
        }
        val snapshot = WarehouseService.snapshotPageContents(target.uniqueId, page)
        val remark = WarehouseService.getPageRemark(target.uniqueId, page)
        val activeSlots = InspectPageUiConfig.activeStorageSlots
        val inactiveSlots = InspectPageUiConfig.inactiveStorageSlots
        val backgroundSlots = InspectPageUiConfig.backgroundSlots
        viewer.openMenu<Chest>(InspectPageUiConfig.formatTitle(mapOf("player" to targetName, "target" to targetName, "page" to page))) {
            rows(InspectPageUiConfig.rows)
            handLocked(false)
            // 只读查看页直接全局锁点击，避免遗漏 shift/数字键/双击等复杂交互。
            onClick(lock = true) {}

            onBuild { _, inventory ->
                activeSlots.forEachIndexed { index, slot ->
                    inventory.setItem(slot, snapshot[index])
                }
                inactiveSlots.forEach { slot ->
                    inventory.setItem(slot, InspectPageUiConfig.disabledStorage.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))
                }
                backgroundSlots.forEach { slot ->
                    inventory.setItem(slot, InspectPageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))
                }
            }

            if (page <= 1) {
                set(InspectPageUiConfig.previousButton.slot!!, InspectPageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE)) { isCancelled = true }
            }

            if (!(page < profile.maxPages && page < PluginConfig.maxPages)) {
                set(InspectPageUiConfig.nextButton.slot!!, InspectPageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE)) { isCancelled = true }
            }

            if (page > 1) {
                set(
                    InspectPageUiConfig.previousButton.slot!!,
                    InspectPageUiConfig.previousButton.buildItem(
                        placeholders = mapOf("page" to (page - 1)),
                        fallbackMaterial = org.bukkit.Material.ARROW,
                    ),
                ) {
                    isCancelled = true
                    MenuSupport.reopen(clicker) {
                        openPage(viewer, target, page - 1)
                    }
                }
            }

            set(
                InspectPageUiConfig.readonlyButton.slot!!,
                InspectPageUiConfig.readonlyButton.buildItem(
                    placeholders = mapOf(
                        "player" to targetName,
                        "target" to targetName,
                        "page" to page,
                        "max_pages" to profile.maxPages,
                        "page_size" to MenuSupport.storageCapacity(),
                        "remark" to remark,
                    ),
                    skipBlankKeys = setOf("remark"),
                    fallbackMaterial = org.bukkit.Material.BOOK,
                ),
            ) {
                isCancelled = true
            }

            set(
                InspectPageUiConfig.backButton.slot!!,
                InspectPageUiConfig.backButton.buildItem(
                    placeholders = mapOf("player" to targetName, "target" to targetName),
                    fallbackMaterial = org.bukkit.Material.CHEST,
                ),
            ) {
                isCancelled = true
                MenuSupport.reopen(clicker) {
                    openHome(viewer, target)
                }
            }

            set(
                InspectPageUiConfig.closeButton.slot!!,
                InspectPageUiConfig.closeButton.buildItem(fallbackMaterial = org.bukkit.Material.BARRIER),
            ) {
                isCancelled = true
                clicker.closeInventory()
            }

            if (page < profile.maxPages && page < PluginConfig.maxPages) {
                set(
                    InspectPageUiConfig.nextButton.slot!!,
                    InspectPageUiConfig.nextButton.buildItem(
                        placeholders = mapOf("page" to (page + 1)),
                        fallbackMaterial = org.bukkit.Material.ARROW,
                    ),
                ) {
                    isCancelled = true
                    MenuSupport.reopen(clicker) {
                        openPage(viewer, target, page + 1)
                    }
                }
            }
        }
    }
}
