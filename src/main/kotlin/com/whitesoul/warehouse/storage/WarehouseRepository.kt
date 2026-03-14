package com.whitesoul.warehouse.storage

import com.whitesoul.warehouse.model.WarehousePageData
import com.whitesoul.warehouse.model.WarehouseProfile
import java.util.UUID

interface WarehouseRepository {

    fun initialize()

    fun shutdown()

    fun loadProfile(uuid: UUID): WarehouseProfile?

    fun saveProfile(profile: WarehouseProfile)

    fun saveProfiles(profiles: Collection<WarehouseProfile>)

    fun loadPage(uuid: UUID, page: Int): WarehousePageData?

    fun savePage(pageData: WarehousePageData)

    fun savePages(pageDataList: Collection<WarehousePageData>)

    fun deletePage(uuid: UUID, page: Int)
}
