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
        val profile = WarehouseService.touchProfile(player)
        val pageSize = MenuSupport.storageCapacity()
        val pageSlots = HomeUiConfig.pageSlots
        val interactiveSlots = (pageSlots + HomeUiConfig.controlSlots).toSet()
        player.openMenu<Chest>(HomeUiConfig.formatTitle()) {
            rows(HomeUiConfig.rows)
            handLocked(true)
            onClick(lock = true) {}

            repeat(HomeUiConfig.rows * 9) { slot ->
                if (slot !in interactiveSlots) {
                    set(slot, HomeUiConfig.filler.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))
                }
            }

            pageSlots.forEachIndexed { index, slot ->
                val page = index + 1
                if (page <= profile.maxPages) {
                    val used = WarehouseService.getPageUsedSlots(player.uniqueId, page, pageSize)
                    val remark = WarehouseService.getPageRemark(player.uniqueId, page)
                    val category = WarehouseService.getPageCategory(player.uniqueId, page).ifBlank { "默认" }
                    set(
                        slot,
                        HomeUiConfig.pageItem.buildItem(
                            placeholders = mapOf(
                                "page" to page,
                                "used" to used,
                                "page_size" to pageSize,
                                "max_pages" to profile.maxPages,
                                "remark" to remark,
                                "category" to category,
                            ),
                            skipBlankKeys = setOf("remark"),
                            fallbackMaterial = org.bukkit.Material.CHEST,
                        ),
                    ) {
                        isCancelled = true
                        val click = clickEventOrNull()?.click
                        when {
                            // Shift+右键打开分类选择菜单
                            click == ClickType.SHIFT_RIGHT -> {
                                MenuSupport.reopen(clicker) {
                                    WarehouseCategoryMenu.open(player, page)
                                }
                            }
                            // 右键进入聊天备注编辑
                            click?.isRightClick == true -> {
                                WarehouseRemarkInputService.begin(player, page)
                            }
                            // 左键进入仓库页
                            else -> {
                                MenuSupport.reopen(clicker) {
                                    WarehousePageMenu.openSelf(player, page)
                                }
                            }
                        }
                    }
                } else {
                    set(
                        slot,
                        HomeUiConfig.lockedPageItem.buildItem(
                            placeholders = mapOf("page" to page),
                            fallbackMaterial = org.bukkit.Material.BARRIER,
                        ),
                    ) {
                        isCancelled = true
                        player.sendLang("page-locked", page)
                    }
                }
            }

            set(
                HomeUiConfig.helpButton.slot!!,
                HomeUiConfig.helpButton.buildItem(fallbackMaterial = org.bukkit.Material.BOOK),
            ) {
                isCancelled = true
            }

            set(
                HomeUiConfig.profileButton.slot!!,
                HomeUiConfig.profileButton.buildItem(
                    placeholders = mapOf(
                        "max_pages" to profile.maxPages,
                        "page_size" to pageSize,
                    ),
                    fallbackMaterial = org.bukkit.Material.PAPER,
                ),
            ) {
                isCancelled = true
            }

            set(
                HomeUiConfig.refreshButton.slot!!,
                HomeUiConfig.refreshButton.buildItem(fallbackMaterial = org.bukkit.Material.COMPASS),
            ) {
                isCancelled = true
                MenuSupport.reopen(clicker) {
                    openSelf(player)
                }
            }

            set(
                HomeUiConfig.closeButton.slot!!,
                HomeUiConfig.closeButton.buildItem(fallbackMaterial = org.bukkit.Material.BARRIER),
            ) {
                isCancelled = true
                clicker.closeInventory()
            }
        }
    }
}
