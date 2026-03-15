package com.whitesoul.warehouse.ui

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.service.WarehouseService
import org.bukkit.entity.Player
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Chest
import taboolib.platform.util.sendLang

object WarehouseCategoryMenu {

    fun open(player: Player, page: Int) {
        val categories = PluginConfig.categoryNames
        val currentCategory = WarehouseService.getPageCategory(player.uniqueId, page)
        // 菜单项 = "默认分类（清除）" + 所有配置的分类
        val entries = listOf("") + categories
        val categorySlots = CategoryUiConfig.categorySlots
        val fillerChar = CategoryUiConfig.filler.char

        // 行数根据分类数量动态调整，但不超过配置的最大行数
        val neededRows = ((entries.size + categorySlots.size - 1) / maxOf(categorySlots.size / CategoryUiConfig.maxRows, 1))
            .coerceIn(1, CategoryUiConfig.maxRows)
        // 实际使用的布局行
        val layoutLines = CategoryUiConfig.layoutLines.take(neededRows)
        val usableSlots = UiConfigSupport.slotsOf(layoutLines, 'C')

        player.openMenu<Chest>(CategoryUiConfig.formatTitle(mapOf("page" to page))) {
            map(*layoutLines.toTypedArray())
            rows(neededRows)
            handLocked(true)
            onClick(lock = true) {}

            // 填充物
            set(fillerChar, CategoryUiConfig.filler.buildItem(fallbackMaterial = org.bukkit.Material.STAINED_GLASS_PANE))

            // 动态填充分类项
            onBuild { _, inventory ->
                entries.forEachIndexed { index, categoryName ->
                    if (index >= usableSlots.size) return@forEachIndexed
                    val slot = usableSlots[index]
                    val isCurrent = categoryName == currentCategory || (categoryName.isEmpty() && currentCategory.isBlank())

                    val item = if (categoryName.isEmpty()) {
                        // 默认分类
                        val name = if (isCurrent) CategoryUiConfig.defaultCategoryCurrentName else CategoryUiConfig.defaultCategoryItem.name
                        CategoryUiConfig.defaultCategoryItem.copy(name = name).buildItem(
                            fallbackMaterial = org.bukkit.Material.BARRIER,
                        )
                    } else {
                        // 具体分类
                        val name = if (isCurrent) CategoryUiConfig.categoryCurrentName else CategoryUiConfig.categoryItem.name
                        val baseLore = CategoryUiConfig.categoryItem.lore
                        val cat = PluginConfig.categories[categoryName]
                        val patternLines = if (cat != null && cat.lorePatterns.isNotEmpty()) {
                            cat.lorePatterns.map { UiConfigSupport.formatText(CategoryUiConfig.patternLine, mapOf("pattern" to it)) }
                        } else emptyList()
                        val fullLore = UiConfigSupport.formatLore(baseLore, mapOf("category" to categoryName)) + patternLines
                        MenuSupport.item(
                            material = UiConfigSupport.resolveMaterial(CategoryUiConfig.categoryItem.material, org.bukkit.Material.NAME_TAG),
                            name = UiConfigSupport.formatText(name, mapOf("category" to categoryName)),
                            lore = fullLore,
                            amount = CategoryUiConfig.categoryItem.amount,
                            durability = CategoryUiConfig.categoryItem.durability,
                        )
                    }
                    inventory.setItem(slot, item)
                }
            }

            // 点击事件
            usableSlots.forEachIndexed { index, slot ->
                onClick(slot) { event ->
                    event.isCancelled = true
                    if (index >= entries.size) return@onClick
                    val categoryName = entries[index]
                    WarehouseService.updatePageCategory(player.uniqueId, page, categoryName)
                    if (categoryName.isEmpty()) {
                        player.sendLang("category-cleared", page)
                    } else {
                        player.sendLang("category-set", page, categoryName)
                    }
                    MenuSupport.reopen(event.clicker) {
                        WarehouseHomeMenu.openSelf(player)
                    }
                }
            }
        }
    }
}
