package com.minimalflow.launcher.core.apps

import com.minimalflow.launcher.core.data.AliasDao
import com.minimalflow.launcher.core.data.EntityMappers.toConfiguration
import com.minimalflow.launcher.core.data.EntityMappers.toEntity
import com.minimalflow.launcher.core.data.EntityMappers.toModel
import com.minimalflow.launcher.core.data.FavoriteAppEntity
import com.minimalflow.launcher.core.data.FavoriteDao
import com.minimalflow.launcher.core.data.HiddenAppEntity
import com.minimalflow.launcher.core.data.HiddenDao
import com.minimalflow.launcher.core.data.LauncherSettingsDao
import com.minimalflow.launcher.core.model.AliasLimits
import com.minimalflow.launcher.core.model.AppAlias
import com.minimalflow.launcher.core.model.AppBadge
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.FavoriteApp
import com.minimalflow.launcher.core.model.FavoriteLimits
import com.minimalflow.launcher.core.model.HiddenApp
import com.minimalflow.launcher.core.model.IconSource
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.SortMode
import com.minimalflow.launcher.core.privacy.UsageRecord
import com.minimalflow.launcher.core.privacy.UsageTrackingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app list the whole launcher reads from.
 *
 * Discovery (a snapshot in memory), the local database (favourites, hidden apps,
 * aliases, launch counters) and the settings (sorting, visibility) are combined
 * into a single [AppListEntry] list. Everything downstream - search, widgets,
 * the context menu, the alphabet rail - consumes that one stream, so there is
 * exactly one definition of "the apps the user can see".
 */
@Singleton
class AppRepository @Inject constructor(
    private val discoveryService: AppDiscoveryService,
    private val packageChangeObserver: AppPackageChangeObserver,
    private val favoriteDao: FavoriteDao,
    private val hiddenDao: HiddenDao,
    private val aliasDao: AliasDao,
    private val settingsDao: LauncherSettingsDao,
    private val usageTrackingRepository: UsageTrackingRepository,
    private val sortingService: AppSortingService,
    private val appScope: CoroutineScope,
) {

    private val refreshTrigger = MutableStateFlow(0)
    private val writeLock = Mutex()

    init {
        // The observer is debounced, so installing or updating an app results in
        // exactly one re-read of the package manager rather than one per broadcast.
        appScope.launch {
            packageChangeObserver.observe().collect { refresh() }
        }
    }

    val configuration: StateFlow<LauncherConfiguration> = settingsDao.observe()
        .map { it?.toConfiguration() ?: LauncherConfiguration() }
        .distinctUntilChanged()
        .stateIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = LauncherConfiguration(),
        )

    private val discoveredApps: StateFlow<List<AppListEntry>> = refreshTrigger
        .map { discoveryService.loadInstalledApps() }
        .map { installed -> installed.map { AppListEntry(app = it) } }
        .stateIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    private val localData: Flow<LocalData> = combine(
        favoriteDao.observeAll().map { rows -> rows.map { it.toModel() } },
        hiddenDao.observeAll().map { rows -> rows.map { it.toModel() } },
        aliasDao.observeAll().map { rows -> rows.map { it.toModel() } },
        usageTrackingRepository.records,
    ) { favorites, hidden, aliases, usage -> LocalData(favorites, hidden, aliases, usage) }

    /** Every installed app, decorated with the user's local customisations. */
    val entries: StateFlow<List<AppListEntry>> = combine(
        discoveredApps,
        localData,
        configuration,
    ) { discovered, local, config -> buildEntries(discovered, local, config) }
        .stateIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    /** Apps the home screen may show, in the configured order. */
    val visibleApps: Flow<List<AppListEntry>> = entries.combine(configuration) { list, config ->
        sortingService.sort(
            AppVisibilityPolicy.visibleEntries(list, hiddenKeysOf(list), config),
            effectiveSortMode(config),
        )
    }

    /** Everything search looks at. Hidden apps stay searchable on purpose. */
    val searchableApps: Flow<List<AppListEntry>> = entries.combine(configuration) { list, config ->
        AppVisibilityPolicy.searchableEntries(list, hiddenKeysOf(list), config)
    }

    /** Favourites in display order, resolved against the installed app list. */
    val favorites: Flow<List<AppListEntry>> = combine(
        entries,
        favoriteDao.observeAll().map { rows -> rows.map { it.toModel() } },
    ) { list, favorites -> resolveFavorites(list, favorites) }

    /** Hidden apps, including the ones the user hid that are no longer installed. */
    val hiddenApps: Flow<List<HiddenAppSummary>> = combine(
        entries,
        hiddenDao.observeAll().map { rows -> rows.map { it.toModel() } },
    ) { list, hidden ->
        val index = AppIndex(list)
        hidden.map { hiddenApp ->
            val entry = index.find(hiddenApp.key)
            HiddenAppSummary(
                key = hiddenApp.key,
                label = entry?.displayLabel ?: hiddenApp.key.packageName,
                entry = entry,
                hiddenAt = hiddenApp.hiddenAt,
                isInstalled = entry != null,
            )
        }.sortedBy { it.label.lowercase() }
    }

    val aliases: Flow<List<AppAlias>> = aliasDao.observeAll()
        .map { rows -> rows.map { it.toModel() } }

    val favoriteOrder: Flow<List<AppKey>> = favoriteDao.observeAll()
        .map { rows -> rows.map { it.toModel() } }
        .map { favorites -> favorites.sortedBy { it.displayOrder }.map { it.key } }

    // ------------------------------------------------------------- mutations

    suspend fun refresh() {
        refreshTrigger.value += 1
    }

    suspend fun addFavorite(key: AppKey, maximum: Int = FavoriteLimits.DEFAULT_MAXIMUM) {
        writeLock.withLock {
            if (favoriteDao.find(key.packageName, key.userSerial) != null) return@withLock
            val nextOrder = favoriteDao.maxOrder() + 1
            if (nextOrder >= FavoriteLimits.clamp(maximum)) return@withLock
            favoriteDao.upsert(
                FavoriteApp(
                    key = key,
                    displayOrder = nextOrder,
                    addedAt = System.currentTimeMillis(),
                ).toEntity(),
            )
        }
    }

    suspend fun removeFavorite(key: AppKey) {
        writeLock.withLock {
            favoriteDao.delete(key.packageName, key.userSerial)
            // Re-pack the remaining rows so the order indexes stay dense; the
            // `+1` the next add performs depends on it.
            favoriteDao.replaceAll(favoriteDao.getAll().sortedBy { it.displayOrder })
        }
    }

    suspend fun setFavoriteOrder(orderedKeys: List<AppKey>) {
        writeLock.withLock {
            val existing = favoriteDao.getAll().associateBy { it.storageKey() }
            val now = System.currentTimeMillis()
            favoriteDao.replaceAll(
                orderedKeys.mapIndexedNotNull { index, key ->
                    existing[key.storageKey()]
                        ?.toModel()
                        ?.toEntity()
                        ?.copy(displayOrder = index, addedAt = now)
                },
            )
        }
    }
    suspend fun hideApp(key: AppKey) {
        hiddenDao.upsert(key.toHiddenEntity())
    }

    suspend fun restoreApp(key: AppKey) {
        hiddenDao.delete(key.packageName, key.userSerial)
    }

    suspend fun setAllHidden(keys: Set<AppKey>) {
        writeLock.withLock {
            hiddenDao.clear()
            val now = System.currentTimeMillis()
            keys.forEach { key -> hiddenDao.upsert(key.toHiddenEntity(now)) }
        }
    }

    /** Creates, updates or - when both values are empty - removes the override. */
    suspend fun saveAlias(key: AppKey, customLabel: String?, searchKeywords: List<String>) {
        val sanitised = AliasLimits.sanitise(customLabel, searchKeywords)
        if (sanitised.customLabel == null && sanitised.searchKeywords.isEmpty()) {
            aliasDao.delete(key.packageName, key.userSerial)
            return
        }
        aliasDao.upsert(
            AppAlias(
                key = key,
                customLabel = sanitised.customLabel,
                searchKeywords = sanitised.searchKeywords,
                updatedAt = System.currentTimeMillis(),
            ).toEntity(),
        )
    }

    suspend fun clearAlias(key: AppKey) {
        aliasDao.delete(key.packageName, key.userSerial)
    }

    suspend fun recordLaunch(key: AppKey) {
        usageTrackingRepository.recordLaunch(key)
    }

    suspend fun findEntry(key: AppKey): AppListEntry? =
        AppIndex(entries.first()).find(key)

    // --------------------------------------------------------------- internals

    private fun buildEntries(
        discovered: List<AppListEntry>,
        local: LocalData,
        config: LauncherConfiguration,
    ): List<AppListEntry> {
        val favoriteKeys = local.favorites.map { it.key }.toSet()
        val hiddenKeys = local.hidden.map { it.key }.toSet()
        val aliasesByExactKey = local.aliases.associateBy { it.key.storageKey() }
        val aliasesByPackage = local.aliases.filter { it.key.isPackageWide }
            .associateBy { it.key.packageName }
        val iconOverrides = config.iconConfig.perAppOverrides
        val trackUsage = config.privacy.usageTrackingEnabled

        return discovered.map { entry ->
            val key = entry.key
            val storageKey = key.storageKey()
            val alias = aliasesByExactKey[storageKey] ?: aliasesByPackage[key.packageName]
            val usage = local.usage[storageKey] ?: local.usage[key.packageName]
            val override = iconOverrides[storageKey] ?: iconOverrides[key.packageName]
            val isFavorite = AppVisibilityPolicy.isFavorite(key, favoriteKeys)
            val isHidden = AppVisibilityPolicy.isHidden(key, hiddenKeys)

            val badge = when {
                isHidden -> AppBadge.HIDDEN
                isFavorite -> AppBadge.FAVORITE
                entry.app.isWorkProfile -> AppBadge.WORK_PROFILE
                override != null -> AppBadge.CUSTOM_ICON
                alias?.cleanedLabel() != null -> AppBadge.CUSTOM_LABEL
                else -> AppBadge.NONE
            }

            entry.copy(
                customLabel = alias?.cleanedLabel(),
                searchKeywords = alias?.normalisedKeywords().orEmpty(),
                iconSource = override?.let(::parseIconSource),
                isFavorite = isFavorite,
                isHidden = isHidden,
                badge = badge,
                launchCount = if (trackUsage) usage?.launchCount ?: 0 else 0,
                lastLaunchTime = if (trackUsage) usage?.lastLaunchAt ?: 0L else 0L,
            )
        }
    }

    private fun resolveFavorites(
        list: List<AppListEntry>,
        favorites: List<FavoriteApp>,
    ): List<AppListEntry> {
        val index = AppIndex(list)
        return favorites.sortedBy { it.displayOrder }.mapNotNull { favorite ->
            index.find(favorite.key)?.copy(
                isFavorite = true,
                badge = AppBadge.FAVORITE,
            )
        }
    }

    private fun hiddenKeysOf(list: List<AppListEntry>): Set<AppKey> =
        list.filter { it.isHidden }.map { it.key }.toSet()

    /**
     * Icon overrides are stored as one string so they survive inside the settings
     * JSON: a content URI, or `pack:com.example.pack/ClassName`.
     */
    private fun parseIconSource(value: String): IconSource? = when {
        value.startsWith(ICON_SOURCE_PACK_PREFIX) -> {
            val reference = value.removePrefix(ICON_SOURCE_PACK_PREFIX)
            val slash = reference.indexOf('/')
            if (slash <= 0) {
                null
            } else {
                IconSource.FromPack(
                    packPackage = reference.substring(0, slash),
                    packActivityClass = reference.substring(slash + 1).ifEmpty { null },
                )
            }
        }

        value.isNotBlank() -> IconSource.FromImage(value)
        else -> null
    }

    private fun effectiveSortMode(config: LauncherConfiguration): SortMode {
        val mode = config.sortingMode
        val needsUsage = mode == SortMode.RECENT || mode == SortMode.FREQUENT
        return if (needsUsage && !config.privacy.usageTrackingEnabled) SortMode.ALPHABETICAL else mode
    }

    private fun AppKey.toHiddenEntity(now: Long = System.currentTimeMillis()) = HiddenAppEntity(
        packageName = packageName,
        componentName = activityClassName,
        userSerial = userSerial,
        hiddenAt = now,
    )

    /**
     * Storage key of a persisted favourite.
     *
     * The entity is deliberately package-wide (`componentName` is nullable) while
     * discovery always produces a fully qualified component, so the key has to
     * come from the model to stay comparable.
     */
    private fun FavoriteAppEntity.storageKey(): String = toModel().key.storageKey()

    private data class LocalData(
        val favorites: List<FavoriteApp>,
        val hidden: List<HiddenApp>,
        val aliases: List<AppAlias>,
        val usage: Map<String, UsageRecord>,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        const val ICON_SOURCE_PACK_PREFIX = "pack:"
    }
}

/** What the "hidden apps" settings screen shows. */
data class HiddenAppSummary(
    val key: AppKey,
    val label: String,
    val entry: AppListEntry?,
    val hiddenAt: Long,
    val isInstalled: Boolean,
)

/**
 * Lookup helper that understands the two shapes an [AppKey] can take.
 *
 * Favourites, hidden apps and aliases are stored package-wide, while discovery
 * always produces a fully qualified component. Matching therefore has to fall
 * back from "same component" to "same package" or nothing would ever resolve
 * after an app update renames its launcher activity.
 */
internal class AppIndex(entries: List<AppListEntry>) {

    private val byExactKey: Map<String, AppListEntry> =
        entries.associateBy { it.key.storageKey() }

    private val byPackage: Map<String, AppListEntry> = buildMap {
        entries.forEach { entry ->
            if (!containsKey(entry.key.packageName)) put(entry.key.packageName, entry)
        }
    }

    fun find(key: AppKey): AppListEntry? {
        byExactKey[key.storageKey()]?.let { return it }
        if (key.isPackageWide) return byPackage[key.packageName]
        // A package-wide database row, resolved against a fully qualified app.
        if (!key.activityClassName.isNullOrEmpty()) {
            byPackage[key.packageName]?.let { candidate ->
                if (AppKey(candidate.key.packageName, null, candidate.key.userSerial)
                        .matches(key)
                ) {
                    return candidate
                }
            }
        }
        return null
    }
}
