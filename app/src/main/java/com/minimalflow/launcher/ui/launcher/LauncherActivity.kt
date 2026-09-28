package com.minimalflow.launcher.ui.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.minimalflow.launcher.core.apps.AppIconRepository
import com.minimalflow.launcher.core.data.PreferencesRepository
import com.minimalflow.launcher.core.gestures.GestureCommand
import com.minimalflow.launcher.core.gestures.GestureDispatcher
import com.minimalflow.launcher.core.gestures.GestureHost
import com.minimalflow.launcher.core.gestures.minimalFlowGestures
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.themes.BuiltInThemes
import com.minimalflow.launcher.core.themes.MinimalFlowTheme
import com.minimalflow.launcher.core.themes.ThemeRepository
import com.minimalflow.launcher.ui.settings.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The home screen: the activity the system starts when the user presses home.
 *
 * It is deliberately a launcher and nothing else. Everything that can be done in
 * one tap is on this surface and everything else is one gesture away, because a
 * home screen that needs a trip through settings to be useful is just a settings
 * app with an app drawer.
 */
@AndroidEntryPoint
class LauncherActivity : ComponentActivity(), GestureHost {

    @Inject
    lateinit var iconRepository: AppIconRepository

    @Inject
    lateinit var gestureDispatcher: GestureDispatcher

    @Inject
    lateinit var themeRepository: ThemeRepository

    @Inject
    lateinit var preferences: PreferencesRepository

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LauncherRoot(
                viewModel = viewModel,
                iconRepository = iconRepository,
                themeRepository = themeRepository,
                onGesture = ::onGesture,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        rememberPreviousHome()
    }

    /**
     * Remembers the launcher the user came from, once, so settings can offer a
     * way back. Read from DataStore rather than from the resolver because by the
     * time this runs MinimalFlow may already be the default, and at that point
     * the resolver only has one answer to give.
     */
    private fun rememberPreviousHome() {
        lifecycleScope.launch {
            if (!preferences.previousHomePackage.first().isNullOrBlank()) return@launch
            val current = currentHomePackage()
            if (current != null && current != packageName) {
                preferences.setPreviousHomePackage(current)
            }
        }
    }

    private fun currentHomePackage(): String? = runCatching {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0,
        )
    }.getOrNull()?.activityInfo?.packageName

    /**
     * Gestures go through [GestureDispatcher] so the slot-to-action mapping, and
     * the refusal to perform a gesture the current screen cannot handle, live in
     * one testable place instead of in the activity.
     */
    private fun onGesture(command: GestureCommand) {
        lifecycleScope.launch {
            gestureDispatcher.dispatch(command, this@LauncherActivity)
        }
    }

    // ----------------------------------------------------------------- GestureHost
    //
    // The dispatcher only knows *what* to do. Opening search is view-model state,
    // and opening settings is an activity start, so each is handled where it
    // actually belongs.

    override fun openSearchOverlay() {
        viewModel.openSearch()
    }

    override fun openAppList() {
        viewModel.openAppList()
    }

    override fun openFavorites() {
        viewModel.closeSearch()
    }

    override fun openWidgetPanel() = Unit

    override fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}

@Composable
private fun LauncherRoot(
    viewModel: LauncherViewModel,
    iconRepository: AppIconRepository,
    themeRepository: ThemeRepository,
    onGesture: (GestureCommand) -> Unit,
) {
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val pxFor: (Dp) -> Int = { dp -> with(density) { dp.toPx().roundToInt() } }

    val loadIcon: IconLoader = { entry: AppListEntry, sizePx: Int ->
        iconRepository.loadIcon(
            key = entry.key,
            iconSource = entry.iconSource,
            sizePx = sizePx,
        )
    }

    // The stored theme is a suspending lookup, so the first frame uses the
    // built-in default and swaps when it arrives. A blank frame here would be far
    // more visible than a brief flash of the default palette.
    val theme by produceState<com.minimalflow.launcher.core.model.ThemeConfig?>(
        initialValue = null,
        key1 = configuration.themeId,
    ) {
        value = runCatching { themeRepository.resolveOrDefault(configuration.themeId) }.getOrNull()
    }

    MinimalFlowTheme(
        theme = theme ?: BuiltInThemes.default,
        configuration = configuration,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The list deliberately draws under the system bars; only the
                // content that would otherwise collide is padded.
                .systemBarsPadding()
                .minimalFlowGestures(
                    config = configuration.gestureConfig,
                    onGesture = { detection -> onGesture(detection.command) },
                ),
        ) {
            when (mode) {
                LauncherMode.Home -> HomeScreen(
                    viewModel = viewModel,
                    loadIcon = loadIcon,
                    pxFor = pxFor,
                    onOpenSettings = { onGesture(GestureCommand.OpenSettings) },
                )

                LauncherMode.Search -> SearchScreen(
                    viewModel = viewModel,
                    loadIcon = loadIcon,
                    pxFor = pxFor,
                    onClose = viewModel::closeSearch,
                )
            }
        }
    }
}
