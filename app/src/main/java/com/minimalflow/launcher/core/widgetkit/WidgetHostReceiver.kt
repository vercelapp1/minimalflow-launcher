package com.minimalflow.launcher.core.widgetkit

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.minimalflow.launcher.core.model.WidgetPlacement
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Tells the running launcher that a hosted widget changed.
 *
 * The platform has no way to reach a launcher's own Compose UI, so this receiver is
 * the signal: `onUpdate`, `onDeleted` and `onAppWidgetOptionsChanged` are the only
 * callbacks Android offers a widget host, and [WidgetHostRegistry.startListening] is
 * listening for the same broadcasts.
 *
 * It is declared with `android:permission="BIND_APPWIDGET"` and not exported,
 * which is what stops a third-party app from waking the launcher at will.
 *
 * MinimalFlow does not publish a widget of its own, so this receiver declares no
 * `android.appwidget.provider` metadata - it only needs the intent filter.
 *
 * Dependencies arrive through [WidgetHostReceiverEntryPoint] rather than
 * `@AndroidEntryPoint`. Hilt's annotation processor matches the framework base
 * classes it knows by name, and an explicit entry point keeps working whatever the
 * base class is, without asking the processor to reason about it.
 */
class WidgetHostReceiver : AppWidgetProvider() {

    private var cachedDependencies: WidgetHostReceiverEntryPoint? = null

    /**
     * `AppWidgetProvider` is a `BroadcastReceiver` and has no application context of
     * its own, so the entry point is resolved from the context the framework hands
     * to each callback and then reused.
     */
    private fun dependencies(context: Context): WidgetHostReceiverEntryPoint =
        cachedDependencies ?: EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetHostReceiverEntryPoint::class.java)
            .also { cachedDependencies = it }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // The AppWidgetHost is what pushes content to the provider; all this
        // receiver has to do is make the UI redraw the affected rows.
        dependencies(context).hostRegistry().startListening()
        appWidgetIds.forEach { WidgetHostEvents.publish(WidgetHostEvent.Updated(it)) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val dependencies = dependencies(context)
        WidgetHostEvents.publish(WidgetHostEvent.Resized(appWidgetId))
        dependencies.appScope().launch {
            // The platform won the size negotiation, so store what it granted.
            val width = newOptions.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                WidgetPlacement.DEFAULT_WIDTH_SP,
            )
            val height = newOptions.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                WidgetPlacement.DEFAULT_HEIGHT_DP,
            )
            runCatching { dependencies.widgetRepository().resize(appWidgetId, width, height) }
                .onFailure { error -> Log.w(TAG, "Could not store the granted size for $appWidgetId", error) }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // The platform has forgotten these ids while the placements table still has
        // rows for them, so drop the rows rather than rendering empty boxes. The
        // host keeps listening: other widgets are still bound to it.
        val dependencies = dependencies(context)
        dependencies.appScope().launch {
            appWidgetIds.forEach { id ->
                WidgetHostEvents.publish(WidgetHostEvent.Deleted(id))
                runCatching { dependencies.widgetRepository().forgetIfPresent(id) }
                    .onFailure { error -> Log.w(TAG, "Could not drop placement $id", error) }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            // A provider just pushed content, so make sure the host is listening
            // before the platform tries to deliver anything else.
            dependencies(context).hostRegistry().startListening()
        }
    }

    private companion object {
        const val TAG = "WidgetHostReceiver"
    }
}

/** What [WidgetHostReceiver] needs from the dependency graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetHostReceiverEntryPoint {
    fun widgetRepository(): WidgetRepository
    fun hostRegistry(): WidgetHostRegistry
    fun appScope(): CoroutineScope
}

/** Something the platform told us about a hosted widget. */
sealed interface WidgetHostEvent {
    /** A provider pushed new content to this widget. */
    data class Updated(val appWidgetId: Int) : WidgetHostEvent

    /** The user or the provider changed a widget's size. */
    data class Resized(val appWidgetId: Int) : WidgetHostEvent

    /** The platform deleted a widget, for example because its provider was removed. */
    data class Deleted(val appWidgetId: Int) : WidgetHostEvent
}

/**
 * Process-wide widget events.
 *
 * A shared flow rather than a bound-service call, because the launcher needs the
 * events while it is in the foreground and a bound service would have to be
 * connected before the first frame is drawn.
 */
object WidgetHostEvents {

    private val events = MutableSharedFlow<WidgetHostEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun subscribe(): SharedFlow<WidgetHostEvent> = events.asSharedFlow()

    internal fun publish(event: WidgetHostEvent) {
        events.tryEmit(event)
    }
}

/**
 * A hosted [AppWidgetHostView] together with the placement it belongs to.
 *
 * They travel as one value so a composable cannot render a view against the wrong
 * row after a reorder.
 */
data class BoundWidget(
    val placement: WidgetPlacement,
    val view: AppWidgetHostView,
    val providerInfo: AppWidgetProviderInfo,
)
