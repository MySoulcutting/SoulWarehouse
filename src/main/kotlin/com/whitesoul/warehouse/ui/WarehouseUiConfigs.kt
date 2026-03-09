package com.whitesoul.warehouse.ui

import com.whitesoul.warehouse.config.PluginConfig
import org.bukkit.ChatColor
import org.bukkit.Material
import taboolib.common.Inject
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

object WarehouseUiConfigs {

    fun reload() {
        HomeUiConfig.reload()
        InspectHomeUiConfig.reload()
        PageUiConfig.reload()
        InspectPageUiConfig.reload()
    }
}

data class UiItemTemplate(
    val slot: Int? = null,
    val material: String,
    val name: String,
    val lore: List<String> = emptyList(),
    val amount: Int = 1,
    val durability: Short = 0,
)

private object UiConfigSupport {

    private val defaultHomePageSlots = listOf(11, 12, 13, 14, 15, 20, 21, 22, 23, 24)
    private val defaultStorageSlots = (0 until 45).toList()

    fun resolveRows(requested: Int, relevantSlots: Collection<Int>, minimumRows: Int = 1): Int {
        val requiredRows = relevantSlots.maxOrNull()?.let { it / 9 + 1 } ?: minimumRows
        return maxOf(requested.coerceIn(minimumRows, 6), requiredRows).coerceIn(minimumRows, 6)
    }

    fun resolveSlotList(
        configured: List<Int>,
        fallback: List<Int>,
        size: Int,
        reserved: Set<Int> = emptySet(),
        requiredCount: Int? = null,
    ): List<Int> {
        val resolved = LinkedHashSet<Int>()
        fun append(slots: Iterable<Int>) {
            for (slot in slots) {
                if (slot !in 0 until size || slot in reserved) {
                    continue
                }
                resolved += slot
                if (requiredCount != null && resolved.size >= requiredCount) {
                    return
                }
            }
        }
        // 槽位解析遵循“配置优先 -> 默认布局 -> 剩余空位兜底”，保证 UI 文件写错时菜单仍可打开。
        append(configured)
        if (requiredCount == null || resolved.size < requiredCount) {
            append(fallback)
        }
        if (requiredCount == null || resolved.size < requiredCount) {
            append(0 until size)
        }
        return if (requiredCount == null) resolved.toList() else resolved.take(requiredCount)
    }

    fun resolveButtonSlot(
        configured: Int?,
        fallback: Int,
        size: Int,
        used: Set<Int>,
    ): Int {
        val candidates = buildList {
            if (configured != null) add(configured)
            add(fallback)
            addAll(0 until size)
        }
        // 控制按钮允许自动避让已占用槽位，减少错误配置导致的按钮重叠。
        return candidates.first { it in 0 until size && it !in used }
    }

    fun readSlots(config: Configuration, path: String): List<Int> {
        return config.getList(path)
            ?.mapNotNull {
                when (it) {
                    is Number -> it.toInt()
                    is String -> it.toIntOrNull()
                    else -> null
                }
            }
            ?.distinct()
            ?: emptyList()
    }

    fun readItem(config: Configuration, path: String, fallback: UiItemTemplate): UiItemTemplate {
        val section = config.getConfigurationSection(path)
        return readItem(section, fallback)
    }

    fun readRawSlot(config: Configuration, path: String, fallback: Int): Int {
        val section = config.getConfigurationSection(path) ?: return fallback
        return if (section.contains("slot")) section.getInt("slot") else fallback
    }

    fun readItem(section: ConfigurationSection?, fallback: UiItemTemplate): UiItemTemplate {
        if (section == null) {
            return fallback
        }
        val slot = if (section.contains("slot")) section.getInt("slot") else fallback.slot
        return UiItemTemplate(
            slot = slot,
            material = section.getString("material", fallback.material) ?: fallback.material,
            name = section.getString("name", fallback.name) ?: fallback.name,
            lore = section.getStringList("lore").ifEmpty { fallback.lore },
            amount = section.getInt("amount", fallback.amount).coerceAtLeast(1),
            durability = section.getInt("durability", fallback.durability.toInt()).toShort(),
        )
    }

    fun defaultHomePageSlots(): List<Int> {
        return defaultHomePageSlots
    }

    fun defaultStorageSlots(): List<Int> {
        return defaultStorageSlots
    }

    fun color(text: String): String {
        return ChatColor.translateAlternateColorCodes('&', text)
    }

    fun formatText(text: String, placeholders: Map<String, Any?> = emptyMap()): String {
        var formatted = text
        placeholders.forEach { (key, value) ->
            formatted = formatted.replace("{$key}", value?.toString().orEmpty())
        }
        return color(formatted)
    }

    fun formatLore(
        lore: List<String>,
        placeholders: Map<String, Any?> = emptyMap(),
        skipBlankKeys: Set<String> = emptySet(),
    ): List<String> {
        return lore.mapNotNull { line ->
            val skipped = skipBlankKeys.any { key ->
                line.contains("{$key}") && placeholders[key]?.toString().orEmpty().isBlank()
            }
            if (skipped) {
                null
            } else {
                formatText(line, placeholders)
            }
        }
    }

    fun resolveMaterial(name: String, fallback: Material): Material {
        return Material.matchMaterial(name.uppercase()) ?: fallback
    }
}

@Inject
object HomeUiConfig {

    @Config("ui/home.yml")
    lateinit var config: Configuration

    private val helpDefault = UiItemTemplate(45, Material.BOOK.name, "&e使用说明")
    private val profileDefault = UiItemTemplate(49, Material.PAPER.name, "&b仓库信息")
    private val refreshDefault = UiItemTemplate(50, Material.COMPASS.name, "&a刷新界面")
    private val closeDefault = UiItemTemplate(53, Material.BARRIER.name, "&c关闭菜单")

    val title: String
        get() = config.getString("title", "&8玩家仓库") ?: "&8玩家仓库"

    val pageSlots: List<Int>
        get() {
            val rows = rows
            val size = rows * 9
            return UiConfigSupport.resolveSlotList(
                configured = UiConfigSupport.readSlots(config, "page-slots"),
                fallback = UiConfigSupport.defaultHomePageSlots(),
                size = size,
                reserved = controlSlots.toSet(),
                requiredCount = PluginConfig.maxPages,
            )
        }

    val filler: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.filler",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7),
        )

    val pageItem: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.page",
            UiItemTemplate(
                material = Material.CHEST.name,
                name = "&6仓库第 {page} 页",
                lore = listOf(
                    "&7状态：&a已解锁",
                    "&7已使用：&f{used}&7/&f{page_size}",
                    "&7备注：&f{remark}",
                    "&e左键打开这一页仓库",
                    "&e右键设置备注",
                ),
            ),
        )

    val lockedPageItem: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.locked-page",
            UiItemTemplate(
                material = Material.BARRIER.name,
                name = "&6仓库第 {page} 页",
                lore = listOf(
                    "&7状态：&c未解锁",
                    "&7你还不能访问这一页",
                ),
            ),
        )

    val helpButton: UiItemTemplate
        get() = readControlItem("items.help", helpDefault, setOf())

    val profileButton: UiItemTemplate
        get() = readControlItem("items.profile", profileDefault, setOf(helpButton.slot!!))

    val refreshButton: UiItemTemplate
        get() = readControlItem("items.refresh", refreshDefault, setOf(helpButton.slot!!, profileButton.slot!!))

    val closeButton: UiItemTemplate
        get() = readControlItem("items.close", closeDefault, setOf(helpButton.slot!!, profileButton.slot!!, refreshButton.slot!!))

    val controlSlots: List<Int>
        get() = listOf(helpButton.slot!!, profileButton.slot!!, refreshButton.slot!!, closeButton.slot!!)

    val rows: Int
        get() = UiConfigSupport.resolveRows(
            requested = config.getInt("rows", 6),
            relevantSlots = UiConfigSupport.readSlots(config, "page-slots") + listOf(
                UiConfigSupport.readRawSlot(config, "items.help", helpDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.profile", profileDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.refresh", refreshDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.close", closeDefault.slot!!),
            ),
        )

    fun reload() {
        config.reload()
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }

    private fun readControlItem(path: String, fallback: UiItemTemplate, used: Set<Int>): UiItemTemplate {
        val raw = UiConfigSupport.readItem(config, path, fallback)
        val slot = UiConfigSupport.resolveButtonSlot(raw.slot, fallback.slot!!, rows * 9, used)
        return raw.copy(slot = slot)
    }
}

@Inject
object InspectHomeUiConfig {

    @Config("ui/inspect-home.yml")
    lateinit var config: Configuration

    private val profileDefault = UiItemTemplate(45, Material.PAPER.name, "&b仓库信息")
    private val readonlyDefault = UiItemTemplate(49, Material.BOOK.name, "&b只读模式")
    private val closeDefault = UiItemTemplate(53, Material.BARRIER.name, "&c关闭菜单")

    val title: String
        get() = config.getString("title", "&8查看仓库 - {player}") ?: "&8查看仓库 - {player}"

    val pageSlots: List<Int>
        get() {
            val rows = rows
            val size = rows * 9
            return UiConfigSupport.resolveSlotList(
                configured = UiConfigSupport.readSlots(config, "page-slots"),
                fallback = UiConfigSupport.defaultHomePageSlots(),
                size = size,
                reserved = controlSlots.toSet(),
                requiredCount = PluginConfig.maxPages,
            )
        }

    val filler: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.filler",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7),
        )

    val pageItem: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.page",
            UiItemTemplate(
                material = Material.ENDER_CHEST.name,
                name = "&6仓库第 {page} 页",
                lore = listOf(
                    "&7状态：&b只读查看",
                    "&7已使用：&f{used}&7/&f{page_size}",
                    "&7备注：&f{remark}",
                    "&e点击查看这一页内容",
                ),
            ),
        )

    val lockedPageItem: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.locked-page",
            UiItemTemplate(
                material = Material.BARRIER.name,
                name = "&6仓库第 {page} 页",
                lore = listOf(
                    "&7状态：&c未解锁",
                    "&7这个页面当前不可查看",
                ),
            ),
        )

    val profileButton: UiItemTemplate
        get() = readControlItem("items.profile", profileDefault, setOf())

    val readonlyButton: UiItemTemplate
        get() = readControlItem("items.readonly", readonlyDefault, setOf(profileButton.slot!!))

    val closeButton: UiItemTemplate
        get() = readControlItem("items.close", closeDefault, setOf(profileButton.slot!!, readonlyButton.slot!!))

    val controlSlots: List<Int>
        get() = listOf(profileButton.slot!!, readonlyButton.slot!!, closeButton.slot!!)

    val rows: Int
        get() = UiConfigSupport.resolveRows(
            requested = config.getInt("rows", 6),
            relevantSlots = UiConfigSupport.readSlots(config, "page-slots") + listOf(
                UiConfigSupport.readRawSlot(config, "items.profile", profileDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.readonly", readonlyDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.close", closeDefault.slot!!),
            ),
        )

    fun reload() {
        config.reload()
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }

    private fun readControlItem(path: String, fallback: UiItemTemplate, used: Set<Int>): UiItemTemplate {
        val raw = UiConfigSupport.readItem(config, path, fallback)
        val slot = UiConfigSupport.resolveButtonSlot(raw.slot, fallback.slot!!, rows * 9, used)
        return raw.copy(slot = slot)
    }
}

@Inject
object PageUiConfig {

    @Config("ui/page.yml")
    lateinit var config: Configuration

    private val previousDefault = UiItemTemplate(45, Material.ARROW.name, "&e上一页")
    private val currentDefault = UiItemTemplate(48, Material.CHEST.name, "&b当前页面：第 {page} 页")
    private val backDefault = UiItemTemplate(49, Material.BOOK.name, "&e返回主页")
    private val closeDefault = UiItemTemplate(50, Material.BARRIER.name, "&c关闭菜单")
    private val nextDefault = UiItemTemplate(53, Material.ARROW.name, "&e下一页")

    val title: String
        get() = config.getString("title", "&8我的仓库 - 第 {page} 页") ?: "&8我的仓库 - 第 {page} 页"

    val background: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.background",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7),
        )

    val disabledStorage: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.disabled-storage",
            UiItemTemplate(
                material = Material.STAINED_GLASS_PANE.name,
                name = "&8不可用槽位",
                lore = listOf("&7当前配置未开放这个槽位"),
                durability = 8,
            ),
        )

    val previousButton: UiItemTemplate
        get() = readControlItem("items.previous", previousDefault, setOf())

    val currentButton: UiItemTemplate
        get() = readControlItem("items.current", currentDefault, setOf(previousButton.slot!!))

    val backButton: UiItemTemplate
        get() = readControlItem("items.back", backDefault, setOf(previousButton.slot!!, currentButton.slot!!))

    val closeButton: UiItemTemplate
        get() = readControlItem(
            "items.close",
            closeDefault,
            setOf(previousButton.slot!!, currentButton.slot!!, backButton.slot!!),
        )

    val nextButton: UiItemTemplate
        get() = readControlItem(
            "items.next",
            nextDefault,
            setOf(previousButton.slot!!, currentButton.slot!!, backButton.slot!!, closeButton.slot!!),
        )

    val controlSlots: List<Int>
        get() = listOf(previousButton.slot!!, currentButton.slot!!, backButton.slot!!, closeButton.slot!!, nextButton.slot!!)

    val rows: Int
        get() = UiConfigSupport.resolveRows(
            requested = config.getInt("rows", 6),
            relevantSlots = UiConfigSupport.readSlots(config, "storage-slots") + listOf(
                UiConfigSupport.readRawSlot(config, "items.previous", previousDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.current", currentDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.back", backDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.close", closeDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.next", nextDefault.slot!!),
            ),
            minimumRows = 2,
        )

    val storageSlots: List<Int>
        get() {
            val size = rows * 9
            val configured = UiConfigSupport.readSlots(config, "storage-slots")
            val requiredCount = maxOf(
                configured.size.coerceAtMost(45),
                minOf(PluginConfig.pageSize, (0 until size).count { it !in controlSlots.toSet() }),
            )
            // storage-slots 既定义展示顺序，也定义逻辑槽位顺序，仓库页数据按这个顺序映射到 UI。
            return UiConfigSupport.resolveSlotList(
                configured = configured,
                fallback = UiConfigSupport.defaultStorageSlots(),
                size = size,
                reserved = controlSlots.toSet(),
                requiredCount = requiredCount,
            )
        }

    val storageCapacity: Int
        get() = minOf(PluginConfig.pageSize, storageSlots.size)

    val activeStorageSlots: List<Int>
        get() = storageSlots.take(storageCapacity)

    val inactiveStorageSlots: List<Int>
        get() = storageSlots.drop(storageCapacity)

    val backgroundSlots: List<Int>
        get() {
            val used = (storageSlots + controlSlots).toSet()
            return (0 until rows * 9).filter { it !in used }
        }

    fun reload() {
        config.reload()
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }

    private fun readControlItem(path: String, fallback: UiItemTemplate, used: Set<Int>): UiItemTemplate {
        val raw = UiConfigSupport.readItem(config, path, fallback)
        val slot = UiConfigSupport.resolveButtonSlot(raw.slot, fallback.slot!!, rows * 9, used)
        return raw.copy(slot = slot)
    }
}

@Inject
object InspectPageUiConfig {

    @Config("ui/inspect-page.yml")
    lateinit var config: Configuration

    private val previousDefault = UiItemTemplate(45, Material.ARROW.name, "&e上一页")
    private val readonlyDefault = UiItemTemplate(48, Material.BOOK.name, "&b只读模式")
    private val backDefault = UiItemTemplate(49, Material.CHEST.name, "&e返回主页")
    private val closeDefault = UiItemTemplate(50, Material.BARRIER.name, "&c关闭菜单")
    private val nextDefault = UiItemTemplate(53, Material.ARROW.name, "&e下一页")

    val title: String
        get() = config.getString("title", "&8查看仓库 - {player} - 第 {page} 页") ?: "&8查看仓库 - {player} - 第 {page} 页"

    val background: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.background",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7),
        )

    val disabledStorage: UiItemTemplate
        get() = UiConfigSupport.readItem(
            config,
            "items.disabled-storage",
            UiItemTemplate(
                material = Material.STAINED_GLASS_PANE.name,
                name = "&8不可用槽位",
                lore = listOf("&7当前布局未显示这个槽位"),
                durability = 8,
            ),
        )

    val previousButton: UiItemTemplate
        get() = readControlItem("items.previous", previousDefault, setOf())

    val readonlyButton: UiItemTemplate
        get() = readControlItem("items.readonly", readonlyDefault, setOf(previousButton.slot!!))

    val backButton: UiItemTemplate
        get() = readControlItem("items.back", backDefault, setOf(previousButton.slot!!, readonlyButton.slot!!))

    val closeButton: UiItemTemplate
        get() = readControlItem(
            "items.close",
            closeDefault,
            setOf(previousButton.slot!!, readonlyButton.slot!!, backButton.slot!!),
        )

    val nextButton: UiItemTemplate
        get() = readControlItem(
            "items.next",
            nextDefault,
            setOf(previousButton.slot!!, readonlyButton.slot!!, backButton.slot!!, closeButton.slot!!),
        )

    val controlSlots: List<Int>
        get() = listOf(previousButton.slot!!, readonlyButton.slot!!, backButton.slot!!, closeButton.slot!!, nextButton.slot!!)

    val rows: Int
        get() = UiConfigSupport.resolveRows(
            requested = config.getInt("rows", 6),
            relevantSlots = UiConfigSupport.readSlots(config, "storage-slots") + listOf(
                UiConfigSupport.readRawSlot(config, "items.previous", previousDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.readonly", readonlyDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.back", backDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.close", closeDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.next", nextDefault.slot!!),
            ),
            minimumRows = 2,
        )

    val storageSlots: List<Int>
        get() {
            val size = rows * 9
            val configured = UiConfigSupport.readSlots(config, "storage-slots")
            val requiredCount = maxOf(
                configured.size.coerceAtMost(45),
                minOf(PageUiConfig.storageCapacity, (0 until size).count { it !in controlSlots.toSet() }),
            )
            // 查看页跟随玩家页容量，避免管理员看到“逻辑上不存在”的隐藏槽位。
            return UiConfigSupport.resolveSlotList(
                configured = configured,
                fallback = UiConfigSupport.defaultStorageSlots(),
                size = size,
                reserved = controlSlots.toSet(),
                requiredCount = requiredCount,
            )
        }

    val displayCapacity: Int
        get() = minOf(PageUiConfig.storageCapacity, storageSlots.size)

    val activeStorageSlots: List<Int>
        get() = storageSlots.take(displayCapacity)

    val inactiveStorageSlots: List<Int>
        get() = storageSlots.drop(displayCapacity)

    val backgroundSlots: List<Int>
        get() {
            val used = (storageSlots + controlSlots).toSet()
            return (0 until rows * 9).filter { it !in used }
        }

    fun reload() {
        config.reload()
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }

    private fun readControlItem(path: String, fallback: UiItemTemplate, used: Set<Int>): UiItemTemplate {
        val raw = UiConfigSupport.readItem(config, path, fallback)
        val slot = UiConfigSupport.resolveButtonSlot(raw.slot, fallback.slot!!, rows * 9, used)
        return raw.copy(slot = slot)
    }
}

internal fun UiItemTemplate.buildItem(
    placeholders: Map<String, Any?> = emptyMap(),
    skipBlankKeys: Set<String> = emptySet(),
    fallbackMaterial: Material = Material.STONE,
): org.bukkit.inventory.ItemStack {
    return MenuSupport.item(
        material = UiConfigSupport.resolveMaterial(material, fallbackMaterial),
        name = UiConfigSupport.formatText(name, placeholders),
        lore = UiConfigSupport.formatLore(lore, placeholders, skipBlankKeys),
        amount = amount,
        durability = durability,
    )
}
