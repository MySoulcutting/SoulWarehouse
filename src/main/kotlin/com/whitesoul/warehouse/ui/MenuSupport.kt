package com.whitesoul.warehouse.ui

import com.whitesoul.warehouse.service.WarehouseService
import com.whitesoul.warehouse.util.DebugLogger
import com.whitesoul.warehouse.util.cloneOrNull
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import java.util.UUID

object MenuSupport {

    @Suppress("DEPRECATION")
    fun item(
        material: Material,
        name: String,
        lore: List<String> = emptyList(),
        amount: Int = 1,
        durability: Short = 0,
    ): ItemStack {
        val item = ItemStack(material, amount, durability)
        val meta = item.itemMeta
        if (meta != null) {
            meta.displayName = color(name)
            meta.lore = lore.map(::color)
            item.itemMeta = meta
        }
        return item
    }

    fun storageCapacity(): Int {
        return PageUiConfig.storageCapacity
    }

    fun persistPageContents(owner: UUID, page: Int, contents: Array<ItemStack?>, save: Boolean) {
        val changed = WarehouseService.updatePageContents(owner, page, contents, PageUiConfig.storageCapacity)
        DebugLogger.log { "persistPageContents owner=$owner, page=$page, changed=$changed, save=$save" }
        if (save) {
            submitAsync { WarehouseService.savePage(owner, page) }
        }
    }

    fun firstStorageSlot(inventory: org.bukkit.inventory.Inventory, itemStack: ItemStack, storageSlots: List<Int>): Int {
        if (!WarehouseService.isItemAllowed(itemStack)) {
            return -1
        }
        return mergeStorageSlots(inventory, itemStack, storageSlots).firstOrNull() ?: -1
    }

    fun mergeStorageSlots(inventory: org.bukkit.inventory.Inventory, itemStack: ItemStack, storageSlots: List<Int>): List<Int> {
        // StorableChest 会按返回顺序尝试放入，先合并满已有堆叠再用空位，体验更接近原版背包。
        val filled = storageSlots.filter { slot ->
            val current = inventory.getItem(slot) ?: return@filter false
            current.isSimilar(itemStack) && current.amount < current.maxStackSize
        }
        val empty = storageSlots.filter { inventory.getItem(it).cloneOrNull() == null }
        return filled + empty
    }

    fun reopen(player: Player, block: () -> Unit) {
        player.closeInventory()
        submit(delay = 1L) {
            // 菜单切换放到下一 tick，避免同 tick 关闭/打开容器导致的客户端异常。
            if (player.isOnline) {
                block()
            }
        }
    }

    private fun color(text: String): String {
        return ChatColor.translateAlternateColorCodes('&', text)
    }
}
