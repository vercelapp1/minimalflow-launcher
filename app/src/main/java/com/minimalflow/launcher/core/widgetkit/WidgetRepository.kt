package com.minimalflow.launcher.core.widgetkit

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.util.Log
import com.minimalflow.launcher.core.data.EntityMappers.toEntity
import com.minimalflow.launcher.core.data.EntityMappers.toModel
import com.minimalflow.launcher.core.data.PreferencesRepository
import com.minimalflow.launcher.core.data.WidgetPlacementDao
import com.minimalflow.launcher.core.data.setExtras
import com.minimalflow.launcher.core.model.AvailableWidget
import com.minimalflow.launcher.core.model.WidgetConfig
import com.minimalflow.launcher.core.model.WidgetLimits
import com.minimalflow.launcher.core.model.WidgetPanelPosition
import com.minimalflow.launcher.core.model.WidgetPlacement
import com.minimalflow.launcher.di.Dispatcher
import com.minimalflow.launcher.di.DispatcherKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What happened while adding a widget, in enough detail for the UI to explain it. */
sealed interface AddWidgetResult {
    data class Added(val placement: WidgetPlacement) : AddWidgetResult
    data class NeedsConfiguration(
        val appWidgetId: Int,
        val provider: AppWidgetProviderInfo,
    ) : AddWidgetResult

    data class Rejected(val reason: String) : AddWidgetResult
}

/**
 * The launcher's own record of which widgets it hosts and where they sit.
 *
 * The platform only knows *that* a widget is bound, never where a launcher wants
 * it, so the ordering, requested size and "the user has customised this" flag all
 * live here. Placement is stored in Room rather than in view state so it survives
 * process death, which for a home screen is the whole point.
 */
@Singleton
class WidgetRepository @Inject constructor(
    private val widgetDao: WidgetPlacementDao,
    private val hostRegistry: WidgetHostRegistry,
    private val preferences: PreferencesRepository,
    @Dispatcher(DispatcherKind.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /** Placements in display order. */
    val placements: Flow<List<WidgetPlacement>> = widgetDao.observeAll()
        .map { rows -> rows.map { it.toModel() } }
        .distinctUntilChanged()

    val config: Flow<WidgetConfig> = preferences.configuration
        .map { it.extras.widgetConfig }
        .distinctUntilChanged()

    suspend fun currentPlacements(): List<WidgetPlacement> = widgetDao.getAll().map { it.toModel() }

    // ---------------------------------------------------------------- adding

    /**
     * Completes the pick-and-bind flow.
     *
     * The system picker returns an id that is *not* yet bound to a provider, so the
     * two steps - look the provider up, then bind - have to happen before anything
     * is written. A provider that needs configuration is reported instead of being
     * stored, because Android discards an unconfigured widget id and the user
     * would be left with a row pointing at nothing.
     */
    suspend fun addFromPickResult(
        appWidgetId: Int,
        providerPackage: String,
        providerClass: String,
        widthSp: Int,
        heightDp: Int,
    ): AddWidgetResult {
        val component = android.content.ComponentName(providerPackage, providerClass)
        val provider = hostRegistry.providerForComponent(component)
            ?: return AddWidgetResult.Rejected("That widget provider is no longer installed.")

        if (!hostRegistry.bind(appWidgetId, provider.provider)) {
            return AddWidgetResult.Rejected(
                "Android only lets the current home app host widgets. Set MinimalFlow as the " +
                    "default home app and try again.",
            )
        }

        if (!provider.isConfiguredForUs()) {
            return AddWidgetResult.NeedsConfiguration(appWidgetId, provider)
        }

        val granted = hostRegistry.applySize(appWidgetId, widthSp, heightDp)
        val placement = WidgetPlacement(
            appWidgetId = appWidgetId,
            providerPackage = providerPackage,
            providerClass = providerClass,
            position = widgetDao.getAll().size,
            widthSp = granted.requestedWidth(widthSp),
            heightDp = granted.requestedHeight(heightDp),
            createdAt = System.currentTimeMillis(),
        )
        widgetDao.upsert(placement.toEntity())
        return AddWidgetResult.Added(placement)
    }

    /**
     * Called after the provider's own configuration activity returned successfully.
     *
     * The result code matters: `RESULT_OK` is the only value that means the widget
     * is usable, and anything else means the system already deleted the id.
     */
    suspend fun finishConfiguration(
        appWidgetId: Int,
        configurationResultCode: Int,
        providerPackage: String,
        providerClass: String,
        widthSp: Int,
        heightDp: Int,
    ): AddWidgetResult {
        if (configurationResultCode != android.app.Activity.RESULT_OK) {
            hostRegistry.release(appWidgetId)
            return AddWidgetResult.Rejected("The widget was not configured, so it was not added.")
        }

        val granted = hostRegistry.applySize(appWidgetId, widthSp, heightDp)
        val placement = WidgetPlacement(
            appWidgetId = appWidgetId,
            providerPackage = providerPackage,
            providerClass = providerClass,
            position = widgetDao.getAll().size,
            widthSp = granted.requestedWidth(widthSp),
            heightDp = granted.requestedHeight(heightDp),
            createdAt = System.currentTimeMillis(),
        )
        widgetDao.upsert(placement.toEntity())
        return AddWidgetResult.Added(placement)
    }

    // ------------------------------------------------------------- mutating

    suspend fun remove(placement: WidgetPlacement) = withContext(ioDispatcher) {
        widgetDao.delete(placement.appWidgetId)
        hostRegistry.release(placement.appWidgetId)
    }

    /**
     * Drops a row whose id the platform has already reclaimed.
     *
     * Unlike [remove] this does *not* try to release the id: the platform deleted
     * it, so there is nothing left to release and asking again only logs noise.
     */
    suspend fun forgetIfPresent(appWidgetId: Int) = withContext(ioDispatcher) {
        widgetDao.delete(appWidgetId)
    }

    /** Reorders and repacks [orderedKeys], keeping the platform ids intact. */
    suspend fun setOrder(orderedKeys: List<Int>) = withContext(ioDispatcher) {
        val existing = widgetDao.getAll().associateBy { it.appWidgetId }
        widgetDao.replaceAll(
            orderedKeys.mapIndexedNotNull { index, id ->
                existing[id]?.copy(position = index)
            },
        )
    }

    /**
     * Stores a new requested size.
     *
     * The platform's answer wins: the row keeps what was granted, so the settings
     * screen and the actual widget agree.
     */
    suspend fun resize(appWidgetId: Int, widthSp: Int, heightDp: Int) = withContext(ioDispatcher) {
        val row = widgetDao.find(appWidgetId) ?: return@withContext
        val granted = hostRegistry.applySize(appWidgetId, widthSp, heightDp)
        widgetDao.upsert(
            row.copy(
                widthSp = granted.requestedWidth(widthSp),
                heightDp = granted.requestedHeight(heightDp),
                isCustomised = true,
            ),
        )
    }

    suspend fun markCustomised(appWidgetId: Int, customised: Boolean) = withContext(ioDispatcher) {
        val row = widgetDao.find(appWidgetId) ?: return@withContext
        widgetDao.upsert(row.copy(isCustomised = customised))
    }

    // -------------------------------------------------------------- settings

    suspend fun setPanelPosition(position: WidgetPanelPosition) {
        preferences.setExtras { it.copy(widgetConfig = it.widgetConfig.copy(panelPosition = position)) }
    }

    suspend fun setSpacing(dp: Int) {
        val clamped = WidgetLimits.clampSpacing(dp)
        preferences.setExtras { it.copy(widgetConfig = it.widgetConfig.copy(spacingDp = clamped)) }
    }

    suspend fun setResizeEnabled(enabled: Boolean) {
        preferences.setExtras { it.copy(widgetConfig = it.widgetConfig.copy(resizeEnabled = enabled)) }
    }

    suspend fun setEditHandlesVisible(visible: Boolean) {
        preferences.setExtras {
            it.copy(widgetConfig = it.widgetConfig.copy(editHandlesVisible = visible))
        }
    }

    suspend fun setShowAddButton(show: Boolean) {
        preferences.setExtras { it.copy(widgetConfig = it.widgetConfig.copy(showAddButton = show)) }
    }

    // --------------------------------------------------------------- cleanup

    /**
     * Reconciles the placements table with what the platform still knows.
     *
     * Runs on start-up and after a package change:
     *  * placements whose provider was uninstalled are dropped and their ids
     *    released, because a bound id to a missing provider is dead weight;
     *  * ids the platform still tracks but this app has no row for are released
     *    too, which is what cleans up after a restore from backup.
     */
    suspend fun reconcile() = withContext(ioDispatcher) {
        val stored = widgetDao.getAll()
        if (stored.isEmpty()) return@withContext

        val orphaned = hostRegistry.boundIds() - stored.map { it.appWidgetId }.toSet()
        if (orphaned.isNotEmpty()) {
            Log.i(TAG, "Releasing ${orphaned.size} widget id(s) with no placement row.")
            orphaned.forEach { hostRegistry.release(it) }
        }

        val stillInstalled = stored.filter { row ->
            hostRegistry.providerForComponent(
                android.content.ComponentName(row.providerPackage, row.providerClass),
            ) != null
        }
        if (stillInstalled.size != stored.size) {
            widgetDao.replaceAll(stillInstalled)
        }
    }

    /**
     * The providers the picker should offer.
     *
     * Returns the raw list rather than a decorated copy: whether a provider is
     * already added is a question the UI can answer from [placements], and
     * overloading `isConfigured` to mean "not added yet" would make the field lie.
     */
    suspend fun pickerCandidates(): List<AvailableWidget> = hostRegistry.availableProviders()

    /** True when the device offers any widget at all. */
    suspend fun hasAnyProvider(): Boolean = hostRegistry.installedProviders().isNotEmpty()

    // --------------------------------------------------------------- helpers

    private fun AppWidgetProviderInfo.isConfiguredForUs(): Boolean = configure == null
    private fun android.os.Bundle.requestedWidth(fallback: Int): Int = getInt(
        AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
        WidgetLimits.clampWidth(fallback),
    )

    private fun android.os.Bundle.requestedHeight(fallback: Int): Int = getInt(
        AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
        WidgetLimits.clampHeight(fallback),
    )

    private companion object {
        const val TAG = "WidgetRepo"
    }
}
