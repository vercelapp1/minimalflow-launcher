package com.minimalflow.launcher

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps [MinimalFlowApplication] for [HiltTestApplication] in instrumentation tests.
 *
 * The real application class builds the whole object graph, including the
 * database and the package-change receiver, none of which a test wants. This
 * runner is referenced from `testInstrumentationRunner` in the build script.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
