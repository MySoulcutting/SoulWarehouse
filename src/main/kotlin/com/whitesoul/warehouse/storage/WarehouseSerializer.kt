package com.whitesoul.warehouse.storage

import com.whitesoul.warehouse.model.WarehousePageData
import org.bukkit.inventory.ItemStack

class WarehouseSerializationException(message: String, cause: Throwable) : IllegalStateException(message, cause)

object WarehouseSerializer {

    fun serialize(contents: Array<ItemStack?>): ByteArray {
        return ItemStack.serializeItemsAsBytes(contents)
    }

    fun deserialize(content: ByteArray?): Array<ItemStack?> {
        if (content == null || content.isEmpty()) {
            return arrayOfNulls(WarehousePageData.SLOT_COUNT)
        }
        return try {
            val items = ItemStack.deserializeItemsFromBytes(content)
            Array<ItemStack?>(WarehousePageData.SLOT_COUNT) { index ->
                items.getOrNull(index)?.takeUnless { it.isEmpty }
            }
        } catch (ex: Exception) {
            throw WarehouseSerializationException(
                "Failed to deserialize warehouse page data (${content.size} bytes)",
                ex,
            )
        }
    }
}
