package com.whitesoul.warehouse.model

import java.util.UUID

data class WarehouseSession(
    val viewer: UUID,
    var currentPage: Int? = null,
    var navigating: Boolean = false,
)
