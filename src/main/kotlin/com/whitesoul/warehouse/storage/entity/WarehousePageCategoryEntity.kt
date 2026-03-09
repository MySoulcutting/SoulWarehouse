package com.whitesoul.warehouse.storage.entity

import taboolib.expansion.Id
import taboolib.expansion.Length
import java.nio.charset.StandardCharsets
import java.util.UUID

class WarehousePageCategoryEntity {

    @Id
    var id: UUID = UUID.randomUUID()

    var uuid: UUID = UUID.randomUUID()

    var page: Int = 1

    @Length(64)
    var category: String = ""

    var updatedAt: Long = System.currentTimeMillis()

    companion object {

        fun idOf(uuid: UUID, page: Int): UUID {
            return UUID.nameUUIDFromBytes("$uuid:category:$page".toByteArray(StandardCharsets.UTF_8))
        }

        fun of(uuid: UUID, page: Int, category: String): WarehousePageCategoryEntity {
            return WarehousePageCategoryEntity().apply {
                id = idOf(uuid, page)
                this.uuid = uuid
                this.page = page
                this.category = category
                updatedAt = System.currentTimeMillis()
            }
        }
    }
}
