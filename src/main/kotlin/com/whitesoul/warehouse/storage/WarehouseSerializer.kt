package com.whitesoul.warehouse.storage

import com.whitesoul.warehouse.model.WarehousePageData
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import taboolib.common.platform.function.warning
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object WarehouseSerializer {

    fun serialize(contents: Array<ItemStack?>): ByteArray {
        ByteArrayOutputStream().use { outputStream ->
            GZIPOutputStream(outputStream).use { gzipOutputStream ->
                BukkitObjectOutputStream(gzipOutputStream).use { dataOutput ->
                    // 先写数组长度，便于未来容量变化时仍能做兼容读取。
                    dataOutput.writeInt(contents.size)
                    contents.forEach { dataOutput.writeObject(it) }
                }
            }
            return outputStream.toByteArray()
        }
    }

    fun deserialize(content: ByteArray?): Array<ItemStack?> {
        if (content == null || content.isEmpty()) {
            return arrayOfNulls(WarehousePageData.SLOT_COUNT)
        }
        return runCatching {
            ByteArrayInputStream(content).use { inputStream ->
                GZIPInputStream(inputStream).use { gzipInputStream ->
                    BukkitObjectInputStream(gzipInputStream).use { dataInput ->
                        val size = dataInput.readInt()
                        val result = arrayOfNulls<ItemStack>(WarehousePageData.SLOT_COUNT)
                        repeat(size.coerceAtMost(WarehousePageData.SLOT_COUNT)) { index ->
                            result[index] = dataInput.readObject() as? ItemStack
                        }
                        // 旧数据比当前容量更大时把剩余对象读掉，避免流位置错乱。
                        repeat((size - WarehousePageData.SLOT_COUNT).coerceAtLeast(0)) {
                            dataInput.readObject()
                        }
                        result
                    }
                }
            }
        }.getOrElse {
            warning("Failed to deserialize warehouse page data (${content.size} bytes): ${it.message}")
            it.printStackTrace()
            arrayOfNulls(WarehousePageData.SLOT_COUNT)
        }
    }
}
