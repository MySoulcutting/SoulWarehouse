package com.whitesoul.warehouse.model

import java.util.UUID

data class WarehouseProfile(
    val uuid: UUID,
    var name: String,
    var maxPages: Int,
    var autoPickup: Boolean = true,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var lastOpenAt: Long = 0L,
)
