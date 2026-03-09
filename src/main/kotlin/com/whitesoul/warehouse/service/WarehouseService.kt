package com.whitesoul.warehouse.service

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.model.WarehousePageData
import com.whitesoul.warehouse.model.WarehouseProfile
import com.whitesoul.warehouse.storage.PtcObjectWarehouseRepository
import com.whitesoul.warehouse.storage.WarehouseRepository
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
    private val remarkCache = ConcurrentHashMap<PageKey, String>()
    // dirty 集合只记录“需要落盘的键”，真正数据本体仍保存在缓存映射里。
    private val dirtyPages = ConcurrentHashMap.newKeySet<PageKey>()
    private val dirtyProfiles = ConcurrentHashMap.newKeySet<UUID>()
    private val dirtyRemarks = ConcurrentHashMap.newKeySet<PageKey>()

    @Volatile
    private var available = false

    @Volatile
    private var lastError: String? = null

    fun initialize() {
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

    fun shutdown() {
        // 关闭前强制刷一次缓存，确保停服时不会丢最后一批未自动保存的数据。
        saveDirtyPages(force = true)
        saveDirtyProfiles(force = true)
        saveDirtyPageRemarks(force = true)
        repository.shutdown()
        available = false
    }

    fun reload() {
        // reload 先保存后清空，避免切配置时把旧缓存直接丢掉。
        saveDirtyPages(force = true)
        saveDirtyProfiles(force = true)
        saveDirtyPageRemarks(force = true)
        warehouseCache.clear()
        profileCache.clear()
        remarkCache.clear()
        dirtyPages.clear()
        dirtyProfiles.clear()
        dirtyRemarks.clear()
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
            if (available) repository.loadPage(owner, page) ?: WarehousePageData(owner, page) else WarehousePageData(owner, page)
        }
    }

    fun snapshotPageContents(owner: UUID, page: Int): Array<ItemStack?> {
        return getOrLoadPage(owner, page).snapshotContents()
    }

    fun getPageUsedSlots(owner: UUID, page: Int, activeSize: Int): Int {
        return getOrLoadPage(owner, page).countUsedSlots(activeSize)
    }

    fun getPageRemark(owner: UUID, page: Int): String {
        val key = PageKey(owner, page)
        return remarkCache.computeIfAbsent(key) {
            if (available) repository.loadPageRemark(owner, page).orEmpty() else ""
        }
    }

    fun updatePageRemark(owner: UUID, page: Int, remark: String): String {
        val normalized = normalizePageRemark(remark)
        val key = PageKey(owner, page)
        remarkCache[key] = normalized
        dirtyRemarks += key
        return normalized
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
            return StoreItemResult(0, normalized)
        }
        var remainingAmount = normalized.amount
        val accessiblePages = getAccessiblePages(player)
        // 先补满已有堆叠，再尝试占用空格，能最大化压缩仓库空间。
        for (page in 1..accessiblePages) {
            remainingAmount = storeIntoPage(player.uniqueId, page, normalized, remainingAmount, size, mergeOnly = true)
            if (remainingAmount <= 0) {
                return StoreItemResult(normalized.amount, null)
            }
        }
        for (page in 1..accessiblePages) {
            remainingAmount = storeIntoPage(player.uniqueId, page, normalized, remainingAmount, size, mergeOnly = false)
            if (remainingAmount <= 0) {
                return StoreItemResult(normalized.amount, null)
            }
        }
        val storedAmount = normalized.amount - remainingAmount
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
        val uuids = if (force) dirtyProfiles.toList() else dirtyProfiles.toList()
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
            0
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
        return runCatching {
            pageData.updatedAt = System.currentTimeMillis()
            repository.savePage(pageData)
            dirtyPages.remove(key)
            true
        }.getOrElse {
            warning("Failed to save warehouse page ${owner}:$page - ${it.message}")
            false
        }
    }

    fun savePageRemark(owner: UUID, page: Int, force: Boolean = false): Boolean {
        if (!available) {
            return false
        }
        val key = PageKey(owner, page)
        if (!force && key !in dirtyRemarks) {
            return true
        }
        val remark = remarkCache[key].orEmpty()
        return runCatching {
            if (remark.isBlank()) {
                repository.deletePageRemark(owner, page)
            } else {
                repository.savePageRemark(owner, page, remark)
            }
            dirtyRemarks.remove(key)
            true
        }.getOrElse {
            warning("Failed to save warehouse remark ${owner}:$page - ${it.message}")
            false
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
        return runCatching {
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

    fun saveOwnerPageRemarks(owner: UUID, force: Boolean = false): Int {
        val keys = dirtyRemarks.filter { it.owner == owner }.toList()
        return keys.count { savePageRemark(it.owner, it.page, force || it in dirtyRemarks) }
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
        return runCatching {
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

    fun saveDirtyPageRemarks(force: Boolean = false): Int {
        val keys = dirtyRemarks.toList()
        return keys.count { savePageRemark(it.owner, it.page, force || it in dirtyRemarks) }
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
        if (!PluginConfig.allowShulkerBox && typeName.endsWith("SHULKER_BOX")) {
            return false
        }
        return true
    }

    fun normalizePageRemark(raw: String): String {
        val plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', raw)).orEmpty()
        return plain.replace(Regex("\\s+"), " ").trim().take(MAX_PAGE_REMARK_LENGTH)
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
            markDirty(owner, page)
        }
        return remaining
    }

    private data class PageKey(val owner: UUID, val page: Int)

    private const val MAX_PAGE_REMARK_LENGTH = 32
}
