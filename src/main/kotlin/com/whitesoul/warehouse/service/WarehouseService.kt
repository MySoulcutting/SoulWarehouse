package com.whitesoul.warehouse.service

import com.whitesoul.warehouse.config.PluginConfig
import com.whitesoul.warehouse.model.WarehousePageData
import com.whitesoul.warehouse.model.WarehouseProfile
import com.whitesoul.warehouse.storage.PtcObjectWarehouseRepository
import com.whitesoul.warehouse.storage.WarehouseRepository
import com.whitesoul.warehouse.util.DebugLogger
import com.whitesoul.warehouse.util.cloneOrNull
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.warning
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object WarehouseService {

    data class StoreItemResult(
        val storedAmount: Int,
        val remaining: ItemStack?,
    )

    data class SaveReport(
        val attempted: Int,
        val saved: Int,
        val error: String? = null,
    ) {
        val failed: Int
            get() = attempted - saved

        val successful: Boolean
            get() = failed == 0 && error == null
    }

    data class ReloadResult(
        val successful: Boolean,
        val pageReport: SaveReport,
        val profileReport: SaveReport,
        val error: String? = null,
    )

    private var repository: WarehouseRepository = PtcObjectWarehouseRepository()
    private val warehouseCache = ConcurrentHashMap<PageKey, WarehousePageData>()
    private val profileCache = ConcurrentHashMap<UUID, WarehouseProfile>()
    private val loadedOwners = ConcurrentHashMap.newKeySet<UUID>()
    private val ownerLoadErrors = ConcurrentHashMap<UUID, String>()
    // 版本化脏标记确保保存期间产生的新修改不会被旧保存结果清除。
    private val dirtyPages = ConcurrentHashMap<PageKey, Long>()
    private val dirtyProfiles = ConcurrentHashMap<UUID, Long>()
    private val changeSequence = AtomicLong()
    private val repositoryLock = Any()
    private val lifecycleLock = Any()

    @Volatile
    private var storageState = StorageState.STOPPED

    @Volatile
    private var lastError: String? = null

    fun initialize() {
        synchronized(lifecycleLock) {
            storageState = StorageState.RELOADING
        }
        DebugLogger.timed("initialize 数据库连接") {
            synchronized(repositoryLock) {
                val candidate = PtcObjectWarehouseRepository()
                runCatching {
                    candidate.initialize()
                    repository.shutdown()
                    repository = candidate
                    lastError = null
                    synchronized(lifecycleLock) {
                        storageState = StorageState.RUNNING
                    }
                }.onFailure {
                    candidate.shutdown()
                    lastError = it.message
                    synchronized(lifecycleLock) {
                        storageState = StorageState.STOPPED
                    }
                    warning("Failed to initialize SoulWarehouse storage: ${it.message}")
                    it.printStackTrace()
                }
            }
        }
    }

    fun shutdown() {
        synchronized(lifecycleLock) {
            if (storageState == StorageState.STOPPED) {
                return
            }
            storageState = StorageState.SHUTTING_DOWN
        }
        synchronized(repositoryLock) {
            DebugLogger.timed("shutdown 保存全部缓存") {
                val pageReport = saveDirtyPagesLocked(force = true)
                val profileReport = saveDirtyProfilesLocked(force = true)
                if (!pageReport.successful || !profileReport.successful || dirtyPages.isNotEmpty() || dirtyProfiles.isNotEmpty()) {
                    warning("Shutdown save incomplete! ${pageReport.failed} pages and ${profileReport.failed} profiles failed. Data may be lost.")
                } else {
                    DebugLogger.log { "shutdown 保存完成: pages=${pageReport.saved}, profiles=${profileReport.saved}" }
                }
            }
            repository.shutdown()
            synchronized(lifecycleLock) {
                storageState = StorageState.STOPPED
            }
        }
    }

    fun reload(reloadConfiguration: () -> Unit): ReloadResult {
        synchronized(lifecycleLock) {
            if (storageState != StorageState.RUNNING) {
                return ReloadResult(false, EMPTY_SAVE_REPORT, EMPTY_SAVE_REPORT, "Storage is not running.")
            }
            storageState = StorageState.RELOADING
        }
        synchronized(repositoryLock) {
            val pageReport = saveDirtyPagesLocked(force = true)
            val profileReport = saveDirtyProfilesLocked(force = true)
            if (!pageReport.successful || !profileReport.successful || dirtyPages.isNotEmpty() || dirtyProfiles.isNotEmpty()) {
                val error = "Reload aborted because cached data could not be saved."
                lastError = error
                synchronized(lifecycleLock) {
                    storageState = StorageState.RUNNING
                }
                return ReloadResult(false, pageReport, profileReport, error)
            }

            val candidate = PtcObjectWarehouseRepository()
            return runCatching {
                reloadConfiguration()
                candidate.initialize()
                val previous = repository
                repository = candidate
                previous.shutdown()
                warehouseCache.clear()
                profileCache.clear()
                loadedOwners.clear()
                ownerLoadErrors.clear()
                dirtyPages.clear()
                dirtyProfiles.clear()
                lastError = null
                synchronized(lifecycleLock) {
                    storageState = StorageState.RUNNING
                }
                ReloadResult(true, pageReport, profileReport)
            }.getOrElse {
                candidate.shutdown()
                val error = it.message ?: it.javaClass.simpleName
                lastError = error
                synchronized(lifecycleLock) {
                    storageState = StorageState.RUNNING
                }
                warning("Failed to reload SoulWarehouse storage: $error")
                ReloadResult(false, pageReport, profileReport, error)
            }
        }
    }

    fun isAvailable(): Boolean {
        return storageState == StorageState.RUNNING
    }

    fun getLastError(): String? {
        return lastError
    }

    fun isOwnerLoaded(uuid: UUID): Boolean {
        return storageState == StorageState.RUNNING && uuid in loadedOwners
    }

    fun getOwnerLoadError(uuid: UUID): String? {
        return ownerLoadErrors[uuid]
    }

    fun getAccessiblePages(player: Player): Int {
        return getProfile(player).maxPages.coerceIn(1, PluginConfig.maxPages)
    }

    fun touchProfile(player: Player): WarehouseProfile {
        return synchronized(lifecycleLock) {
            check(storageState == StorageState.RUNNING && player.uniqueId in loadedOwners) {
                "Warehouse owner ${player.uniqueId} is not loaded."
            }
            val now = System.currentTimeMillis()
            val profile = profileCache[player.uniqueId] ?: WarehouseProfile(
                uuid = player.uniqueId,
                name = player.name,
                maxPages = PluginConfig.defaultPages,
                createdAt = now,
            ).also { profileCache[player.uniqueId] = it }
            synchronized(profile.lock) {
                profile.name = normalizeProfileName(player.name)
                profile.updatedAt = now
                profile.lastOpenAt = now
            }
            markProfileDirty(player.uniqueId)
            profile.snapshot()
        }
    }

    fun setMaxPages(uuid: UUID, pages: Int): Int {
        return updateProfile(uuid) { profile ->
            profile.maxPages = pages.coerceIn(1, PluginConfig.maxPages)
        }
    }

    fun addMaxPages(uuid: UUID, amount: Int): Int {
        return updateProfile(uuid) { profile ->
            profile.maxPages = (profile.maxPages + amount).coerceIn(1, PluginConfig.maxPages)
        }
    }

    fun removeMaxPages(uuid: UUID, amount: Int): Int {
        return addMaxPages(uuid, -amount)
    }

    fun toggleAutoPickup(player: Player): Boolean {
        var newState = false
        updateProfile(player.uniqueId) { profile ->
            profile.autoPickup = !profile.autoPickup
            newState = profile.autoPickup
        }
        return newState
    }

    fun isAutoPickup(player: Player): Boolean {
        val profile = profileCache[player.uniqueId] ?: return false
        synchronized(profile.lock) {
            return profile.autoPickup
        }
    }

    fun getProfile(player: OfflinePlayer): WarehouseProfile {
        return getProfileByUuid(player.uniqueId, player.name ?: player.uniqueId.toString())
    }

    fun getProfileByUuid(uuid: UUID, fallbackName: String = uuid.toString()): WarehouseProfile {
        synchronized(lifecycleLock) {
            return profileCache[uuid]?.snapshot() ?: WarehouseProfile(
                uuid = uuid,
                name = normalizeProfileName(fallbackName),
                maxPages = PluginConfig.defaultPages,
            )
        }
    }

    private fun getCachedPage(owner: UUID, page: Int): WarehousePageData {
        check(owner in loadedOwners) { "Warehouse owner $owner is not loaded." }
        val key = PageKey(owner, page)
        return warehouseCache.computeIfAbsent(key) {
            WarehousePageData(owner, page)
        }
    }

    /**
     * 预热指定玩家的 profile 和所有可访问页面到缓存。
     * 应在异步线程调用，避免阻塞主线程。
     */
    fun preloadOwner(uuid: UUID, fallbackName: String = uuid.toString()): Boolean {
        if (uuid in loadedOwners) return true
        if (Bukkit.isPrimaryThread()) {
            warning("Refusing to load warehouse owner $uuid on the Paper main thread.")
            return false
        }
        return DebugLogger.timed("preloadOwner uuid=$uuid") {
            synchronized(repositoryLock) {
                if (storageState != StorageState.RUNNING) {
                    return@synchronized false
                }
                if (uuid in loadedOwners) {
                    return@synchronized true
                }
                runCatching {
                    val profile = repository.loadProfile(uuid) ?: WarehouseProfile(
                        uuid = uuid,
                        name = normalizeProfileName(fallbackName),
                        maxPages = PluginConfig.defaultPages,
                    )
                    synchronized(profile.lock) {
                        profile.name = normalizeProfileName(profile.name.ifBlank { fallbackName })
                        profile.maxPages = profile.maxPages.coerceIn(1, PluginConfig.maxPages)
                    }
                    val pages = (1..PluginConfig.maxPages).associateWith { page ->
                        repository.loadPage(uuid, page) ?: WarehousePageData(uuid, page)
                    }
                    profileCache[uuid] = profile
                    pages.forEach { (page, data) -> warehouseCache[PageKey(uuid, page)] = data }
                    ownerLoadErrors.remove(uuid)
                    loadedOwners += uuid
                    true
                }.getOrElse {
                    val error = it.message ?: it.javaClass.simpleName
                    ownerLoadErrors[uuid] = error
                    warning("Failed to load warehouse owner $uuid: $error")
                    false
                }
            }
        }
    }

    fun snapshotPageContents(owner: UUID, page: Int): Array<ItemStack?> {
        synchronized(lifecycleLock) {
            return getCachedPage(owner, page).snapshotContents()
        }
    }

    fun getPageUsedSlots(owner: UUID, page: Int, activeSize: Int): Int {
        synchronized(lifecycleLock) {
            return getCachedPage(owner, page).countUsedSlots(activeSize)
        }
    }

    fun getPageRemark(owner: UUID, page: Int): String {
        synchronized(lifecycleLock) {
            return getCachedPage(owner, page).getRemark()
        }
    }

    fun updatePageRemark(owner: UUID, page: Int, remark: String): String {
        val normalized = normalizePageRemark(remark)
        synchronized(lifecycleLock) {
            if (storageState != StorageState.RUNNING) return normalized
            val pageData = getCachedPage(owner, page)
            if (pageData.updateRemark(normalized)) {
                markDirty(owner, page)
            }
        }
        return normalized
    }

    fun getPageCategory(owner: UUID, page: Int): String {
        synchronized(lifecycleLock) {
            return getCachedPage(owner, page).getCategory()
        }
    }

    fun updatePageCategory(owner: UUID, page: Int, category: String) {
        val normalized = category.take(MAX_PAGE_CATEGORY_LENGTH)
        synchronized(lifecycleLock) {
            if (storageState != StorageState.RUNNING) return
            val pageData = getCachedPage(owner, page)
            if (pageData.updateCategory(normalized)) {
                markDirty(owner, page)
            }
        }
    }

    fun updatePageContents(owner: UUID, page: Int, contents: Array<ItemStack?>, size: Int = PluginConfig.pageSize): Boolean {
        synchronized(lifecycleLock) {
            if (storageState != StorageState.RUNNING) return false
            val pageData = getCachedPage(owner, page)
            val changed = pageData.replaceContents(contents, size)
            if (changed) {
                markDirty(owner, page)
            }
            return changed
        }
    }

    fun storeItem(player: Player, itemStack: ItemStack, size: Int = PluginConfig.pageSize): StoreItemResult {
        return synchronized(lifecycleLock) {
            val normalized = itemStack.cloneOrNull() ?: return StoreItemResult(0, null)
            val ready = storageState == StorageState.RUNNING && player.uniqueId in loadedOwners
            if (!ready || !isItemAllowed(normalized)) {
                DebugLogger.log { "storeItem 拒绝: player=${player.name}, item=${normalized.type.name}, allowed=${isItemAllowed(normalized)}, ready=$ready" }
                return StoreItemResult(0, normalized)
            }
            val start = System.nanoTime()
            var remainingAmount = normalized.amount
            val accessiblePages = getAccessiblePages(player)
            val categories = PluginConfig.categories

        // 将页面分为两组：有匹配分类的页面优先，无分类的页面兜底。
            val categorizedPages = mutableListOf<Int>()
            val uncategorizedPages = mutableListOf<Int>()
            for (page in 1..accessiblePages) {
                val categoryName = getPageCategory(player.uniqueId, page)
                if (categoryName.isBlank()) {
                    uncategorizedPages += page
                } else {
                    val category = categories[categoryName] ?: continue
                    if (PluginConfig.matchCategory(normalized, category)) {
                        categorizedPages += page
                    }
                    // 有分类但不匹配的页面直接跳过
                }
            }

            // 4 阶段入仓：分类页合并 → 分类页空槽 → 默认页合并 → 默认页空槽
            val orderedPages = listOf(categorizedPages, uncategorizedPages)
            for (pages in orderedPages) {
                for (page in pages) {
                    remainingAmount = storeIntoPage(player.uniqueId, page, normalized, remainingAmount, size, mergeOnly = true)
                    if (remainingAmount <= 0) {
                        val elapsed = (System.nanoTime() - start) / 1_000_000.0
                        DebugLogger.log { "storeItem 完成(合并): player=${player.name}, item=${normalized.type.name}, amount=${normalized.amount}, elapsed=${String.format("%.2f", elapsed)}ms" }
                        return StoreItemResult(normalized.amount, null)
                    }
                }
                for (page in pages) {
                    remainingAmount = storeIntoPage(player.uniqueId, page, normalized, remainingAmount, size, mergeOnly = false)
                    if (remainingAmount <= 0) {
                        val elapsed = (System.nanoTime() - start) / 1_000_000.0
                        DebugLogger.log { "storeItem 完成(新槽): player=${player.name}, item=${normalized.type.name}, amount=${normalized.amount}, elapsed=${String.format("%.2f", elapsed)}ms" }
                        return StoreItemResult(normalized.amount, null)
                    }
                }
            }

            val storedAmount = normalized.amount - remainingAmount
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            DebugLogger.log { "storeItem 部分存入: player=${player.name}, item=${normalized.type.name}, stored=$storedAmount, remaining=$remainingAmount, elapsed=${String.format("%.2f", elapsed)}ms" }
            StoreItemResult(
                storedAmount = storedAmount,
                remaining = normalized.clone().apply { amount = remainingAmount },
            )
        }
    }

    private fun markDirty(owner: UUID, page: Int) {
        dirtyPages[PageKey(owner, page)] = changeSequence.incrementAndGet()
    }

    private fun markProfileDirty(uuid: UUID) {
        dirtyProfiles[uuid] = changeSequence.incrementAndGet()
    }

    fun saveProfile(uuid: UUID, force: Boolean = false): Boolean {
        synchronized(repositoryLock) {
            if (storageState != StorageState.RUNNING) return false
            return saveProfileLocked(uuid, force)
        }
    }

    private fun saveProfileLocked(uuid: UUID, force: Boolean): Boolean {
        val version = dirtyProfiles[uuid]
        if (!force && version == null) return true
        val profile = profileCache[uuid] ?: return version == null
        val target = ProfileSaveTarget(uuid, version, profile.snapshot())
        return runCatching {
            repository.saveProfile(target.data)
            completeProfileSave(target)
            true
        }.getOrElse {
            warning("Failed to save warehouse profile $uuid - ${it.message}")
            false
        }
    }

    fun saveDirtyProfiles(force: Boolean = false): SaveReport {
        synchronized(repositoryLock) {
            if (storageState != StorageState.RUNNING) return UNAVAILABLE_SAVE_REPORT
            return saveDirtyProfilesLocked(force)
        }
    }

    private fun saveDirtyProfilesLocked(force: Boolean): SaveReport {
        val uuids = if (force) profileCache.keys.toList() else dirtyProfiles.keys.toList()
        val targets = uuids.mapNotNull { uuid ->
            val version = dirtyProfiles[uuid]
            val profile = profileCache[uuid] ?: return@mapNotNull null
            if (!force && version == null) return@mapNotNull null
            ProfileSaveTarget(uuid, version, profile.snapshot())
        }
        if (targets.isEmpty()) return SaveReport(uuids.size, 0)
        return runCatching {
            repository.saveProfiles(targets.map { it.data })
            targets.forEach(::completeProfileSave)
            SaveReport(uuids.size, targets.size)
        }.getOrElse { batchError ->
            warning("Failed to batch save warehouse profiles - ${batchError.message}")
            if (!force) return@getOrElse SaveReport(uuids.size, 0)
            var saved = 0
            targets.forEach { target ->
                runCatching {
                    repository.saveProfile(target.data)
                    completeProfileSave(target)
                    saved++
                }.onFailure {
                    warning("Failed to save warehouse profile ${target.uuid} - ${it.message}")
                }
            }
            SaveReport(uuids.size, saved)
        }
    }

    fun savePage(owner: UUID, page: Int, force: Boolean = false): Boolean {
        synchronized(repositoryLock) {
            if (storageState != StorageState.RUNNING) return false
            return savePageLocked(PageKey(owner, page), force)
        }
    }

    private fun savePageLocked(key: PageKey, force: Boolean): Boolean {
        val version = dirtyPages[key]
        if (!force && version == null) return true
        val pageData = warehouseCache[key] ?: return version == null
        val target = PageSaveTarget(key, version, pageData.snapshotForPersistence())
        return DebugLogger.timed("savePage owner=${key.owner}, page=${key.page}") {
            runCatching {
                repository.savePage(target.data)
                completePageSave(target)
                true
            }.getOrElse {
                warning("Failed to save warehouse page ${key.owner}:${key.page} - ${it.message}")
                false
            }
        }
    }

    fun saveOwnerPages(owner: UUID, force: Boolean = false): SaveReport {
        synchronized(repositoryLock) {
            if (storageState != StorageState.RUNNING) return UNAVAILABLE_SAVE_REPORT
            val keys = if (force) {
                warehouseCache.keys.filter { it.owner == owner }
            } else {
                dirtyPages.keys.filter { it.owner == owner }
            }
            return savePagesLocked(keys, force, "owner=$owner")
        }
    }

    fun saveDirtyPages(force: Boolean = false): SaveReport {
        synchronized(repositoryLock) {
            if (storageState != StorageState.RUNNING) return UNAVAILABLE_SAVE_REPORT
            return saveDirtyPagesLocked(force)
        }
    }

    private fun saveDirtyPagesLocked(force: Boolean): SaveReport {
        val keys = if (force) warehouseCache.keys.toList() else dirtyPages.keys.toList()
        return savePagesLocked(keys, force, "all")
    }

    private fun savePagesLocked(keys: List<PageKey>, retryIndividually: Boolean, context: String): SaveReport {
        val targets = keys.mapNotNull { key ->
            val version = dirtyPages[key]
            val pageData = warehouseCache[key] ?: return@mapNotNull null
            if (!retryIndividually && version == null) return@mapNotNull null
            PageSaveTarget(key, version, pageData.snapshotForPersistence())
        }
        if (targets.isEmpty()) return SaveReport(keys.size, 0)
        return DebugLogger.timed("savePages context=$context, count=${targets.size}") {
            runCatching {
                repository.savePages(targets.map { it.data })
                targets.forEach(::completePageSave)
                SaveReport(keys.size, targets.size)
            }.getOrElse { batchError ->
                warning("Failed to batch save warehouse pages ($context) - ${batchError.message}")
                if (!retryIndividually) return@getOrElse SaveReport(keys.size, 0)
                var saved = 0
                targets.forEach { target ->
                    runCatching {
                        repository.savePage(target.data)
                        completePageSave(target)
                        saved++
                    }.onFailure {
                        warning("Failed to save warehouse page ${target.key.owner}:${target.key.page} - ${it.message}")
                    }
                }
                SaveReport(keys.size, saved)
            }
        }
    }

    private fun completePageSave(target: PageSaveTarget) {
        target.version?.let { version -> dirtyPages.remove(target.key, version) }
    }

    private fun completeProfileSave(target: ProfileSaveTarget) {
        target.version?.let { version -> dirtyProfiles.remove(target.uuid, version) }
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

    private fun updateProfile(uuid: UUID, update: (WarehouseProfile) -> Unit): Int {
        synchronized(lifecycleLock) {
            if (storageState != StorageState.RUNNING || uuid !in loadedOwners) {
                return profileCache[uuid]?.snapshot()?.maxPages ?: PluginConfig.defaultPages
            }
            val profile = profileCache[uuid] ?: return PluginConfig.defaultPages
            val result = synchronized(profile.lock) {
                update(profile)
                profile.name = normalizeProfileName(profile.name)
                profile.updatedAt = System.currentTimeMillis()
                profile.maxPages
            }
            markProfileDirty(uuid)
            return result
        }
    }

    private fun normalizeProfileName(name: String): String {
        return name.take(MAX_PROFILE_NAME_LENGTH)
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
        val pageData = getCachedPage(owner, page)
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
                pageData.markUpdated()
            }
        }
        if (changed) {
            markDirty(owner, page)
        }
        return remaining
    }

    private data class PageKey(val owner: UUID, val page: Int)

    private data class PageSaveTarget(
        val key: PageKey,
        val version: Long?,
        val data: WarehousePageData,
    )

    private data class ProfileSaveTarget(
        val uuid: UUID,
        val version: Long?,
        val data: WarehouseProfile,
    )

    private enum class StorageState {
        STOPPED,
        RUNNING,
        RELOADING,
        SHUTTING_DOWN,
    }

    /** 清理指定玩家的所有缓存条目，防止离线后内存泄漏。应在保存完数据后调用。 */
    fun evictOwner(uuid: UUID) {
        synchronized(lifecycleLock) {
            // 只有当脏数据已全部落盘后才清除缓存，否则保留数据让下次自动保存兜底。
            val hasDirtyPages = dirtyPages.keys.any { it.owner == uuid }
            val hasDirtyProfile = dirtyProfiles.containsKey(uuid)
            if (hasDirtyPages || hasDirtyProfile) {
                warning("evictOwner $uuid: skipping dirty data cleanup (dirtyPages=$hasDirtyPages, dirtyProfile=$hasDirtyProfile), will retry on next auto-save.")
                return
            }
            warehouseCache.keys.removeIf { it.owner == uuid }
            profileCache.remove(uuid)
            loadedOwners.remove(uuid)
            ownerLoadErrors.remove(uuid)
        }
    }

    private val EMPTY_SAVE_REPORT = SaveReport(0, 0)
    private val UNAVAILABLE_SAVE_REPORT = SaveReport(0, 0, "Storage is not running.")
    private const val MAX_PAGE_REMARK_LENGTH = 32
    private const val MAX_PAGE_CATEGORY_LENGTH = 64
    private const val MAX_PROFILE_NAME_LENGTH = 64
}
