package com.whitesoul.warehouse.model

import java.util.UUID

data class WarehouseSession(
    val viewer: UUID,
    @field:Volatile var currentPage: Int? = null,
    @field:Volatile var navigating: Boolean = false,
)
