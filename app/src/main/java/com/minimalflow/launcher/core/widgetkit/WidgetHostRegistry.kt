package com.minimalflow.launcher.core.widgetkit

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.minimalflow.launcher.core.model.AvailableWidget
import com.minimalflow.launcher.core.model.WidgetLimits
import com.minimalflow.launcher.di.Dispatcher
import com.minimalflow.launcher.di.DispatcherKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single [AppWidgetHost] the launcher uses.
 *
 * The host id is a constant owned by the app rather than a value allocated on first
 * run. The platform uses the id to route every future update to this process, so it
 * has to be the same on every launch; the documented way to guarantee that is a
 * value the app defines itself, which is what the stock launcher does as well.
 * Widget ids are the part that are allocated, and those come from
 * [AppWidgetHost.allocateAppWidgetId].
 *
 * Every call here is defensive about the platform saying no. Widgets are
 * third-party code, `BIND_APPWIDGET` is only granted to the default home app, and
 * providers routinely request sizes they cannot deliver - so a refusal is an
 * expected outcome that the UI reports, not an exception to crash on.
 *
 * The calls that cross a binder are marked [withContext] on the IO dispatcher.
 * [host], [startListening] and [startConfiguration] stay synchronous because the
 * platform calls them from the main thread anyway, through
 * [WidgetHostService] and an activity result.
 */
@Singleton
class WidgetHostRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(DispatcherKind.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val manager: AppWidgetManager? =
        context.getSystemService(Context.APPWIDGET_SERVICE) as? AppWidgetManager

    @Volatile
    private var host: AppWidgetHost? = null

    /** False on builds that do not expose the widget service at all. */
    val isSupported: Boolean get() = manager != null

    /**
     * The host, created and started on first use.
     *
     * Concurrent callers all receive the same instance; `null` means the device
     * refused to give the launcher a host, and the UI hides the widget panel.
     */
    fun host(): AppWidgetHost? {
        host?.let { return it }
        return synchronized(this) {
            host ?: runCatching { AppWidgetHost(context, HOST_ID) }
                .onFailure { error -> Log.e(TAG, "Could not create the widget host", error) }
                .getOrNull()
                ?.also { created ->
                    created.startListening()
                    host = created
                }
        }
    }

    /**
     * Resumes delivery of widget updates.
     *
     * Called from [WidgetHostService] when a provider broadcasts, because the
     * platform stops delivering once the host stops listening and a launcher is
     * not in the foreground to restart it by itself.
     */
    fun startListening() {
        runCatching { host()?.startListening() }
            .onFailure { error -> Log.w(TAG, "Could not start listening for widget updates", error) }
    }

    /** Stops receiving widget updates. Call when the launcher leaves the foreground. */
    fun stopListening() {
        runCatching { host?.stopListening() }
            .onFailure { error -> Log.w(TAG, "Could not stop listening for widget updates", error) }
    }

    /** Forgets the cached host, e.g. after a backup restore replaced the data file. */
    fun invalidate() {
        stopListening()
        synchronized(this) { host = null }
    }

    // ------------------------------------------------------------- allocations

    /**
     * Allocates a widget id bound to this host.
     *
     * The id is meaningless to the user and only valid together with this host, so
     * it is never stored anywhere: the platform is the owner and
     * [AppWidgetHost.getAppWidgetIds] is the only list worth trusting.
     */
    suspend fun allocateWidgetId(): Int? = withContext(ioDispatcher) {
        runCatching { host()?.allocateAppWidgetId() }
            .onFailure { error -> Log.w(TAG, "allocateAppWidgetId failed", error) }
            .getOrNull()
    }

    /** Every id currently bound to this host, whether or not it has a placement row. */
    suspend fun boundIds(): Set<Int> = withContext(ioDispatcher) {
        runCatching { host()?.getAppWidgetIds()?.toSet() }
            .onFailure { error -> Log.w(TAG, "getAppWidgetIds failed", error) }
            .getOrNull()
            .orEmpty()
    }

    // -------------------------------------------------------------- providers

    /**
     * Every widget provider installed on the device, for the picker.
     *
     * `getInstalledProviders()` only returns providers the caller is allowed to
     * host, which is why it is safe to call without any special permission.
     */
    suspend fun installedProviders(): List<AppWidgetProviderInfo> = withContext(ioDispatcher) {
        val appWidgetManager = manager ?: return@withContext emptyList()
        runCatching { appWidgetManager.getInstalledProviders() }
            .onFailure { error -> Log.w(TAG, "Could not list widget providers", error) }
            .getOrNull()
            .orEmpty()
    }

    /** The provider bound to [appWidgetId], or `null` when nothing is bound. */
    suspend fun providerFor(appWidgetId: Int): AppWidgetProviderInfo? = withContext(ioDispatcher) {
        runCatching { manager?.getAppWidgetInfo(appWidgetId) }.getOrNull()
    }

    /**
     * The provider with this component.
     *
     * The platform exposes no lookup by component, so this walks the installed
     * list the picker uses anyway. It is a single cached binder call and only runs
     * while adding or reconciling a widget.
     */
    suspend fun providerForComponent(component: ComponentName): AppWidgetProviderInfo? {
        val match = installedProviders().firstOrNull { it.provider == component }
        if (match != null) return match
        Log.i(TAG, "No installed provider matches $component")
        return null
    }

    /**
     * The intent that opens the system widget picker.
     *
     * `EXTRA_APPWIDGET_ID` has to be present for the picker to hand back an
     * allocated id, and the system expects a negative sentinel to mean "I have not
     * got one yet" - that is [EMPTY_APP_WIDGET_ID]. Passing an id the host already
     * owns skips the user-facing bind confirmation, which is not something a
     * launcher should do on the user's behalf.
     */
    fun pickerIntent(): Intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, EMPTY_APP_WIDGET_ID)
    }

    /**
     * Binds an id to a provider.
     *
     * `false` is the normal, documented outcome for a package that is not the
     * current default home app, and the caller turns it into a message rather than
     * an error.
     */
    suspend fun bind(appWidgetId: Int, provider: ComponentName): Boolean = withContext(ioDispatcher) {
        val appWidgetManager = manager ?: return@withContext false
        runCatching { appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider) }
            .onFailure { error ->
                Log.w(TAG, "bindAppWidgetIdIfAllowed failed for $appWidgetId", error)
            }
            .getOrDefault(false)
    }

    /**
     * Runs the provider's own configuration activity.
     *
     * Android always targets the activity the provider declared in
     * `android:configure`, so MinimalFlow never hosts that flow itself. A provider
     * with no such activity makes this throw, which is how "nothing to configure"
     * is detected rather than assumed.
     */
    fun startConfiguration(activity: Activity, appWidgetId: Int, options: Bundle?): Boolean =
        runCatching {
            host()?.startAppWidgetConfigureActivityForResult(
                activity,
                appWidgetId,
                CONFIGURE_REQUEST_CODE,
                0,
                options,
            )
        }.onFailure { error ->
            Log.w(TAG, "No configuration activity for widget $appWidgetId", error)
        }.isSuccess

    // ------------------------------------------------------------------ sizing

    /**
     * Requests a size and returns the options the platform actually granted.
     *
     * The request is only a suggestion: providers clamp to their own minimums and
     * maximums, and the launcher stores what it got rather than what it asked for.
     */
    suspend fun applySize(appWidgetId: Int, widthSp: Int, heightDp: Int): Bundle =
        withContext(ioDispatcher) {
            val appWidgetManager = manager ?: return@withContext Bundle()
            val requested = sizeOptions(widthSp, heightDp)
            runCatching { appWidgetManager.updateAppWidgetOptions(appWidgetId, requested) }
                .onFailure { error ->
                    Log.w(TAG, "updateAppWidgetOptions refused for $appWidgetId", error)
                }
            currentSize(appWidgetId) ?: requested
        }

    /**
     * Builds the options bundle a launcher sends to request a widget size.
     *
     * Only public option keys are used. The platform also understands a
     * resize-mode key to preserve a widget's aspect ratio, but it is not part of
     * the public API, and a launcher must not reach for undocumented constants.
     */
    fun sizeOptions(widthSp: Int, heightDp: Int): Bundle = Bundle().apply {
        val width = WidgetLimits.clampWidth(widthSp)
        val height = WidgetLimits.clampHeight(heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
    }

    /** The size the platform currently granted, or `null` when it will not say. */
    suspend fun currentSize(appWidgetId: Int): Bundle? = withContext(ioDispatcher) {
        runCatching { manager?.getAppWidgetOptions(appWidgetId) }.getOrNull()
    }

    // --------------------------------------------------------------- lifecycle

    /** Releases an id. Always call this when a widget is removed. */
    suspend fun release(appWidgetId: Int) = withContext(ioDispatcher) {
        val appWidgetHost = host ?: return@withContext
        runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
            .onFailure { error -> Log.w(TAG, "deleteAppWidgetId failed for $appWidgetId", error) }
    }

    /**
     * A hosted widget's inflated view.
     *
     * `createView` is the only way to get one, and it must be called on the main
     * thread because it inflates the provider's layout.
     */
    fun createView(appWidgetId: Int, provider: AppWidgetProviderInfo) = host()
        ?.createView(context, appWidgetId, provider)

    /**
     * Every installed provider, presented to the picker.
     *
     * Sorting happens here so the picker and the "add widget" sheet always agree
     * on the order, and so the mapping from provider to model stays next to the
     * place that knows the platform types.
     */
    suspend fun availableProviders(): List<AvailableWidget> {
        val providers = installedProviders()
        return providers
            .map { it.toAvailable() }
            .sortedBy { it.label.lowercase() }
    }

    // --------------------------------------------------------------- adapters

    /**
     * Presents a provider to the picker.
     *
     * `minWidth`/`minHeight` are documented in dp, which is the unit a widget grid
     * actually works in, so the model stores them as-is.
     */
    internal fun AppWidgetProviderInfo.toAvailable(): AvailableWidget {
        // Resize bounds and the provider description arrived in API 31. Below that
        // the picker simply shows no maximum, which is the truth: the host cannot
        // know one.
        val supportsResize = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        return AvailableWidget(
            providerPackage = provider.packageName,
            providerClass = provider.className,
            label = runCatching { loadLabel(context.packageManager).toString() }
                .getOrDefault(provider.packageName),
            providerDescription = if (supportsResize) {
                runCatching { loadDescription(context).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() && it != "null" }
            } else {
                null
            },
            minWidthSp = minWidth,
            minHeightDp = minHeight,
            maxWidthSp = if (supportsResize) maxResizeWidth else 0,
            maxHeightDp = if (supportsResize) maxResizeHeight else 0,
            isConfigured = configure == null,
        )
    }

    companion object {
        private const val TAG = "WidgetHost"

        /**
         * The host id this launcher owns.
         *
         * `0x4D46` is "MF" in ASCII. A host id only has to be unique among the
         * hosts of this one package, so any non-zero constant is correct; picking a
         * readable value makes the platform's own logs legible.
         */
        const val HOST_ID = 0x4D46

        /** The sentinel the system expects in `EXTRA_APPWIDGET_ID` before a bind. */
        const val EMPTY_APP_WIDGET_ID = -1

        /** Request code for the provider configuration activity result. */
        const val CONFIGURE_REQUEST_CODE = 0x4D46
    }
}
