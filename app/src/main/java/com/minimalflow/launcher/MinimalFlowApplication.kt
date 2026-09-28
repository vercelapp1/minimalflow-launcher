package com.minimalflow.launcher

import android.app.Application
import com.minimalflow.launcher.core.apps.AppPackageChangeObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The launcher process.
 *
 * Deliberately thin. A launcher is started on every home press and must reach its
 * first frame quickly, so nothing here blocks startup: the only work is registering
 * the package-change observer, which the Android framework instantiates for us.
 * Anything expensive belongs in a lazily injected service that the first screen
 * asks for.
 */
@HiltAndroidApp
class MinimalFlowApplication : Application() {

    @Inject
    lateinit var packageChangeObserver: AppPackageChangeObserver

    override fun onCreate() {
        super.onCreate()
        // Registering is explicit rather than done in the observer's own
        // constructor so the dependency graph is guaranteed to be built first.
        packageChangeObserver.start()
    }
}
