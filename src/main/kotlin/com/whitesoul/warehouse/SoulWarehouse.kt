package com.whitesoul.warehouse

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.config.PluginLang
import com.whitesoul.warehouse.service.WarehouseSaveService
import com.whitesoul.warehouse.service.WarehouseService
import com.whitesoul.warehouse.ui.WarehouseUiConfigs
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info

object SoulWarehouse : Plugin() {

    override fun onEnable() {
        // 启动顺序固定为“配置 -> UI -> 语言 -> 存储 -> 定时任务”，避免服务读取到旧配置。
        PluginConfig.reload()
        WarehouseUiConfigs.reload()
        PluginLang.reload()
        WarehouseService.initialize()
        WarehouseSaveService.restart()
        info("SoulWarehouse has been enabled.")
    }

    override fun onDisable() {
        // 先停定时任务，再同步落盘缓存，避免停服阶段重复写入。
        WarehouseSaveService.stop()
        WarehouseService.shutdown()
        info("SoulWarehouse has been disabled.")
    }
}
