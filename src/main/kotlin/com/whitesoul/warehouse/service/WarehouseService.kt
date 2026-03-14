package com.whitesoul.warehouse.service

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.model.WarehousePageData
import com.whitesoul.warehouse.model.WarehouseProfile
import com.whitesoul.warehouse.storage.PtcObjectWarehouseRepository
import com.whitesoul.warehouse.storage.WarehouseRepository
import com.whitesoul.warehouse.util.DebugLogger
import com.whitesoul.warehouse.util.cloneOrNull
import org.bukkit.ChatColor
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.warning
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object WarehouseService {

    data class StoreItemResult(
        val storedAmount: Int,
        val remaining: ItemStack?,
    )

    private val repository: WarehouseRepository = PtcObjectWarehouseRepository()
    private val warehouseCache = ConcurrentHashMap<PageKey, WarehousePageData>()
    private val profileCache = ConcurrentHashMap<UUID, WarehouseProfile>()
    // dirty 集合只记录"需要落盘的键"，真正数据本体仍保存在缓存映射里。
    private val dirtyPages = ConcurrentHashMap.newKeySet<PageKey>()
    private val dirtyProfiles = ConcurrentHashMap.newKeySet<UUID>()

    @Volatile
    private var available = false

    @Volatile
    private var lastError: String? = null

    fun initialize() {
        DebugLogger.timed("initialize 数据库连接") {
            runCatching {
                repository.initialize()
                available = true
                lastError = null
            }.onFailure {
                available = false
                lastError = it.message
                warning("Failed to initialize SoulWarehouse storage: ${it.message}")
                it.printStackTrace()
            }
        }
    }

    fun shutdown() {
        // 关闭前强制刷一次缓存，确保停服时不会丢最后一批未自动保存的数据。
        DebugLogger.timed("shutdown 保存全部缓存") {
            val savedPages = saveDirtyPages(force = true)
            val savedProfiles = saveDirtyProfiles(force = true)
            val remainingPages = dirtyPages.size
            val remainingProfiles = dirtyProfiles.size
            if (remainingPages > 0 || remainingProfiles > 0) {
                warning("Shutdown save incomplete! $remainingPages dirty pages and $remainingProfiles dirty profiles were NOT saved. Data may be lost.")
            } else {
                DebugLogger.log { "shutdown 保存完成: pages=$savedPages, profiles=$savedProfiles" }
            }
        }
        repository.shutdown()
        available = false
    }

    fun reload() {
        // reload 先保存后清空，避免切配置时把旧缓存直接丢掉。
        val savedPages = saveDirtyPages(force = true)
        val savedProfiles = saveDirtyProfiles(force = true)
        val remainingPages = dirtyPages.size
        val remainingProfiles = dirtyProfiles.size
        if (remainingPages > 0 || remainingProfiles > 0) {
            warning("Reload save incomplete! $remainingPages dirty pages and $remainingProfiles dirty profiles were NOT saved. Keeping dirty markers for retry.")
        }
        warehouseCache.clear()
        profileCache.clear()
        // 只有全部保存成功才清除脏标记，否则保留让下次自动保存兜底。
        if (remainingPages == 0) dirtyPages.clear()
        if (remainingProfiles == 0) dirtyProfiles.clear()
        repository.shutdown()
        initialize()
    }

    fun isAvailable(): Boolean {
        return available
    }

    fun getLastError(): String? {
        return lastError
    }

    fun getAccessiblePages(player: Player): Int {
        if (player.hasPermission("soulwarehouse.page.*")) {
            return PluginConfig.maxPages
        }
        var pages = PluginConfig.defaultPages
        for (page in 1..PluginConfig.maxPages) {
            if (player.hasPermission("soulwarehouse.page.$page")) {
                pages = maxOf(pages, page)
            }
        }
        return pages.coerceIn(1, PluginConfig.maxPages)
    }

    fun touchProfile(player: Player): WarehouseProfile {
        val now = System.currentTimeMillis()
        val profile = profileCache[player.uniqueId] ?: if (available) repository.loadProfile(player.uniqueId) else null
        val result = profile ?: WarehouseProfile(
            uuid = player.uniqueId,
            name = player.name,
            maxPages = getAccessiblePages(player),
            createdAt = now,
        )
        result.name = player.name
        result.maxPages = getAccessiblePages(player)
        result.updatedAt = now
        result.lastOpenAt = now
        profileCache[player.uniqueId] = result
        markProfileDirty(player.uniqueId)
        return result
    }

    fun toggleAutoPickup(player: Player): Boolean {
        val profile = getProfile(player)
        profile.autoPickup = !profile.autoPickup
        profile.updatedAt = System.currentTimeMillis()
        markProfileDirty(player.uniqueId)
        return profile.autoPickup
    }

    fun isAutoPickup(player: Player): Boolean {
        return getProfile(player).autoPickup
    }

    fun getProfile(player: OfflinePlayer): WarehouseProfile {
        val cached = profileCache[player.uniqueId]
        if (cached != null) {
            return cached
        }
        val loaded = if (available) repository.loadProfile(player.uniqueId) else null
        if (loaded != null) {
            profileCache[player.uniqueId] = loaded
            return loaded
        }
        val fallback = WarehouseProfile(
            uuid = player.uniqueId,
            name = player.name ?: player.uniqueId.toString(),
            maxPages = PluginConfig.defaultPages,
        )
        profileCache[player.uniqueId] = fallback
        return fallback
    }

    fun getOrLoadPage(owner: UUID, page: Int): WarehousePageData {
        val key = PageKey(owner, page)
        return warehouseCache.computeIfAbsent(key) {
            // 页面按需加载，未创建过的数据直接回退到一页空仓。
            DebugLogger.timed("loadPage owner=$owner, page=$page") {
                if (available) repository.loadPage(owner, page) ?: WarehousePageData(owner, page) else WarehousePageData(owner, page)
            }
        }
    }

    fun snapshotPageContents(owner: UUID, page: Int): Array<ItemStack?> {
        return getOrLoadPage(owner, page).snapshotContents()
    }

    fun getPageUsedSlots(owner: UUID, page: Int, activeSize: Int): Int {
        return getOrLoadPage(owner, page).countUsedSlots(activeSize)
    }

    fun getPageRemark(owner: UUID, page: Int): String {
        return getOrLoadPage(owner, page).remark
    }

    fun updatePageRemark(owner: UUID, page: Int, remark: String): String {
        val normalized = normalizePageRemark(remark)
        val pageData = getOrLoadPage(owner, page)
        pageData.remark = normalized
        markDirty(owner, page)
        return normalized
    }

    fun getPageCategory(owner: UUID, page: Int): String {
        return getOrLoadPage(owner, page).category
    }

    fun updatePageCategory(owner: UUID, page: Int, category: String) {
        val pageData = getOrLoadPage(owner, page)
        pageData.category = category
        markDirty(owner, page)
    }

    fun updatePageContents(owner: UUID, page: Int, contents: Array<ItemStack?>, size: Int = PluginConfig.pageSize): Boolean {
        val pageData = getOrLoadPage(owner, page)
        val changed = pageData.replaceContents(contents, size)
        if (changed) {
            markDirty(owner, page)
        }
        return changed
    }

    fun storeItem(player: Player, itemStack: ItemStack, size: Int = PluginConfig.pageSize): StoreItemResult {
        val normalized = itemStack.cloneOrNull() ?: return StoreItemResult(0, null)
        if (!available || !isItemAllowed(normalized)) {
            DebugLogger.log { "storeItem 拒绝: player=${player.name}, item=${normalized.type.name}, allowed=${isItemAllowed(normalized)}, available=$available" }
            return StoreItemResult(0, normalized)
        }
        val start = System.nanoTime()
        var remainingAmount = normalized.amount
        val accessiblePages = getAccessiblePages(player)
        val categories = PluginConfig.categories
        // 先补满已有堆叠，再尝试占用空格，能最大化压缩仓库空间。
        for (page in 1..accessiblePages) {
            if (!isPageAcceptItem(player.uniqueId, page, normalized, categories)) continue
            remainingAmount = storeIntoPage(player.uniqueId, page, normalized, remainingAmount, size, mergeOnly = true)
            if (remainingAmount <= 0) {
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                DebugLogger.log { "storeItem 完成(合并): player=${player.name}, item=${normalized.type.name}, amount=${normalized.amount}, elapsed=${String.format("%.2f", elapsed)}ms" }
                return StoreItemResult(normalized.amount, null)
            }
        }
        for (page in 1..accessiblePages) {
            if (!isPageAcceptItem(player.uniqueId, page, normalized, categories)) continue
            remainingAmount = storeIntoPage(player.uniqueId, page, normalized, remainingAmount, size, mergeOnly = false)
            if (remainingAmount <= 0) {
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                DebugLogger.log { "storeItem 完成(新槽): player=${player.name}, item=${normalized.type.name}, amount=${normalized.amount}, elapsed=${String.format("%.2f", elapsed)}ms" }
                return StoreItemResult(normalized.amount, null)
            }
        }
        val storedAmount = normalized.amount - remainingAmount
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        DebugLogger.log { "storeItem 部分存入: player=${player.name}, item=${normalized.type.name}, stored=$storedAmount, remaining=$remainingAmount, elapsed=${String.format("%.2f", elapsed)}ms" }
        return StoreItemResult(
            storedAmount = storedAmount,
            remaining = normalized.clone().apply { amount = remainingAmount },
        )
    }

    fun markDirty(owner: UUID, page: Int) {
        dirtyPages += PageKey(owner, page)
    }

    fun markProfileDirty(uuid: UUID) {
        dirtyProfiles += uuid
    }

    fun saveProfile(uuid: UUID, force: Boolean = false): Boolean {
        if (!available) {
            return false
        }
        if (!force && uuid !in dirtyProfiles) {
            return true
        }
        val profile = profileCache[uuid] ?: return true
        return runCatching {
            repository.saveProfile(profile)
            dirtyProfiles.remove(uuid)
            true
        }.getOrElse {
            warning("Failed to save warehouse profile $uuid - ${it.message}")
            false
        }
    }

    fun saveDirtyProfiles(force: Boolean = false): Int {
        if (!available) {
            return 0
        }
        val uuids = dirtyProfiles.toList()
        if (uuids.isEmpty()) {
            return 0
        }
        val profiles = uuids.mapNotNull { uuid -> profileCache[uuid]?.let { uuid to it } }
        if (profiles.isEmpty()) {
            return 0
        }
        return runCatching {
            repository.saveProfiles(profiles.map { it.second })
            profiles.forEach { dirtyProfiles.remove(it.first) }
            profiles.size
        }.getOrElse {
            warning("Failed to batch save warehouse profiles - ${it.message}")
            if (force) {
                profiles.count { (uuid, _) -> saveProfile(uuid, true) }
            } else {
                0
            }
        }
    }

    fun savePage(owner: UUID, page: Int, force: Boolean = false): Boolean {
        if (!available) {
            return false
        }
        val key = PageKey(owner, page)
        if (!force && key !in dirtyPages) {
            return true
        }
        val pageData = warehouseCache[key] ?: return true
        return DebugLogger.timed("savePage owner=$owner, page=$page") {
            runCatching {
                pageData.updatedAt = System.currentTimeMillis()
                repository.savePage(pageData)
                dirtyPages.remove(key)
                true
            }.getOrElse {
                warning("Failed to save warehouse page ${owner}:$page - ${it.message}")
                false
            }
        }
    }

    fun saveOwnerPages(owner: UUID, force: Boolean = false): Int {
        if (!available) {
            return 0
        }
        val pages = dirtyPages
            .filter { it.owner == owner }
            .mapNotNull { key -> warehouseCache[key]?.let { key to it } }
        if (pages.isEmpty()) {
            return 0
        }
        return DebugLogger.timed("saveOwnerPages owner=$owner, count=${pages.size}") {
            runCatching {
                pages.forEach { (_, pageData) -> pageData.updatedAt = System.currentTimeMillis() }
                repository.savePages(pages.map { it.second })
                pages.forEach { (key, _) -> dirtyPages.remove(key) }
                pages.size
            }.getOrElse {
                warning("Failed to batch save warehouse pages for $owner - ${it.message}")
                if (force) {
                    pages.count { (key, _) -> savePage(key.owner, key.page, true) }
                } else {
                    0
                }
            }
        }
    }

    fun saveDirtyPages(force: Boolean = false): Int {
        if (!available) {
            return 0
        }
        val pages = dirtyPages
            .toList()
            .mapNotNull { key -> warehouseCache[key]?.let { key to it } }
        if (pages.isEmpty()) {
            return 0
        }
        return DebugLogger.timed("saveDirtyPages count=${pages.size}") {
            runCatching {
                pages.forEach { (_, pageData) -> pageData.updatedAt = System.currentTimeMillis() }
                repository.savePages(pages.map { it.second })
                pages.forEach { (key, _) -> dirtyPages.remove(key) }
                pages.size
            }.getOrElse {
                warning("Failed to batch save warehouse pages - ${it.message}")
                if (force) {
                    pages.count { (key, _) -> savePage(key.owner, key.page, true) }
                } else {
                    0
                }
            }
        }
    }

    fun isPageAccessible(player: Player, page: Int): Boolean {
        return page in 1..getAccessiblePages(player)
    }

    fun isInspectPageAccessible(profile: WarehouseProfile, page: Int): Boolean {
        return page in 1..profile.maxPages.coerceAtMost(PluginConfig.maxPages)
    }

    fun isItemAllowed(itemStack: ItemStack): Boolean {
        val typeName = itemStack.type.name.uppercase()
        if (typeName in PluginConfig.blacklist) {
            return false
        }
        val meta = itemStack.itemMeta
        // 检查物品显示名称，去色后忽略大小写匹配
        val displayName = meta?.displayName
        if (displayName != null) {
            val plain = ChatColor.stripColor(displayName).orEmpty().trim().uppercase()
            if (plain.isNotEmpty() && plain in PluginConfig.blacklist) {
                return false
            }
        }
        // 检查物品 Lore，任意一行去色后匹配黑名单即拦截
        val lore = meta?.lore
        if (lore != null) {
            val blacklist = PluginConfig.blacklist
            for (line in lore) {
                val plain = ChatColor.stripColor(line).orEmpty().trim().uppercase()
                if (plain.isNotEmpty() && plain in blacklist) {
                    return false
                }
            }
        }
        if (!PluginConfig.allowShulkerBox && typeName.endsWith("SHULKER_BOX")) {
            return false
        }
        return true
    }

    fun normalizePageRemark(raw: String): String {
        val plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', raw)).orEmpty()
        return plain.replace(Regex("\\s+"), " ").trim().take(MAX_PAGE_REMARK_LENGTH)
    }

    /** 判断页面是否接受该物品（基于分类规则）。无分类的页面接受任何物品。 */
    private fun isPageAcceptItem(
        owner: UUID,
        page: Int,
        itemStack: ItemStack,
        categories: Map<String, PluginConfig.WarehouseCategory>,
    ): Boolean {
        val categoryName = getPageCategory(owner, page)
        if (categoryName.isBlank()) return true
        val category = categories[categoryName] ?: return true
        return PluginConfig.matchCategory(itemStack, category)
    }

    private fun storeIntoPage(
        owner: UUID,
        page: Int,
        template: ItemStack,
        amount: Int,
        size: Int,
        mergeOnly: Boolean,
    ): Int {
        if (amount <= 0) {
            return 0
        }
        val pageData = getOrLoadPage(owner, page)
        val limit = size.coerceIn(0, WarehousePageData.SLOT_COUNT)
        var remaining = amount
        var changed = false
        synchronized(pageData.lock) {
            for (slot in 0 until limit) {
                val current = pageData.contents[slot]
                if (mergeOnly) {
                    if (current == null || !current.isSimilar(template) || current.amount >= current.maxStackSize) {
                        continue
                    }
                    val moved = minOf(remaining, current.maxStackSize - current.amount)
                    if (moved <= 0) {
                        continue
                    }
                    current.amount += moved
                    remaining -= moved
                    changed = true
                } else {
                    if (current != null) {
                        continue
                    }
                    val moved = minOf(remaining, template.maxStackSize)
                    if (moved <= 0) {
                        break
                    }
                    pageData.contents[slot] = template.clone().apply { this.amount = moved }
                    remaining -= moved
                    changed = true
                }
                if (remaining <= 0) {
                    break
                }
            }
            if (changed) {
                pageData.updatedAt = System.currentTimeMillis()
            }
        }
        if (changed) {
            markDirty(owner, page)
        }
        return remaining
    }

    private data class PageKey(val owner: UUID, val page: Int)

    private const val MAX_PAGE_REMARK_LENGTH = 32

    /** 清理指定玩家的所有缓存条目，防止离线后内存泄漏。应在保存完数据后调用。 */
    fun evictOwner(uuid: UUID) {
        // 只有当脏数据已全部落盘后才清除缓存和脏标记，否则保留脏标记让下次自动保存兜底。
        val hasDirtyPages = dirtyPages.any { it.owner == uuid }
        val hasDirtyProfile = uuid in dirtyProfiles
        if (hasDirtyPages || hasDirtyProfile) {
            warning("evictOwner $uuid: skipping dirty data cleanup (dirtyPages=$hasDirtyPages, dirtyProfile=$hasDirtyProfile), will retry on next auto-save.")
            return
        }
        warehouseCache.keys.removeIf { it.owner == uuid }
        profileCache.remove(uuid)
    }
}
