package com.whitesoul.warehouse.storage.entity

import com.whitesoul.warehouse.model.WarehousePageData
import com.whitesoul.warehouse.storage.WarehouseSerializer
import com.whitesoul.warehouse.storage.WarehouseSerializationException
import taboolib.expansion.ColumnType
import taboolib.expansion.Id
import taboolib.expansion.Length
import taboolib.expansion.UniqueKey
import taboolib.module.database.ColumnTypePostgreSQL
import taboolib.module.database.ColumnTypeSQL
import taboolib.module.database.ColumnTypeSQLite
import java.nio.charset.StandardCharsets
import java.util.UUID

class WarehousePageEntity {

    @Id
    @UniqueKey
    var pageKey: UUID = UUID.randomUUID()

    var uuid: UUID = UUID.randomUUID()

    var page: Int = 1

    var usedSlots: Int = 0

    @ColumnType(
        sql = ColumnTypeSQL.LONGBLOB,
        sqlite = ColumnTypeSQLite.BLOB,
        postgresql = ColumnTypePostgreSQL.BYTEA,
    )
    var content: ByteArray = byteArrayOf()

    @Length(64)
    var remark: String = ""

    @Length(64)
    var category: String = ""

    var updatedAt: Long = System.currentTimeMillis()

    fun toPageData(): WarehousePageData {
        val contents = try {
            WarehouseSerializer.deserialize(content)
        } catch (ex: WarehouseSerializationException) {
            throw WarehouseSerializationException("Failed to deserialize warehouse page $uuid:$page", ex)
        }
        return WarehousePageData(
            owner = uuid,
            page = page,
            contents = contents,
            updatedAt = updatedAt,
            remark = remark,
            category = category,
        )
    }

    companion object {

        fun idOf(uuid: UUID, page: Int): UUID {
            // 复合主键转稳定 UUID，便于在 PTC Object 里直接按主键 upsert/delete。
            return UUID.nameUUIDFromBytes("$uuid:$page".toByteArray(StandardCharsets.UTF_8))
        }

        fun fromPageData(pageData: WarehousePageData): WarehousePageEntity {
            return WarehousePageEntity().apply {
                pageKey = idOf(pageData.owner, pageData.page)
                uuid = pageData.owner
                page = pageData.page
                usedSlots = pageData.countUsedSlots(WarehousePageData.SLOT_COUNT)
                content = pageData.serializeContents()
                updatedAt = pageData.getUpdatedAt()
                remark = pageData.getRemark()
                category = pageData.getCategory()
            }
        }
    }
}
