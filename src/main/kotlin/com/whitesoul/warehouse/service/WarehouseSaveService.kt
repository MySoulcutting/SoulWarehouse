package com.whitesoul.warehouse.service

import com.whitesoul.warehouse.config.PluginConfig
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.service.PlatformExecutor

object WarehouseSaveService {

    private var task: PlatformExecutor.PlatformTask? = null

    @Synchronized
    fun restart() {
        stop()
        if (!PluginConfig.autoSave) {
            return
        }
        val interval = PluginConfig.autoSaveIntervalSeconds * 20L
        // 自动保存只处理脏数据，避免菜单交互期间每次改动都直接打到数据库。
        task = submitAsync(delay = interval, period = interval) {
            WarehouseService.saveDirtyPages()
            WarehouseService.saveDirtyProfiles()
        }
    }

    @Synchronized
    fun stop() {
        task?.cancel()
        task = null
    }
}
