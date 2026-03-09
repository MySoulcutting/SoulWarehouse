package com.whitesoul.warehouse.storage.entity

import com.whitesoul.warehouse.model.WarehousePageData
import com.whitesoul.warehouse.storage.WarehouseSerializer
import taboolib.expansion.ColumnType
import taboolib.expansion.Id
import taboolib.module.database.ColumnTypePostgreSQL
import taboolib.module.database.ColumnTypeSQL
import taboolib.module.database.ColumnTypeSQLite
import java.nio.charset.StandardCharsets
import java.util.UUID

class WarehousePageEntity {

    @Id
    var id: UUID = UUID.randomUUID()

    var uuid: UUID = UUID.randomUUID()

    var page: Int = 1

    var usedSlots: Int = 0

    @ColumnType(
        sql = ColumnTypeSQL.LONGBLOB,
        sqlite = ColumnTypeSQLite.BLOB,
        postgresql = ColumnTypePostgreSQL.BYTEA,
    )
    var content: ByteArray = byteArrayOf()

    var updatedAt: Long = System.currentTimeMillis()

    fun toPageData(): WarehousePageData {
        return WarehousePageData(
            owner = uuid,
            page = page,
            contents = WarehouseSerializer.deserialize(content),
            updatedAt = updatedAt,
        )
    }

    companion object {

        fun idOf(uuid: UUID, page: Int): UUID {
            // 复合主键转稳定 UUID，便于在 PTC Object 里直接按主键 upsert/delete。
            return UUID.nameUUIDFromBytes("$uuid:$page".toByteArray(StandardCharsets.UTF_8))
        }

        fun fromPageData(pageData: WarehousePageData): WarehousePageEntity {
            return WarehousePageEntity().apply {
                id = idOf(pageData.owner, pageData.page)
                uuid = pageData.owner
                page = pageData.page
                usedSlots = pageData.countUsedSlots(WarehousePageData.SLOT_COUNT)
                // 通过 serializeContents() 在持有 lock 的情况下序列化，避免异步保存时与主线程并发读写 contents。
                content = pageData.serializeContents()
                updatedAt = pageData.updatedAt
            }
        }
    }
}
