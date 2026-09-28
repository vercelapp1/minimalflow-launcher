package com.minimalflow.launcher.core.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches for installs, updates and removals.
 *
 * Installing three apps in a row would otherwise trigger three full re-reads of
 * the package manager, so the raw broadcasts are debounced into a single tick
 * that [AppRepository] reacts to.
 *
 * The receiver is registered at runtime and is *not* declared in the manifest:
 * `PACKAGE_*` broadcasts are system broadcasts, and a manifest receiver would
 * have to be exported, which the launcher deliberately avoids.
 */
@Singleton
class AppPackageChangeObserver @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /**
     * Emits a debounced tick whenever the installed app list may have changed.
     * Collecting starts and stops the receiver with the collector's lifetime.
     */
    fun observe(): Flow<Unit> = _changes
        .debounce(DEBOUNCE_MILLIS)
        .distinctUntilChanged()

    /** Shared view without the debounce, for callers that want the raw signal. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * Starts observing. Safe to call repeatedly: the receiver is only registered
     * once, and [stop] tears it down.
     */
    fun start() {
        if (receiver != null) return
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                _changes.tryEmit(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val registered = runCatching {
            context.registerReceiver(newReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "Could not observe package changes", it) }.isSuccess

        if (registered) receiver = newReceiver else Log.w(TAG, "Package observer not registered")
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private var receiver: BroadcastReceiver? = null

    private companion object {
        const val TAG = "PackageObserver"
        const val DEBOUNCE_MILLIS = 400L
    }
}
