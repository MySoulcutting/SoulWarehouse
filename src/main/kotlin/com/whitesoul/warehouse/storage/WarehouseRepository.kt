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

    fun loadPageUsage(uuid: UUID, page: Int): Int?

    fun loadPageRemark(uuid: UUID, page: Int): String?

    fun savePage(pageData: WarehousePageData)

    fun savePages(pageDataList: Collection<WarehousePageData>)

    fun savePageRemark(uuid: UUID, page: Int, remark: String)

    fun deletePage(uuid: UUID, page: Int)

    fun deletePageRemark(uuid: UUID, page: Int)
}