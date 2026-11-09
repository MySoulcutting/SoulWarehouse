package com.whitesoul.warehouse.placeholder

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.service.WarehouseService
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * PlaceholderAPI 变量注册入口。
 *
 * TabooLib 6.3 已移除 module-placeholderapi，因此直接对接 PlaceholderAPI 官方 API：
 * 编译期 compileOnly，运行时检测到 PlaceholderAPI 插件才注册，未安装时本功能整体静默跳过。
 *
 * 提供的变量（前缀 soulwarehouse）：
 * - %soulwarehouse_pages%       玩家当前可用的仓库页数
 * - %soulwarehouse_limit_pages% 全局配置的页数上限（config.yml 的 warehouse.max-pages）
 * - %soulwarehouse_max_pages%   同 limit_pages 的别名（仅 PAPI 语境；注意 UI 占位符 {max_pages} 语义不同）
 */
object WarehousePlaceholders {

    @Volatile
    private var registered = false

    /**
     * 服务器存在 PlaceholderAPI 时注册变量，可安全重复调用：
     * onEnable 调用一次，PlaceholderAPI 后启用时由 PluginEnableEvent 兜底再调。
     */
    fun setup() {
        if (registered) return
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return
        if (WarehousePlaceholderExpansion.register()) {
            registered = true
        }
    }

    fun teardown() {
        if (!registered) return
        WarehousePlaceholderExpansion.unregister()
        registered = false
    }
}

/**
 * 独立 object，保证仅在 setup() 确认 PlaceholderAPI 存在后才触发类加载，
 * 未安装 PAPI 的服务器不会因父类缺失而抛 NoClassDefFoundError。
 */
private object WarehousePlaceholderExpansion : PlaceholderExpansion() {

    override fun getIdentifier(): String = "soulwarehouse"

    override fun getAuthor(): String = "White_Souls"

    override fun getVersion(): String =
        Bukkit.getPluginManager().getPlugin("SoulWarehouse")?.pluginMeta?.version ?: "unknown"

    /** 插件内部注册的 expansion，/papi reload 时保留注册，避免变量失效后无人恢复。 */
    override fun persist(): Boolean = true

    /**
     * 只支持在线玩家：离线玩家的档案已随退出驱逐出缓存，
     * 此时返回默认页数会是误导值，宁可返回 null（PAPI 保留原变量文本）。
     */
    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null || !WarehouseService.isAvailable()) return null
        return when (params.lowercase()) {
            "pages" -> WarehouseService.getAccessiblePages(player).toString()
            "limit_pages", "max_pages" -> PluginConfig.maxPages.toString()
            else -> null
        }
    }
}
