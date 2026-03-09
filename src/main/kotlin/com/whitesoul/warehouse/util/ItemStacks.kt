package com.whitesoul.warehouse.util

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

fun normalizeStoredItem(itemStack: ItemStack?): ItemStack? {
    if (itemStack == null || itemStack.type == Material.AIR || itemStack.amount <= 0) {
        return null
    }
    return itemStack.clone()
}

fun ItemStack?.cloneOrNull(): ItemStack? {
    return normalizeStoredItem(this)
}

fun isSameStoredItem(first: ItemStack?, second: ItemStack?): Boolean {
    val left = normalizeStoredItem(first)
    val right = normalizeStoredItem(second)
    if (left == null || right == null) {
        return left == null && right == null
    }
    return left.amount == right.amount && left.isSimilar(right)
}
