package com.whitesoul.warehouse.model

import com.whitesoul.warehouse.storage.WarehouseSerializer
import com.whitesoul.warehouse.util.cloneOrNull
import com.whitesoul.warehouse.util.isSameStoredItem
import com.whitesoul.warehouse.util.normalizeStoredItem
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class WarehousePageData(
    val owner: UUID,
    val page: Int,
    val contents: Array<ItemStack?> = arrayOfNulls(SLOT_COUNT),
    var remark: String = "",
    var category: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
) {

    /** 所有对 [contents] 数组的读写都必须持有此锁，防止异步保存线程与主线程并发访问。 */
    @Transient
    val lock = Any()

    fun getItem(slot: Int): ItemStack? {
        synchronized(lock) {
            return contents.getOrNull(slot).cloneOrNull()
        }
    }

    fun setItem(slot: Int, itemStack: ItemStack?) {
        synchronized(lock) {
            if (slot !in 0 until SLOT_COUNT) {
                return
            }
            contents[slot] = normalizeStoredItem(itemStack)
            updatedAt = System.currentTimeMillis()
        }
    }

    fun snapshotContents(): Array<ItemStack?> {
        synchronized(lock) {
            // 菜单层拿到的是快照副本，避免直接篡改缓存数组引用。
            return Array(SLOT_COUNT) { index -> contents[index].cloneOrNull() }
        }
    }

    fun replaceContents(newContents: Array<ItemStack?>, size: Int = SLOT_COUNT): Boolean {
        synchronized(lock) {
            var changed = false
            // 只覆盖当前启用的逻辑槽位，未开放槽位保持原样，避免 UI 缩容时误删历史数据。
            repeat(size.coerceIn(0, SLOT_COUNT)) { slot ->
                val normalized = normalizeStoredItem(newContents.getOrNull(slot))
                if (!isSameStoredItem(contents[slot], normalized)) {
                    contents[slot] = normalized
                    changed = true
                }
            }
            if (changed) {
                updatedAt = System.currentTimeMillis()
            }
            return changed
        }
    }

    fun countUsedSlots(activeSize: Int): Int {
        synchronized(lock) {
            return (0 until activeSize.coerceIn(0, SLOT_COUNT)).count { contents[it] != null }
        }
    }

    /** 在持有 [lock] 的情况下序列化 contents，供异步保存线程安全调用。 */
    fun serializeContents(): ByteArray {
        synchronized(lock) {
            return WarehouseSerializer.serialize(contents)
        }
    }

    companion object {
        const val SLOT_COUNT = 45
    }
}
