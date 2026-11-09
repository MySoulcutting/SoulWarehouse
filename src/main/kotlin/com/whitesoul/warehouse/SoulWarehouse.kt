package com.whitesoul.warehouse

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.config.PluginLang
import com.whitesoul.warehouse.placeholder.WarehousePlaceholders
import com.whitesoul.warehouse.service.WarehouseSaveService
import com.whitesoul.warehouse.service.WarehouseService
import com.whitesoul.warehouse.ui.WarehouseUiConfigs
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.submitAsync

object SoulWarehouse : Plugin() {

    @Volatile
    private var disabling = false

    override fun onEnable() {
        disabling = false
        // PlaceholderAPI 存在时注册变量（softdepend 已保证其先启用；后启用场景由监听器兜底）。
        WarehousePlaceholders.setup()
        // 启动顺序固定为“配置 -> UI -> 语言 -> 存储 -> 定时任务”，避免服务读取到旧配置。
        PluginConfig.reload()
        WarehouseUiConfigs.reload()
        PluginLang.reload()
        submitAsync {
            WarehouseService.initialize()
            if (!disabling && WarehouseService.isAvailable()) {
                WarehouseSaveService.restart()
                info("SoulWarehouse has been enabled.")
            }
        }
    }

    override fun onDisable() {
        disabling = true
        WarehousePlaceholders.teardown()
        // 先停定时任务，再同步落盘缓存，避免停服阶段重复写入。
        WarehouseSaveService.stop()
        WarehouseService.shutdown()
        info("SoulWarehouse has been disabled.")
    }
}
