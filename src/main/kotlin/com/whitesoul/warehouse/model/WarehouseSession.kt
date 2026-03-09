package com.whitesoul.warehouse.model

import java.util.UUID

data class WarehouseSession(
    val viewer: UUID,
    @Volatile var currentPage: Int? = null,
    @Volatile var navigating: Boolean = false,
)
