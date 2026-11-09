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
        if (!WarehouseService.isOwnerLoaded(target.uniqueId)) {
            MenuSupport.loadOwner(viewer, target.uniqueId, targetName) { openHome(viewer, target) }
            return
        }
        val profile = WarehouseService.getProfile(target)
        val pageSize = MenuSupport.storageCapacity()
        val pageSlots = InspectHomeUiConfig.pageSlots
        val fillerChar = InspectHomeUiConfig.filler.char

        viewer.openMenu<Chest>(InspectHomeUiConfig.formatTitle(mapOf("player" to targetName, "target" to targetName))) {
            map(*InspectHomeUiConfig.layoutLines.toTypedArray())
            rows(InspectHomeUiConfig.rows)
            handLocked(true)
            onClick(lock = true) {}

            // 填充物
            set(fillerChar, InspectHomeUiConfig.filler.buildItem(fallbackMaterial = org.bukkit.Material.GRAY_STAINED_GLASS_PANE))

            // 仓库信息
            set(
                InspectHomeUiConfig.profileButton.char,
                InspectHomeUiConfig.profileButton.buildItem(
                    placeholders = mapOf(
                        "player" to targetName, "target" to targetName,
                        "max_pages" to profile.maxPages, "page_size" to pageSize,
                    ),
                    fallbackMaterial = org.bukkit.Material.PAPER,
                ),
            )

            // 只读模式
            set(
                InspectHomeUiConfig.readonlyButton.char,
                InspectHomeUiConfig.readonlyButton.buildItem(
                    placeholders = mapOf("player" to targetName, "target" to targetName),
                    fallbackMaterial = org.bukkit.Material.BOOK,
                ),
            )

            // 关闭菜单
            set(InspectHomeUiConfig.closeButton.char, InspectHomeUiConfig.closeButton.buildItem(fallbackMaterial = org.bukkit.Material.BARRIER)) {
                isCancelled = true
                clicker.closeInventory()
            }

            // 仓库页槽位 — 通过 onBuild 动态填充
            onBuild { _, inventory ->
                pageSlots.forEachIndexed { index, slot ->
                    val page = index + 1
                    if (WarehouseService.isInspectPageAccessible(profile, page)) {
                        val used = WarehouseService.getPageUsedSlots(target.uniqueId, page, pageSize)
                        val remark = WarehouseService.getPageRemark(target.uniqueId, page)
                        val category = WarehouseService.getPageCategory(target.uniqueId, page).ifBlank { "默认" }
                        inventory.setItem(
                            slot,
                            InspectHomeUiConfig.pageItem.buildItem(
                                placeholders = mapOf(
                                    "page" to page, "used" to used, "page_size" to pageSize,
                                    "player" to targetName, "target" to targetName,
                                    "remark" to remark, "category" to category,
                                ),
                                skipBlankKeys = setOf("remark"),
                                fallbackMaterial = org.bukkit.Material.ENDER_CHEST,
                            ),
                        )
                    } else {
                        inventory.setItem(
                            slot,
                            InspectHomeUiConfig.lockedPageItem.buildItem(
                                placeholders = mapOf("page" to page),
                                fallbackMaterial = org.bukkit.Material.BARRIER,
                            ),
                        )
                    }
                }
            }

            // 页面槽位点击事件
            pageSlots.forEachIndexed { index, slot ->
                val page = index + 1
                onClick(slot) { event ->
                    event.isCancelled = true
                    if (!WarehouseService.isInspectPageAccessible(profile, page)) {
                        viewer.sendLang("page-locked", page)
                        return@onClick
                    }
                    MenuSupport.reopen(event.clicker) { openPage(viewer, target, page) }
                }
            }
        }
    }

    fun openPage(viewer: Player, target: OfflinePlayer, page: Int) {
        if (!WarehouseService.isAvailable()) {
            viewer.sendLang("storage-unavailable", WarehouseService.getLastError() ?: "unknown")
            return
        }
        val targetName = target.name ?: target.uniqueId.toString()
        if (!WarehouseService.isOwnerLoaded(target.uniqueId)) {
            MenuSupport.loadOwner(viewer, target.uniqueId, targetName) { openPage(viewer, target, page) }
            return
        }
        val profile = WarehouseService.getProfile(target)
        if (!WarehouseService.isInspectPageAccessible(profile, page)) {
            viewer.sendLang("page-locked", page)
            return
        }
        val snapshot = WarehouseService.snapshotPageContents(target.uniqueId, page)
        val remark = WarehouseService.getPageRemark(target.uniqueId, page)
        val activeSlots = InspectPageUiConfig.activeStorageSlots
        val inactiveSlots = InspectPageUiConfig.inactiveStorageSlots

        val bgChar = InspectPageUiConfig.background.char
        val prevChar = InspectPageUiConfig.previousButton.char
        val nextChar = InspectPageUiConfig.nextButton.char

        viewer.openMenu<Chest>(InspectPageUiConfig.formatTitle(mapOf("player" to targetName, "target" to targetName, "page" to page))) {
            map(*InspectPageUiConfig.layoutLines.toTypedArray())
            rows(InspectPageUiConfig.rows)
            handLocked(true)
            onClick(lock = true) {}

            // 背景填充
            set(bgChar, InspectPageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.GRAY_STAINED_GLASS_PANE))

            // 存储区物品通过 onBuild 填充
            onBuild { _, inventory ->
                activeSlots.forEachIndexed { index, slot ->
                    inventory.setItem(slot, snapshot[index])
                }
                inactiveSlots.forEach { slot ->
                    inventory.setItem(slot, InspectPageUiConfig.disabledStorage.buildItem(fallbackMaterial = org.bukkit.Material.LIGHT_GRAY_STAINED_GLASS_PANE))
                }
            }

            // 上一页
            if (page > 1) {
                set(prevChar, InspectPageUiConfig.previousButton.buildItem(
                    placeholders = mapOf("page" to (page - 1)), fallbackMaterial = org.bukkit.Material.ARROW,
                ))
                onClick(prevChar) {
                    it.isCancelled = true
                    MenuSupport.reopen(it.clicker) { openPage(viewer, target, page - 1) }
                }
            } else {
                set(prevChar, InspectPageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.GRAY_STAINED_GLASS_PANE))
            }

            // 只读模式
            set(
                InspectPageUiConfig.readonlyButton.char,
                InspectPageUiConfig.readonlyButton.buildItem(
                    placeholders = mapOf(
                        "player" to targetName, "target" to targetName,
                        "page" to page, "max_pages" to profile.maxPages,
                        "page_size" to MenuSupport.storageCapacity(), "remark" to remark,
                    ),
                    skipBlankKeys = setOf("remark"),
                    fallbackMaterial = org.bukkit.Material.BOOK,
                ),
            )

            // 返回主页
            set(InspectPageUiConfig.backButton.char, InspectPageUiConfig.backButton.buildItem(
                placeholders = mapOf("player" to targetName, "target" to targetName),
                fallbackMaterial = org.bukkit.Material.CHEST,
            ))
            onClick(InspectPageUiConfig.backButton.char) {
                it.isCancelled = true
                MenuSupport.reopen(it.clicker) { openHome(viewer, target) }
            }

            // 关闭菜单
            set(InspectPageUiConfig.closeButton.char, InspectPageUiConfig.closeButton.buildItem(fallbackMaterial = org.bukkit.Material.BARRIER))
            onClick(InspectPageUiConfig.closeButton.char) {
                it.isCancelled = true
                it.clicker.closeInventory()
            }

            // 下一页
            if (page < profile.maxPages && page < PluginConfig.maxPages) {
                set(nextChar, InspectPageUiConfig.nextButton.buildItem(
                    placeholders = mapOf("page" to (page + 1)), fallbackMaterial = org.bukkit.Material.ARROW,
                ))
                onClick(nextChar) {
                    it.isCancelled = true
                    MenuSupport.reopen(it.clicker) { openPage(viewer, target, page + 1) }
                }
            } else {
                set(nextChar, InspectPageUiConfig.background.buildItem(fallbackMaterial = org.bukkit.Material.GRAY_STAINED_GLASS_PANE))
            }
        }
    }
}
