package com.whitesoul.warehouse.storage.entity

import com.whitesoul.warehouse.model.WarehouseProfile
import taboolib.expansion.Id
import taboolib.expansion.Length
import taboolib.expansion.UniqueKey
import java.util.UUID

class WarehouseProfileEntity {

    @Id
    @UniqueKey
    var uuid: UUID = UUID.randomUUID()

    @Length(64)
    var name: String = ""

    var maxPages: Int = 1

    var autoPickup: Boolean = true

    var createdAt: Long = System.currentTimeMillis()

    var updatedAt: Long = System.currentTimeMillis()

    var lastOpenAt: Long = 0L

    fun toProfile(): WarehouseProfile {
        return WarehouseProfile(
            uuid = uuid,
            name = name,
            maxPages = maxPages,
            autoPickup = autoPickup,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastOpenAt = lastOpenAt,
        )
    }

    companion object {

        fun fromProfile(profile: WarehouseProfile): WarehouseProfileEntity {
            return WarehouseProfileEntity().apply {
                uuid = profile.uuid
                name = profile.name
                maxPages = profile.maxPages
                autoPickup = profile.autoPickup
                createdAt = profile.createdAt
                updatedAt = profile.updatedAt
                lastOpenAt = profile.lastOpenAt
            }
        }
    }
}
