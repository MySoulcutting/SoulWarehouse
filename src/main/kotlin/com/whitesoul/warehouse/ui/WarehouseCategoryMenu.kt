package com.whitesoul.warehouse.ui

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.service.WarehouseService
import org.bukkit.ChatColor
import org.bukkit.Material
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
        val rows = ((entries.size + 8) / 9).coerceIn(1, 6)
        player.openMenu<Chest>(color("&8设置第 $page 页分类")) {
            rows(rows)
            handLocked(true)
            onClick(lock = true) {}

            entries.forEachIndexed { index, categoryName ->
                if (index >= rows * 9) return@forEachIndexed
                val isCurrent = categoryName == currentCategory || (categoryName.isEmpty() && currentCategory.isBlank())
                val item = if (categoryName.isEmpty()) {
                    MenuSupport.item(
                        material = Material.BARRIER,
                        name = color(if (isCurrent) "&a✔ 默认分类（当前）" else "&e默认分类"),
                        lore = listOf(color("&7清除分类限制，允许任何物品自动入仓")),
                    )
                } else {
                    MenuSupport.item(
                        material = Material.NAME_TAG,
                        name = color(if (isCurrent) "&a✔ $categoryName（当前）" else "&e$categoryName"),
                        lore = buildList {
                            add(color("&7自动入仓时仅接受匹配此分类的物品"))
                            val cat = PluginConfig.categories[categoryName]
                            if (cat != null && cat.lorePatterns.isNotEmpty()) {
                                add(color("&7匹配规则："))
                                cat.lorePatterns.forEach { add(color("  &8- &f$it")) }
                            }
                        },
                    )
                }
                set(index, item) {
                    isCancelled = true
                    WarehouseService.updatePageCategory(player.uniqueId, page, categoryName)
                    if (categoryName.isEmpty()) {
                        player.sendLang("category-cleared", page)
                    } else {
                        player.sendLang("category-set", page, categoryName)
                    }
                    MenuSupport.reopen(clicker) {
                        WarehouseHomeMenu.openSelf(player)
                    }
                }
            }
        }
    }

    private fun color(text: String): String {
        return ChatColor.translateAlternateColorCodes('&', text)
    }
}
