package com.whitesoul.warehouse.storage

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.model.WarehousePageData
import com.whitesoul.warehouse.model.WarehouseProfile
import com.whitesoul.warehouse.storage.entity.WarehousePageEntity
import com.whitesoul.warehouse.storage.entity.WarehouseProfileEntity
import taboolib.expansion.ContainerOperator
import taboolib.expansion.PersistentContainer
import taboolib.expansion.persistentContainer
import taboolib.common.platform.function.warning
import java.util.UUID

class PtcObjectWarehouseRepository : WarehouseRepository {

    private var container: PersistentContainer? = null

    private val currentContainer: PersistentContainer
        get() = container ?: error("SoulWarehouse repository is not initialized.")

    private var profileTableName = ""

    private var pageTableName = ""

    private val profileOperator: ContainerOperator
        get() = currentContainer[profileTableName]

    private val pageOperator: ContainerOperator
        get() = currentContainer[pageTableName]

    override fun initialize() {
        shutdown()
        val tablePrefix = PluginConfig.tablePrefix
        profileTableName = "${tablePrefix}player_profile"
        pageTableName = "${tablePrefix}player_warehouse_page"
        container = persistentContainer(
            type = PluginConfig.databaseSource(),
            flags = PluginConfig.databaseFlags,
            ssl = PluginConfig.databaseSslMode,
        ) {
            new(WarehouseProfileEntity::class.java, profileTableName)
            new(WarehousePageEntity::class.java, pageTableName)
        }
    }

    override fun shutdown() {
        runCatching { container?.close() }.onFailure {
            warning("Failed to close SoulWarehouse database connection: ${it.message}")
        }
        container = null
    }

    override fun loadProfile(uuid: UUID): WarehouseProfile? {
        return profileOperator.findOne(WarehouseProfileEntity::class.java, uuid)?.toProfile()
    }

    override fun saveProfile(profile: WarehouseProfile) {
        saveProfiles(listOf(profile))
    }

    override fun saveProfiles(profiles: Collection<WarehouseProfile>) {
        if (profiles.isEmpty()) {
            return
        }
        profileOperator.upsert(profiles.map { WarehouseProfileEntity.fromProfile(it) })
    }

    override fun loadPage(uuid: UUID, page: Int): WarehousePageData? {
        return pageOperator.findOne(
            WarehousePageEntity::class.java,
            WarehousePageEntity.idOf(uuid, page),
        )?.toPageData()
    }

    override fun savePage(pageData: WarehousePageData) {
        savePages(listOf(pageData))
    }

    override fun savePages(pageDataList: Collection<WarehousePageData>) {
        if (pageDataList.isEmpty()) {
            return
        }
        val (emptyPages, nonEmptyPages) = pageDataList.partition {
            it.countUsedSlots(WarehousePageData.SLOT_COUNT) <= 0 && it.getRemark().isBlank() && it.getCategory().isBlank()
        }
        // 空页（无物品、无备注、无分类）直接删记录，避免数据库里堆积无用行。
        if (nonEmptyPages.isNotEmpty()) {
            pageOperator.upsert(nonEmptyPages.map { WarehousePageEntity.fromPageData(it) })
        }
        emptyPages.forEach { deletePage(it.owner, it.page) }
    }

    override fun deletePage(uuid: UUID, page: Int) {
        val key = WarehousePageEntity().apply {
            pageKey = WarehousePageEntity.idOf(uuid, page)
        }
        pageOperator.deleteByKey(key)
    }
}
