package com.whitesoul.warehouse.model

import com.whitesoul.warehouse.util.cloneOrNull
import com.whitesoul.warehouse.util.normalizeStoredItem
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class WarehousePageData(
    val owner: UUID,
    val page: Int,
    val contents: Array<ItemStack?> = arrayOfNulls(SLOT_COUNT),
    var updatedAt: Long = System.currentTimeMillis(),
) {

    fun getItem(slot: Int): ItemStack? {
        return contents.getOrNull(slot).cloneOrNull()
    }

    fun setItem(slot: Int, itemStack: ItemStack?) {
        if (slot !in 0 until SLOT_COUNT) {
            return
        }
        contents[slot] = normalizeStoredItem(itemStack)
        updatedAt = System.currentTimeMillis()
    }

    fun snapshotContents(): Array<ItemStack?> {
        // 菜单层拿到的是快照副本，避免直接篡改缓存数组引用。
        return Array(SLOT_COUNT) { index -> contents[index].cloneOrNull() }
    }

    fun replaceContents(newContents: Array<ItemStack?>, size: Int = SLOT_COUNT): Boolean {
        var changed = false
        // 只覆盖当前启用的逻辑槽位，未开放槽位保持原样，避免 UI 缩容时误删历史数据。
        repeat(size.coerceIn(0, SLOT_COUNT)) { slot ->
            val normalized = normalizeStoredItem(newContents.getOrNull(slot))
            if (contents[slot] != normalized) {
                contents[slot] = normalized
                changed = true
            }
        }
        if (changed) {
            updatedAt = System.currentTimeMillis()
        }
        return changed
    }

    fun countUsedSlots(activeSize: Int): Int {
        return (0 until activeSize.coerceIn(0, SLOT_COUNT)).count { contents[it] != null }
    }

    companion object {
        const val SLOT_COUNT = 45
    }
}
