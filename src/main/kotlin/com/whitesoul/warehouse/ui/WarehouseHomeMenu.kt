package com.whitesoul.warehouse.ui

import com.whitesoul.warehouse.service.WarehouseRemarkInputService
import com.whitesoul.warehouse.service.WarehouseService
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Chest
import taboolib.platform.util.sendLang

object WarehouseHomeMenu {

    fun openSelf(player: Player) {
        if (!WarehouseService.isAvailable()) {
            player.sendLang("storage-unavailable", WarehouseService.getLastError() ?: "unknown")
            return
        }
        if (!WarehouseService.isOwnerLoaded(player.uniqueId)) {
            MenuSupport.loadOwner(player, player.uniqueId, player.name) { openSelf(player) }
            return
        }
        val profile = WarehouseService.touchProfile(player)
        val pageSize = MenuSupport.storageCapacity()
        val pageSlots = HomeUiConfig.pageSlots
        val fillerChar = HomeUiConfig.filler.char

        player.openMenu<Chest>(HomeUiConfig.formatTitle()) {
            map(*HomeUiConfig.layoutLines.toTypedArray())
            rows(HomeUiConfig.rows)
            handLocked(true)
            onClick(lock = true) {}

            // 填充物
            set(fillerChar, HomeUiConfig.filler.buildItem(fallbackMaterial = org.bukkit.Material.GRAY_STAINED_GLASS_PANE))

            // 使用说明
            set(HomeUiConfig.helpButton.char, HomeUiConfig.helpButton.buildItem(fallbackMaterial = org.bukkit.Material.BOOK)) {
                isCancelled = true
            }

            // 仓库信息
            set(
                HomeUiConfig.profileButton.char,
                HomeUiConfig.profileButton.buildItem(
                    placeholders = mapOf("max_pages" to profile.maxPages, "page_size" to pageSize),
                    fallbackMaterial = org.bukkit.Material.PAPER,
                ),
            ) {
                isCancelled = true
            }

            // 刷新界面
            set(HomeUiConfig.refreshButton.char, HomeUiConfig.refreshButton.buildItem(fallbackMaterial = org.bukkit.Material.COMPASS)) {
                isCancelled = true
                MenuSupport.reopen(clicker) { openSelf(player) }
            }

            // 关闭菜单
            set(HomeUiConfig.closeButton.char, HomeUiConfig.closeButton.buildItem(fallbackMaterial = org.bukkit.Material.BARRIER)) {
                isCancelled = true
                clicker.closeInventory()
            }

            // 自动拾取入仓开关
            val autoPickup = profile.autoPickup
            val statusText = if (autoPickup) "&a已开启" else "&c已关闭"
            set(
                HomeUiConfig.autoPickupButton.char,
                HomeUiConfig.autoPickupButton.buildItem(
                    placeholders = mapOf("status" to statusText),
                    fallbackMaterial = org.bukkit.Material.HOPPER,
                ),
            ) {
                isCancelled = true
                val newState = WarehouseService.toggleAutoPickup(player)
                if (newState) player.sendLang("auto-pickup-enabled") else player.sendLang("auto-pickup-disabled")
                MenuSupport.reopen(clicker) { openSelf(player) }
            }

            // 仓库页槽位 — 通过 onBuild 动态填充（因为每个 P 槽位内容不同）
            onBuild { _, inventory ->
                pageSlots.forEachIndexed { index, slot ->
                    val page = index + 1
                    if (page <= profile.maxPages) {
                        val used = WarehouseService.getPageUsedSlots(player.uniqueId, page, pageSize)
                        val remark = WarehouseService.getPageRemark(player.uniqueId, page)
                        val category = WarehouseService.getPageCategory(player.uniqueId, page).ifBlank { "默认" }
                        inventory.setItem(
                            slot,
                            HomeUiConfig.pageItem.buildItem(
                                placeholders = mapOf(
                                    "page" to page, "used" to used, "page_size" to pageSize,
                                    "max_pages" to profile.maxPages, "remark" to remark, "category" to category,
                                ),
                                skipBlankKeys = setOf("remark"),
                                fallbackMaterial = org.bukkit.Material.CHEST,
                            ),
                        )
                    } else {
                        inventory.setItem(
                            slot,
                            HomeUiConfig.lockedPageItem.buildItem(
                                placeholders = mapOf("page" to page),
                                fallbackMaterial = org.bukkit.Material.BARRIER,
                            ),
                        )
                    }
                }
            }

            // 页面槽位点击事件 — 通过 slot index 绑定
            pageSlots.forEachIndexed { index, slot ->
                val page = index + 1
                onClick(slot) { event ->
                    event.isCancelled = true
                    if (page > profile.maxPages) {
                        player.sendLang("page-locked", page)
                        return@onClick
                    }
                    val click = event.clickEventOrNull()?.click
                    when {
                        click == ClickType.SHIFT_RIGHT -> {
                            MenuSupport.reopen(event.clicker) { WarehouseCategoryMenu.open(player, page) }
                        }
                        click?.isRightClick == true -> {
                            WarehouseRemarkInputService.begin(player, page)
                        }
                        else -> {
                            MenuSupport.reopen(event.clicker) { WarehousePageMenu.openSelf(player, page) }
                        }
                    }
                }
            }
        }
    }
}
