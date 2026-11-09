package com.whitesoul.warehouse.config

import org.bukkit.ChatColor
import org.bukkit.Sound
import org.bukkit.inventory.ItemStack
import taboolib.common.Inject
import taboolib.expansion.dbFile
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import taboolib.common.platform.function.warning

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
        get() = config.getString("database.password", "")!!

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
        get() = when (databaseType) {
            "mysql", "mariadb" -> configuredSslMode ?: if (useSsl) "REQUIRED" else "DISABLED"
            else -> null
        }

    val databaseFlags: List<String>
        get() = when (databaseType) {
            "postgresql", "pgsql", "pg" -> listOf("sslmode=${postgresSslMode()}")
            else -> emptyList()
        }

    private val configuredSslMode: String?
        get() = config.getString("database.ssl-mode")?.trim()?.takeIf { it.isNotEmpty() }

    private fun postgresSslMode(): String {
        val configured = configuredSslMode ?: return if (useSsl) "require" else "disable"
        return when (configured.lowercase().replace('_', '-')) {
            "disabled", "disable" -> "disable"
            "allow" -> "allow"
            "preferred", "prefer" -> "prefer"
            "required", "require" -> "require"
            "verify-ca" -> "verify-ca"
            "verify-identity", "verify-full" -> "verify-full"
            else -> error("Unsupported PostgreSQL ssl-mode: $configured")
        }
    }

    val maxPages: Int
        get() = config.getInt("warehouse.max-pages", 5).coerceAtLeast(1)

    val defaultPages: Int
        get() = config.getInt("warehouse.default-pages", 3).coerceIn(1, maxPages)

    val pageSize: Int
        get() = config.getInt("warehouse.page-size", 45).coerceIn(9, 45)

    val allowShulkerBox: Boolean
        get() = config.getBoolean("warehouse.allow-shulker-box", true)

    data class SoundConfig(val sound: Sound, val volume: Float, val pitch: Float)

    @Volatile
    private var cachedAutoStoreSound: SoundConfig? = null

    @Volatile
    private var autoStoreSoundParsed = false

    val autoStoreSound: SoundConfig?
        get() {
            if (!autoStoreSoundParsed) {
                cachedAutoStoreSound = parseAutoStoreSound()
                autoStoreSoundParsed = true
            }
            return cachedAutoStoreSound
        }

    private fun parseAutoStoreSound(): SoundConfig? {
        val raw = config.getString("warehouse.auto-store-sound")?.trim()
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split("\\s+".toRegex())
        val sound = runCatching { Sound.valueOf(parts[0].uppercase()) }.getOrNull() ?: return null
        val volume = parts.getOrNull(1)?.toFloatOrNull() ?: 0.5f
        val pitch = parts.getOrNull(2)?.toFloatOrNull() ?: 1.0f
        return SoundConfig(sound, volume, pitch)
    }

    val autoSave: Boolean
        get() = config.getBoolean("save.auto-save", true)

    val autoSaveIntervalSeconds: Long
        get() = config.getLong("save.auto-save-interval", 300L).coerceAtLeast(30L)

    val debug: Boolean
        get() = config.getBoolean("debug", false)

    data class WarehouseCategory(val name: String, val lorePatterns: List<String>)

    // 缓存字段，在 reload() 时重新计算，避免高频访问时反复解析配置。
    @Volatile
    private var cachedBlacklist: Set<String> = emptySet()

    @Volatile
    private var cachedCategories: Map<String, WarehouseCategory> = emptyMap()

    val blacklist: Set<String>
        get() = cachedBlacklist

    val categories: Map<String, WarehouseCategory>
        get() = cachedCategories

    val categoryNames: List<String>
        get() = cachedCategories.keys.toList()

    private fun rebuildBlacklist(): Set<String> {
        return config.getStringList("warehouse.blacklist")
            .map { ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', it)).orEmpty().trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun rebuildCategories(): Map<String, WarehouseCategory> {
        val section = config.getConfigurationSection("warehouse.categories") ?: return emptyMap()
        return buildMap {
            section.getKeys(false).forEach { name ->
                if (name.length > MAX_CATEGORY_NAME_LENGTH) {
                    warning("Ignoring warehouse category '$name': name exceeds $MAX_CATEGORY_NAME_LENGTH characters.")
                    return@forEach
                }
                val patterns = config.getStringList("warehouse.categories.$name")
                put(name, WarehouseCategory(name, patterns))
            }
        }
    }

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
        cachedBlacklist = rebuildBlacklist()
        cachedCategories = rebuildCategories()
        cachedAutoStoreSound = null
        autoStoreSoundParsed = false
    }

    private const val MAX_CATEGORY_NAME_LENGTH = 64
}
