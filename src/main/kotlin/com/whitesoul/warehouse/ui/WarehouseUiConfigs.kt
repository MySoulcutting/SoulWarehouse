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
        // 槽位解析遵循"配置优先 -> 默认布局 -> 剩余空位兜底"，保证 UI 文件写错时菜单仍可打开。
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
        // 使用 firstOrNull 避免全部候选位都被占满时抛出 NoSuchElementException。
        return candidates.firstOrNull { it in 0 until size && it !in used } ?: fallback.coerceIn(0, size - 1)
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

    private data class CachedLayout(
        val title: String,
        val rows: Int,
        val helpButton: UiItemTemplate,
        val profileButton: UiItemTemplate,
        val refreshButton: UiItemTemplate,
        val closeButton: UiItemTemplate,
        val controlSlots: List<Int>,
        val pageSlots: List<Int>,
        val filler: UiItemTemplate,
        val pageItem: UiItemTemplate,
        val lockedPageItem: UiItemTemplate,
    )

    @Volatile
    private var cached: CachedLayout? = null

    private fun computeLayout(): CachedLayout {
        val title = config.getString("title", "&8玩家仓库") ?: "&8玩家仓库"

        val rows = UiConfigSupport.resolveRows(
            requested = config.getInt("rows", 6),
            relevantSlots = UiConfigSupport.readSlots(config, "page-slots") + listOf(
                UiConfigSupport.readRawSlot(config, "items.help", helpDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.profile", profileDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.refresh", refreshDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.close", closeDefault.slot!!),
            ),
        )
        val size = rows * 9

        val helpButton = readControlItem("items.help", helpDefault, setOf(), size)
        val profileButton = readControlItem("items.profile", profileDefault, setOf(helpButton.slot!!), size)
        val refreshButton = readControlItem("items.refresh", refreshDefault, setOf(helpButton.slot!!, profileButton.slot!!), size)
        val closeButton = readControlItem("items.close", closeDefault, setOf(helpButton.slot!!, profileButton.slot!!, refreshButton.slot!!), size)
        val controlSlots = listOf(helpButton.slot!!, profileButton.slot!!, refreshButton.slot!!, closeButton.slot!!)

        val pageSlots = UiConfigSupport.resolveSlotList(
            configured = UiConfigSupport.readSlots(config, "page-slots"),
            fallback = UiConfigSupport.defaultHomePageSlots(),
            size = size,
            reserved = controlSlots.toSet(),
            requiredCount = PluginConfig.maxPages,
        )

        val filler = UiConfigSupport.readItem(
            config,
            "items.filler",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7),
        )

        val pageItem = UiConfigSupport.readItem(
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

        val lockedPageItem = UiConfigSupport.readItem(
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

        return CachedLayout(
            title = title,
            rows = rows,
            helpButton = helpButton,
            profileButton = profileButton,
            refreshButton = refreshButton,
            closeButton = closeButton,
            controlSlots = controlSlots,
            pageSlots = pageSlots,
            filler = filler,
            pageItem = pageItem,
            lockedPageItem = lockedPageItem,
        )
    }

    private fun layout(): CachedLayout {
        return cached ?: computeLayout().also { cached = it }
    }

    val title: String
        get() = layout().title

    val rows: Int
        get() = layout().rows

    val helpButton: UiItemTemplate
        get() = layout().helpButton

    val profileButton: UiItemTemplate
        get() = layout().profileButton

    val refreshButton: UiItemTemplate
        get() = layout().refreshButton

    val closeButton: UiItemTemplate
        get() = layout().closeButton

    val controlSlots: List<Int>
        get() = layout().controlSlots

    val pageSlots: List<Int>
        get() = layout().pageSlots

    val filler: UiItemTemplate
        get() = layout().filler

    val pageItem: UiItemTemplate
        get() = layout().pageItem

    val lockedPageItem: UiItemTemplate
        get() = layout().lockedPageItem

    fun reload() {
        config.reload()
        cached = null
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }

    private fun readControlItem(path: String, fallback: UiItemTemplate, used: Set<Int>, size: Int): UiItemTemplate {
        val raw = UiConfigSupport.readItem(config, path, fallback)
        val slot = UiConfigSupport.resolveButtonSlot(raw.slot, fallback.slot!!, size, used)
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

    private data class CachedLayout(
        val title: String,
        val rows: Int,
        val profileButton: UiItemTemplate,
        val readonlyButton: UiItemTemplate,
        val closeButton: UiItemTemplate,
        val controlSlots: List<Int>,
        val pageSlots: List<Int>,
        val filler: UiItemTemplate,
        val pageItem: UiItemTemplate,
        val lockedPageItem: UiItemTemplate,
    )

    @Volatile
    private var cached: CachedLayout? = null

    private fun computeLayout(): CachedLayout {
        val title = config.getString("title", "&8查看仓库 - {player}") ?: "&8查看仓库 - {player}"

        val rows = UiConfigSupport.resolveRows(
            requested = config.getInt("rows", 6),
            relevantSlots = UiConfigSupport.readSlots(config, "page-slots") + listOf(
                UiConfigSupport.readRawSlot(config, "items.profile", profileDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.readonly", readonlyDefault.slot!!),
                UiConfigSupport.readRawSlot(config, "items.close", closeDefault.slot!!),
            ),
        )
        val size = rows * 9

        val profileButton = readControlItem("items.profile", profileDefault, setOf(), size)
        val readonlyButton = readControlItem("items.readonly", readonlyDefault, setOf(profileButton.slot!!), size)
        val closeButton = readControlItem("items.close", closeDefault, setOf(profileButton.slot!!, readonlyButton.slot!!), size)
        val controlSlots = listOf(profileButton.slot!!, readonlyButton.slot!!, closeButton.slot!!)

        val pageSlots = UiConfigSupport.resolveSlotList(
            configured = UiConfigSupport.readSlots(config, "page-slots"),
            fallback = UiConfigSupport.defaultHomePageSlots(),
            size = size,
            reserved = controlSlots.toSet(),
            requiredCount = PluginConfig.maxPages,
        )

        val filler = UiConfigSupport.readItem(
            config,
            "items.filler",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7),
        )

        val pageItem = UiConfigSupport.readItem(
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

        val lockedPageItem = UiConfigSupport.readItem(
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

        return CachedLayout(
            title = title,
            rows = rows,
            profileButton = profileButton,
            readonlyButton = readonlyButton,
            closeButton = closeButton,
            controlSlots = controlSlots,
            pageSlots = pageSlots,
            filler = filler,
            pageItem = pageItem,
            lockedPageItem = lockedPageItem,
        )
    }

    private fun layout(): CachedLayout {
        return cached ?: computeLayout().also { cached = it }
    }

    val title: String
        get() = layout().title

    val rows: Int
        get() = layout().rows

    val profileButton: UiItemTemplate
        get() = layout().profileButton

    val readonlyButton: UiItemTemplate
        get() = layout().readonlyButton

    val closeButton: UiItemTemplate
        get() = layout().closeButton

    val controlSlots: List<Int>
        get() = layout().controlSlots

    val pageSlots: List<Int>
        get() = layout().pageSlots

    val filler: UiItemTemplate
        get() = layout().filler

    val pageItem: UiItemTemplate
        get() = layout().pageItem

    val lockedPageItem: UiItemTemplate
        get() = layout().lockedPageItem

    fun reload() {
        config.reload()
        cached = null
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }

    private fun readControlItem(path: String, fallback: UiItemTemplate, used: Set<Int>, size: Int): UiItemTemplate {
        val raw = UiConfigSupport.readItem(config, path, fallback)
        val slot = UiConfigSupport.resolveButtonSlot(raw.slot, fallback.slot!!, size, used)
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

    private data class CachedLayout(
        val title: String,
        val rows: Int,
        val previousButton: UiItemTemplate,
        val currentButton: UiItemTemplate,
        val backButton: UiItemTemplate,
        val closeButton: UiItemTemplate,
        val nextButton: UiItemTemplate,
        val controlSlots: List<Int>,
        val storageSlots: List<Int>,
        val storageCapacity: Int,
        val activeStorageSlots: List<Int>,
        val inactiveStorageSlots: List<Int>,
        val backgroundSlots: List<Int>,
        val background: UiItemTemplate,
        val disabledStorage: UiItemTemplate,
    )

    @Volatile
    private var cached: CachedLayout? = null

    private fun computeLayout(): CachedLayout {
        val title = config.getString("title", "&8我的仓库 - 第 {page} 页") ?: "&8我的仓库 - 第 {page} 页"

        val rows = UiConfigSupport.resolveRows(
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
        val size = rows * 9

        val previousButton = readControlItem("items.previous", previousDefault, setOf(), size)
        val currentButton = readControlItem("items.current", currentDefault, setOf(previousButton.slot!!), size)
        val backButton = readControlItem("items.back", backDefault, setOf(previousButton.slot!!, currentButton.slot!!), size)
        val closeButton = readControlItem(
            "items.close",
            closeDefault,
            setOf(previousButton.slot!!, currentButton.slot!!, backButton.slot!!),
            size,
        )
        val nextButton = readControlItem(
            "items.next",
            nextDefault,
            setOf(previousButton.slot!!, currentButton.slot!!, backButton.slot!!, closeButton.slot!!),
            size,
        )
        val controlSlots = listOf(previousButton.slot!!, currentButton.slot!!, backButton.slot!!, closeButton.slot!!, nextButton.slot!!)

        val configured = UiConfigSupport.readSlots(config, "storage-slots")
        val requiredCount = maxOf(
            configured.size.coerceAtMost(45),
            minOf(PluginConfig.pageSize, (0 until size).count { it !in controlSlots.toSet() }),
        )
        // storage-slots 既定义展示顺序，也定义逻辑槽位顺序，仓库页数据按这个顺序映射到 UI。
        val storageSlots = UiConfigSupport.resolveSlotList(
            configured = configured,
            fallback = UiConfigSupport.defaultStorageSlots(),
            size = size,
            reserved = controlSlots.toSet(),
            requiredCount = requiredCount,
        )

        val storageCapacity = minOf(PluginConfig.pageSize, storageSlots.size)
        val activeStorageSlots = storageSlots.take(storageCapacity)
        val inactiveStorageSlots = storageSlots.drop(storageCapacity)

        val used = (storageSlots + controlSlots).toSet()
        val backgroundSlots = (0 until size).filter { it !in used }

        val background = UiConfigSupport.readItem(
            config,
            "items.background",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7),
        )

        val disabledStorage = UiConfigSupport.readItem(
            config,
            "items.disabled-storage",
            UiItemTemplate(
                material = Material.STAINED_GLASS_PANE.name,
                name = "&8不可用槽位",
                lore = listOf("&7当前配置未开放这个槽位"),
                durability = 8,
            ),
        )

        return CachedLayout(
            title = title,
            rows = rows,
            previousButton = previousButton,
            currentButton = currentButton,
            backButton = backButton,
            closeButton = closeButton,
            nextButton = nextButton,
            controlSlots = controlSlots,
            storageSlots = storageSlots,
            storageCapacity = storageCapacity,
            activeStorageSlots = activeStorageSlots,
            inactiveStorageSlots = inactiveStorageSlots,
            backgroundSlots = backgroundSlots,
            background = background,
            disabledStorage = disabledStorage,
        )
    }

    private fun layout(): CachedLayout {
        return cached ?: computeLayout().also { cached = it }
    }

    val title: String
        get() = layout().title

    val rows: Int
        get() = layout().rows

    val previousButton: UiItemTemplate
        get() = layout().previousButton

    val currentButton: UiItemTemplate
        get() = layout().currentButton

    val backButton: UiItemTemplate
        get() = layout().backButton

    val closeButton: UiItemTemplate
        get() = layout().closeButton

    val nextButton: UiItemTemplate
        get() = layout().nextButton

    val controlSlots: List<Int>
        get() = layout().controlSlots

    val storageSlots: List<Int>
        get() = layout().storageSlots

    val storageCapacity: Int
        get() = layout().storageCapacity

    val activeStorageSlots: List<Int>
        get() = layout().activeStorageSlots

    val inactiveStorageSlots: List<Int>
        get() = layout().inactiveStorageSlots

    val backgroundSlots: List<Int>
        get() = layout().backgroundSlots

    val background: UiItemTemplate
        get() = layout().background

    val disabledStorage: UiItemTemplate
        get() = layout().disabledStorage

    fun reload() {
        config.reload()
        cached = null
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }

    private fun readControlItem(path: String, fallback: UiItemTemplate, used: Set<Int>, size: Int): UiItemTemplate {
        val raw = UiConfigSupport.readItem(config, path, fallback)
        val slot = UiConfigSupport.resolveButtonSlot(raw.slot, fallback.slot!!, size, used)
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

    private data class CachedLayout(
        val title: String,
        val rows: Int,
        val previousButton: UiItemTemplate,
        val readonlyButton: UiItemTemplate,
        val backButton: UiItemTemplate,
        val closeButton: UiItemTemplate,
        val nextButton: UiItemTemplate,
        val controlSlots: List<Int>,
        val storageSlots: List<Int>,
        val displayCapacity: Int,
        val activeStorageSlots: List<Int>,
        val inactiveStorageSlots: List<Int>,
        val backgroundSlots: List<Int>,
        val background: UiItemTemplate,
        val disabledStorage: UiItemTemplate,
    )

    @Volatile
    private var cached: CachedLayout? = null

    private fun computeLayout(): CachedLayout {
        val title = config.getString("title", "&8查看仓库 - {player} - 第 {page} 页") ?: "&8查看仓库 - {player} - 第 {page} 页"

        val rows = UiConfigSupport.resolveRows(
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
        val size = rows * 9

        val previousButton = readControlItem("items.previous", previousDefault, setOf(), size)
        val readonlyButton = readControlItem("items.readonly", readonlyDefault, setOf(previousButton.slot!!), size)
        val backButton = readControlItem("items.back", backDefault, setOf(previousButton.slot!!, readonlyButton.slot!!), size)
        val closeButton = readControlItem(
            "items.close",
            closeDefault,
            setOf(previousButton.slot!!, readonlyButton.slot!!, backButton.slot!!),
            size,
        )
        val nextButton = readControlItem(
            "items.next",
            nextDefault,
            setOf(previousButton.slot!!, readonlyButton.slot!!, backButton.slot!!, closeButton.slot!!),
            size,
        )
        val controlSlots = listOf(previousButton.slot!!, readonlyButton.slot!!, backButton.slot!!, closeButton.slot!!, nextButton.slot!!)

        val configured = UiConfigSupport.readSlots(config, "storage-slots")
        val requiredCount = maxOf(
            configured.size.coerceAtMost(45),
            minOf(PageUiConfig.storageCapacity, (0 until size).count { it !in controlSlots.toSet() }),
        )
        // 查看页跟随玩家页容量，避免管理员看到"逻辑上不存在"的隐藏槽位。
        val storageSlots = UiConfigSupport.resolveSlotList(
            configured = configured,
            fallback = UiConfigSupport.defaultStorageSlots(),
            size = size,
            reserved = controlSlots.toSet(),
            requiredCount = requiredCount,
        )

        val displayCapacity = minOf(PageUiConfig.storageCapacity, storageSlots.size)
        val activeStorageSlots = storageSlots.take(displayCapacity)
        val inactiveStorageSlots = storageSlots.drop(displayCapacity)

        val used = (storageSlots + controlSlots).toSet()
        val backgroundSlots = (0 until size).filter { it !in used }

        val background = UiConfigSupport.readItem(
            config,
            "items.background",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7),
        )

        val disabledStorage = UiConfigSupport.readItem(
            config,
            "items.disabled-storage",
            UiItemTemplate(
                material = Material.STAINED_GLASS_PANE.name,
                name = "&8不可用槽位",
                lore = listOf("&7当前布局未显示这个槽位"),
                durability = 8,
            ),
        )

        return CachedLayout(
            title = title,
            rows = rows,
            previousButton = previousButton,
            readonlyButton = readonlyButton,
            backButton = backButton,
            closeButton = closeButton,
            nextButton = nextButton,
            controlSlots = controlSlots,
            storageSlots = storageSlots,
            displayCapacity = displayCapacity,
            activeStorageSlots = activeStorageSlots,
            inactiveStorageSlots = inactiveStorageSlots,
            backgroundSlots = backgroundSlots,
            background = background,
            disabledStorage = disabledStorage,
        )
    }

    private fun layout(): CachedLayout {
        return cached ?: computeLayout().also { cached = it }
    }

    val title: String
        get() = layout().title

    val rows: Int
        get() = layout().rows

    val previousButton: UiItemTemplate
        get() = layout().previousButton

    val readonlyButton: UiItemTemplate
        get() = layout().readonlyButton

    val backButton: UiItemTemplate
        get() = layout().backButton

    val closeButton: UiItemTemplate
        get() = layout().closeButton

    val nextButton: UiItemTemplate
        get() = layout().nextButton

    val controlSlots: List<Int>
        get() = layout().controlSlots

    val storageSlots: List<Int>
        get() = layout().storageSlots

    val displayCapacity: Int
        get() = layout().displayCapacity

    val activeStorageSlots: List<Int>
        get() = layout().activeStorageSlots

    val inactiveStorageSlots: List<Int>
        get() = layout().inactiveStorageSlots

    val backgroundSlots: List<Int>
        get() = layout().backgroundSlots

    val background: UiItemTemplate
        get() = layout().background

    val disabledStorage: UiItemTemplate
        get() = layout().disabledStorage

    fun reload() {
        config.reload()
        cached = null
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }

    private fun readControlItem(path: String, fallback: UiItemTemplate, used: Set<Int>, size: Int): UiItemTemplate {
        val raw = UiConfigSupport.readItem(config, path, fallback)
        val slot = UiConfigSupport.resolveButtonSlot(raw.slot, fallback.slot!!, size, used)
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
