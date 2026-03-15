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
    val char: Char = ' ',
    val material: String,
    val name: String,
    val lore: List<String> = emptyList(),
    val amount: Int = 1,
    val durability: Short = 0,
)

// ============================================================
// 通用工具
// ============================================================

internal object UiConfigSupport {

    /** 默认布局行数组 → 解析出 layout 字符串列表（去掉空格，每行保留 9 字符） */
    fun readLayout(config: Configuration, path: String, fallback: List<String>): List<String> {
        val raw = config.getStringList(path).ifEmpty { fallback }
        return raw.map { it.replace(" ", "") }.filter { it.isNotEmpty() }.take(6)
    }

    /** 从 layout 中提取某个字符对应的所有 slot index */
    fun slotsOf(layout: List<String>, char: Char): List<Int> {
        val result = mutableListOf<Int>()
        layout.forEachIndexed { row, line ->
            line.forEachIndexed { col, c ->
                if (c == char) result += row * 9 + col
            }
        }
        return result
    }

    fun readItem(config: Configuration, path: String, fallback: UiItemTemplate): UiItemTemplate {
        val section = config.getConfigurationSection(path) ?: return fallback
        return readItem(section, fallback)
    }

    fun readItem(section: ConfigurationSection?, fallback: UiItemTemplate): UiItemTemplate {
        if (section == null) return fallback
        val charStr = section.getString("char", fallback.char.toString()) ?: fallback.char.toString()
        return UiItemTemplate(
            char = charStr.firstOrNull() ?: fallback.char,
            material = section.getString("material", fallback.material) ?: fallback.material,
            name = section.getString("name", fallback.name) ?: fallback.name,
            lore = section.getStringList("lore").ifEmpty { fallback.lore },
            amount = section.getInt("amount", fallback.amount).coerceAtLeast(1),
            durability = section.getInt("durability", fallback.durability.toInt()).toShort(),
        )
    }

    fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

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
            if (skipped) null else formatText(line, placeholders)
        }
    }

    fun resolveMaterial(name: String, fallback: Material): Material {
        return Material.matchMaterial(name.uppercase()) ?: fallback
    }
}

// ============================================================
// Home 菜单配置
// ============================================================

@Inject
object HomeUiConfig {

    @Config("ui/home.yml")
    lateinit var config: Configuration

    private val defaultLayout = listOf(
        "FFFFFFFFF",
        "FPPPPPFFF",
        "FPPPPPFFF",
        "FFFFFFFFF",
        "FFFFFFFFF",
        "HAFIRFFFC",
    )

    private data class CachedLayout(
        val title: String,
        val layout: List<String>,
        val rows: Int,
        val filler: UiItemTemplate,
        val pageItem: UiItemTemplate,
        val lockedPageItem: UiItemTemplate,
        val helpButton: UiItemTemplate,
        val autoPickupButton: UiItemTemplate,
        val profileButton: UiItemTemplate,
        val refreshButton: UiItemTemplate,
        val closeButton: UiItemTemplate,
        val pageSlots: List<Int>,
    )

    @Volatile
    private var cached: CachedLayout? = null

    private fun computeLayout(): CachedLayout {
        val title = config.getString("title", "&8玩家仓库") ?: "&8玩家仓库"
        val layout = UiConfigSupport.readLayout(config, "layout", defaultLayout)
        val rows = layout.size.coerceIn(1, 6)

        val filler = UiConfigSupport.readItem(config, "items.filler",
            UiItemTemplate(char = 'F', material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7))
        val pageItem = UiConfigSupport.readItem(config, "items.page",
            UiItemTemplate(char = 'P', material = Material.CHEST.name, name = "&6仓库第 {page} 页"))
        val lockedPageItem = UiConfigSupport.readItem(config, "items.locked-page",
            UiItemTemplate(material = Material.BARRIER.name, name = "&6仓库第 {page} 页"))
        val helpButton = UiConfigSupport.readItem(config, "items.help",
            UiItemTemplate(char = 'H', material = Material.BOOK.name, name = "&e使用说明"))
        val autoPickupButton = UiConfigSupport.readItem(config, "items.auto-pickup",
            UiItemTemplate(char = 'A', material = Material.HOPPER.name, name = "&e自动拾取入仓"))
        val profileButton = UiConfigSupport.readItem(config, "items.profile",
            UiItemTemplate(char = 'I', material = Material.PAPER.name, name = "&b仓库信息"))
        val refreshButton = UiConfigSupport.readItem(config, "items.refresh",
            UiItemTemplate(char = 'R', material = Material.COMPASS.name, name = "&a刷新界面"))
        val closeButton = UiConfigSupport.readItem(config, "items.close",
            UiItemTemplate(char = 'C', material = Material.BARRIER.name, name = "&c关闭菜单"))

        val pageSlots = UiConfigSupport.slotsOf(layout, pageItem.char)

        return CachedLayout(
            title = title, layout = layout, rows = rows,
            filler = filler, pageItem = pageItem, lockedPageItem = lockedPageItem,
            helpButton = helpButton, autoPickupButton = autoPickupButton,
            profileButton = profileButton, refreshButton = refreshButton, closeButton = closeButton,
            pageSlots = pageSlots,
        )
    }

    private fun layout(): CachedLayout = cached ?: computeLayout().also { cached = it }

    val title: String get() = layout().title
    val layoutLines: List<String> get() = layout().layout
    val rows: Int get() = layout().rows
    val filler: UiItemTemplate get() = layout().filler
    val pageItem: UiItemTemplate get() = layout().pageItem
    val lockedPageItem: UiItemTemplate get() = layout().lockedPageItem
    val helpButton: UiItemTemplate get() = layout().helpButton
    val autoPickupButton: UiItemTemplate get() = layout().autoPickupButton
    val profileButton: UiItemTemplate get() = layout().profileButton
    val refreshButton: UiItemTemplate get() = layout().refreshButton
    val closeButton: UiItemTemplate get() = layout().closeButton
    val pageSlots: List<Int> get() = layout().pageSlots

    fun reload() {
        config.reload()
        cached = null
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }
}

// ============================================================
// Inspect Home 菜单配置
// ============================================================

@Inject
object InspectHomeUiConfig {

    @Config("ui/inspect-home.yml")
    lateinit var config: Configuration

    private val defaultLayout = listOf(
        "FFFFFFFFF",
        "FPPPPPFFF",
        "FPPPPPFFF",
        "FFFFFFFFF",
        "FFFFFFFFF",
        "IFFFOFFFC",
    )

    private data class CachedLayout(
        val title: String,
        val layout: List<String>,
        val rows: Int,
        val filler: UiItemTemplate,
        val pageItem: UiItemTemplate,
        val lockedPageItem: UiItemTemplate,
        val profileButton: UiItemTemplate,
        val readonlyButton: UiItemTemplate,
        val closeButton: UiItemTemplate,
        val pageSlots: List<Int>,
    )

    @Volatile
    private var cached: CachedLayout? = null

    private fun computeLayout(): CachedLayout {
        val title = config.getString("title", "&8查看仓库 - {player}") ?: "&8查看仓库 - {player}"
        val layout = UiConfigSupport.readLayout(config, "layout", defaultLayout)
        val rows = layout.size.coerceIn(1, 6)

        val filler = UiConfigSupport.readItem(config, "items.filler",
            UiItemTemplate(char = 'F', material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7))
        val pageItem = UiConfigSupport.readItem(config, "items.page",
            UiItemTemplate(char = 'P', material = Material.ENDER_CHEST.name, name = "&6仓库第 {page} 页"))
        val lockedPageItem = UiConfigSupport.readItem(config, "items.locked-page",
            UiItemTemplate(material = Material.BARRIER.name, name = "&6仓库第 {page} 页"))
        val profileButton = UiConfigSupport.readItem(config, "items.profile",
            UiItemTemplate(char = 'I', material = Material.PAPER.name, name = "&b仓库信息"))
        val readonlyButton = UiConfigSupport.readItem(config, "items.readonly",
            UiItemTemplate(char = 'O', material = Material.BOOK.name, name = "&b只读模式"))
        val closeButton = UiConfigSupport.readItem(config, "items.close",
            UiItemTemplate(char = 'C', material = Material.BARRIER.name, name = "&c关闭菜单"))

        val pageSlots = UiConfigSupport.slotsOf(layout, pageItem.char)

        return CachedLayout(
            title = title, layout = layout, rows = rows,
            filler = filler, pageItem = pageItem, lockedPageItem = lockedPageItem,
            profileButton = profileButton, readonlyButton = readonlyButton, closeButton = closeButton,
            pageSlots = pageSlots,
        )
    }

    private fun layout(): CachedLayout = cached ?: computeLayout().also { cached = it }

    val title: String get() = layout().title
    val layoutLines: List<String> get() = layout().layout
    val rows: Int get() = layout().rows
    val filler: UiItemTemplate get() = layout().filler
    val pageItem: UiItemTemplate get() = layout().pageItem
    val lockedPageItem: UiItemTemplate get() = layout().lockedPageItem
    val profileButton: UiItemTemplate get() = layout().profileButton
    val readonlyButton: UiItemTemplate get() = layout().readonlyButton
    val closeButton: UiItemTemplate get() = layout().closeButton
    val pageSlots: List<Int> get() = layout().pageSlots

    fun reload() {
        config.reload()
        cached = null
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }
}

// ============================================================
// Page 菜单配置
// ============================================================

@Inject
object PageUiConfig {

    @Config("ui/page.yml")
    lateinit var config: Configuration

    private val defaultLayout = listOf(
        "SSSSSSSSS",
        "SSSSSSSSS",
        "SSSSSSSSS",
        "SSSSSSSSS",
        "SSSSSSSSS",
        "<BB=KXBB>",
    )

    private data class CachedLayout(
        val title: String,
        val layout: List<String>,
        val rows: Int,
        val background: UiItemTemplate,
        val disabledStorage: UiItemTemplate,
        val previousButton: UiItemTemplate,
        val currentButton: UiItemTemplate,
        val backButton: UiItemTemplate,
        val closeButton: UiItemTemplate,
        val nextButton: UiItemTemplate,
        /** 布局中所有 S 字符对应的 slot */
        val allStorageSlots: List<Int>,
        /** 实际可用的存储槽位（受 page-size 限制） */
        val activeStorageSlots: List<Int>,
        /** 超出 page-size 的存储槽位，显示为不可用 */
        val inactiveStorageSlots: List<Int>,
        /** 存储容量 */
        val storageCapacity: Int,
    )

    @Volatile
    private var cached: CachedLayout? = null

    private fun computeLayout(): CachedLayout {
        val title = config.getString("title", "&8我的仓库 - 第 {page} 页") ?: "&8我的仓库 - 第 {page} 页"
        val layout = UiConfigSupport.readLayout(config, "layout", defaultLayout)
        val rows = layout.size.coerceIn(1, 6)

        val background = UiConfigSupport.readItem(config, "items.background",
            UiItemTemplate(char = 'B', material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7))
        val disabledStorage = UiConfigSupport.readItem(config, "items.disabled-storage",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = "&8不可用槽位", durability = 8))
        val previousButton = UiConfigSupport.readItem(config, "items.previous",
            UiItemTemplate(char = '<', material = Material.ARROW.name, name = "&e上一页"))
        val currentButton = UiConfigSupport.readItem(config, "items.current",
            UiItemTemplate(char = '=', material = Material.CHEST.name, name = "&b当前页面：第 {page} 页"))
        val backButton = UiConfigSupport.readItem(config, "items.back",
            UiItemTemplate(char = 'K', material = Material.BOOK.name, name = "&e返回主页"))
        val closeButton = UiConfigSupport.readItem(config, "items.close",
            UiItemTemplate(char = 'X', material = Material.BARRIER.name, name = "&c关闭菜单"))
        val nextButton = UiConfigSupport.readItem(config, "items.next",
            UiItemTemplate(char = '>', material = Material.ARROW.name, name = "&e下一页"))

        val allStorageSlots = UiConfigSupport.slotsOf(layout, 'S')
        val storageCapacity = minOf(PluginConfig.pageSize, allStorageSlots.size)
        val activeStorageSlots = allStorageSlots.take(storageCapacity)
        val inactiveStorageSlots = allStorageSlots.drop(storageCapacity)

        return CachedLayout(
            title = title, layout = layout, rows = rows,
            background = background, disabledStorage = disabledStorage,
            previousButton = previousButton, currentButton = currentButton,
            backButton = backButton, closeButton = closeButton, nextButton = nextButton,
            allStorageSlots = allStorageSlots,
            activeStorageSlots = activeStorageSlots,
            inactiveStorageSlots = inactiveStorageSlots,
            storageCapacity = storageCapacity,
        )
    }

    private fun layout(): CachedLayout = cached ?: computeLayout().also { cached = it }

    val title: String get() = layout().title
    val layoutLines: List<String> get() = layout().layout
    val rows: Int get() = layout().rows
    val background: UiItemTemplate get() = layout().background
    val disabledStorage: UiItemTemplate get() = layout().disabledStorage
    val previousButton: UiItemTemplate get() = layout().previousButton
    val currentButton: UiItemTemplate get() = layout().currentButton
    val backButton: UiItemTemplate get() = layout().backButton
    val closeButton: UiItemTemplate get() = layout().closeButton
    val nextButton: UiItemTemplate get() = layout().nextButton
    val allStorageSlots: List<Int> get() = layout().allStorageSlots
    val activeStorageSlots: List<Int> get() = layout().activeStorageSlots
    val inactiveStorageSlots: List<Int> get() = layout().inactiveStorageSlots
    val storageCapacity: Int get() = layout().storageCapacity

    fun reload() {
        config.reload()
        cached = null
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }
}

// ============================================================
// Inspect Page 菜单配置
// ============================================================

@Inject
object InspectPageUiConfig {

    @Config("ui/inspect-page.yml")
    lateinit var config: Configuration

    private val defaultLayout = listOf(
        "SSSSSSSSS",
        "SSSSSSSSS",
        "SSSSSSSSS",
        "SSSSSSSSS",
        "SSSSSSSSS",
        "<BBOKXBB>",
    )

    private data class CachedLayout(
        val title: String,
        val layout: List<String>,
        val rows: Int,
        val background: UiItemTemplate,
        val disabledStorage: UiItemTemplate,
        val previousButton: UiItemTemplate,
        val readonlyButton: UiItemTemplate,
        val backButton: UiItemTemplate,
        val closeButton: UiItemTemplate,
        val nextButton: UiItemTemplate,
        val allStorageSlots: List<Int>,
        val activeStorageSlots: List<Int>,
        val inactiveStorageSlots: List<Int>,
        val displayCapacity: Int,
    )

    @Volatile
    private var cached: CachedLayout? = null

    private fun computeLayout(): CachedLayout {
        val title = config.getString("title", "&8查看仓库 - {player} - 第 {page} 页") ?: "&8查看仓库 - {player} - 第 {page} 页"
        val layout = UiConfigSupport.readLayout(config, "layout", defaultLayout)
        val rows = layout.size.coerceIn(1, 6)

        val background = UiConfigSupport.readItem(config, "items.background",
            UiItemTemplate(char = 'B', material = Material.STAINED_GLASS_PANE.name, name = " ", durability = 7))
        val disabledStorage = UiConfigSupport.readItem(config, "items.disabled-storage",
            UiItemTemplate(material = Material.STAINED_GLASS_PANE.name, name = "&8不可用槽位", durability = 8))
        val previousButton = UiConfigSupport.readItem(config, "items.previous",
            UiItemTemplate(char = '<', material = Material.ARROW.name, name = "&e上一页"))
        val readonlyButton = UiConfigSupport.readItem(config, "items.readonly",
            UiItemTemplate(char = 'O', material = Material.BOOK.name, name = "&b只读模式"))
        val backButton = UiConfigSupport.readItem(config, "items.back",
            UiItemTemplate(char = 'K', material = Material.CHEST.name, name = "&e返回主页"))
        val closeButton = UiConfigSupport.readItem(config, "items.close",
            UiItemTemplate(char = 'X', material = Material.BARRIER.name, name = "&c关闭菜单"))
        val nextButton = UiConfigSupport.readItem(config, "items.next",
            UiItemTemplate(char = '>', material = Material.ARROW.name, name = "&e下一页"))

        val allStorageSlots = UiConfigSupport.slotsOf(layout, 'S')
        val displayCapacity = minOf(PageUiConfig.storageCapacity, allStorageSlots.size)
        val activeStorageSlots = allStorageSlots.take(displayCapacity)
        val inactiveStorageSlots = allStorageSlots.drop(displayCapacity)

        return CachedLayout(
            title = title, layout = layout, rows = rows,
            background = background, disabledStorage = disabledStorage,
            previousButton = previousButton, readonlyButton = readonlyButton,
            backButton = backButton, closeButton = closeButton, nextButton = nextButton,
            allStorageSlots = allStorageSlots,
            activeStorageSlots = activeStorageSlots,
            inactiveStorageSlots = inactiveStorageSlots,
            displayCapacity = displayCapacity,
        )
    }

    private fun layout(): CachedLayout = cached ?: computeLayout().also { cached = it }

    val title: String get() = layout().title
    val layoutLines: List<String> get() = layout().layout
    val rows: Int get() = layout().rows
    val background: UiItemTemplate get() = layout().background
    val disabledStorage: UiItemTemplate get() = layout().disabledStorage
    val previousButton: UiItemTemplate get() = layout().previousButton
    val readonlyButton: UiItemTemplate get() = layout().readonlyButton
    val backButton: UiItemTemplate get() = layout().backButton
    val closeButton: UiItemTemplate get() = layout().closeButton
    val nextButton: UiItemTemplate get() = layout().nextButton
    val allStorageSlots: List<Int> get() = layout().allStorageSlots
    val activeStorageSlots: List<Int> get() = layout().activeStorageSlots
    val inactiveStorageSlots: List<Int> get() = layout().inactiveStorageSlots
    val displayCapacity: Int get() = layout().displayCapacity

    fun reload() {
        config.reload()
        cached = null
    }

    fun formatTitle(placeholders: Map<String, Any?> = emptyMap()): String {
        return UiConfigSupport.formatText(title, placeholders)
    }
}

// ============================================================
// 扩展函数
// ============================================================

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
