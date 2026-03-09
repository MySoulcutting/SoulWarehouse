package com.whitesoul.warehouse.config

import org.bukkit.ChatColor
import org.bukkit.inventory.ItemStack
import taboolib.common.Inject
import taboolib.expansion.dbFile
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

@Inject
object PluginConfig {

    @Config("config.yml")
    lateinit var config: Configuration

    val databaseType: String
        get() = config.getString("database.type", "mysql")!!.lowercase()

    val databaseHost: String
        get() = config.getString("database.host", "127.0.0.1")!!

    val databasePort: Int
        get() = config.getInt("database.port", 3306)

    val databaseUser: String
        get() = config.getString("database.user", "root")!!

    val databasePassword: String
        get() = config.getString("database.password", "123456")!!

    val databaseName: String
        get() = config.getString("database.database", "minecraft")!!

    val databaseFile: String
        get() = config.getString("database.file", "warehouse.db")!!

    val databaseSection: ConfigurationSection
        get() = config.getConfigurationSection("database") ?: Configuration.empty()

    val tablePrefix: String
        get() = config.getString("database.table-prefix", "sw_")!!
            // 表前缀会直接进入 SQL 标识符，先做白名单过滤避免非法字符。
            .replace(Regex("[^A-Za-z0-9_]"), "")
            .ifBlank { "sw_" }

    val useSsl: Boolean
        get() = config.getBoolean("database.use-ssl", false)

    val databaseSslMode: String?
        get() = config.getString("database.ssl-mode")?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (databaseType) {
                "mysql", "mariadb" -> if (useSsl) "REQUIRED" else "DISABLED"
                else -> null
            }

    val maxPages: Int
        get() = config.getInt("warehouse.max-pages", 5).coerceAtLeast(1)

    val defaultPages: Int
        get() = config.getInt("warehouse.default-pages", 3).coerceIn(1, maxPages)

    val pageSize: Int
        get() = config.getInt("warehouse.page-size", 45).coerceIn(9, 45)

    val allowShulkerBox: Boolean
        get() = config.getBoolean("warehouse.allow-shulker-box", true)

    val blacklist: Set<String>
        get() = config.getStringList("warehouse.blacklist").map { it.uppercase() }.toSet()

    val autoSave: Boolean
        get() = config.getBoolean("save.auto-save", true)

    val autoSaveIntervalSeconds: Long
        get() = config.getLong("save.auto-save-interval", 300L).coerceAtLeast(30L)

    val saveOnClose: Boolean
        get() = config.getBoolean("save.save-on-close", true)

    val saveOnQuit: Boolean
        get() = config.getBoolean("save.save-on-quit", true)

    val autoStoreDrops: Boolean
        get() = config.getBoolean("pickup.auto-store-drops", true)

    data class WarehouseCategory(val name: String, val lorePatterns: List<String>)

    val categories: Map<String, WarehouseCategory>
        get() {
            val section = config.getConfigurationSection("warehouse.categories") ?: return emptyMap()
            return section.getKeys(false).associateWith { name ->
                val patterns = config.getStringList("warehouse.categories.$name")
                WarehouseCategory(name, patterns)
            }
        }

    val categoryNames: List<String>
        get() = categories.keys.toList()

    fun matchCategory(itemStack: ItemStack, category: WarehouseCategory): Boolean {
        val meta = itemStack.itemMeta ?: return false
        val lore = meta.lore ?: return false
        val coloredPatterns = category.lorePatterns.map { ChatColor.translateAlternateColorCodes('&', it) }
        return lore.any { line -> coloredPatterns.any { pattern -> line.contains(pattern) } }
    }

    fun databaseSource(): Any {
        return when (databaseType) {
            // SQLite 走独立文件，其余类型直接把 database 节点交给 TabooLib 容器层解析。
            "sqlite" -> dbFile(databaseFile)
            "mysql", "mariadb", "postgresql", "pgsql", "pg" -> databaseSection
            else -> error("Unsupported database type: $databaseType")
        }
    }

    fun reload() {
        config.reload()
    }
}
