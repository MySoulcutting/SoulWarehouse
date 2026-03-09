package com.whitesoul.warehouse.storage.entity

import taboolib.expansion.Id
import taboolib.expansion.Length
import java.nio.charset.StandardCharsets
import java.util.UUID

class WarehousePageRemarkEntity {

    @Id
    var id: UUID = UUID.randomUUID()

    var uuid: UUID = UUID.randomUUID()

    var page: Int = 1

    @Length(64)
    var remark: String = ""

    var updatedAt: Long = System.currentTimeMillis()

    companion object {

        fun idOf(uuid: UUID, page: Int): UUID {
            // 备注与页内容分表存储，因此主键需要额外带上 remark 命名空间。
            return UUID.nameUUIDFromBytes("$uuid:remark:$page".toByteArray(StandardCharsets.UTF_8))
        }

        fun of(uuid: UUID, page: Int, remark: String): WarehousePageRemarkEntity {
            return WarehousePageRemarkEntity().apply {
                id = idOf(uuid, page)
                this.uuid = uuid
                this.page = page
                this.remark = remark
                updatedAt = System.currentTimeMillis()
            }
        }
    }
}
