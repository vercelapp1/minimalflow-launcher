package com.minimalflow.launcher.ui.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimalflow.launcher.core.apps.AppSection
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.SortMode
import com.minimalflow.launcher.core.ui.Spacing
import com.minimalflow.launcher.core.ui.components.AppRow
import com.minimalflow.launcher.core.ui.components.MinimalFlowBottomSheet
import com.minimalflow.launcher.core.ui.minimalFlowColors

/**
 * The modal surfaces of the home screen: the long-press app menu and the sort
 * picker.
 *
 * A modal bottom sheet rather than a `Popup`: it dims the list behind it, it
 * gets the back gesture for free, and it cannot leave the launcher in a state
 * where the list is visible but unusable.
 */
@Composable
fun HomeSheets(
    sheet: HomeSheet,
    sections: List<AppSection>,
    configuration: LauncherConfiguration,
    loadIcon: IconLoader,
    pxFor: PxConverter,
    onDismiss: () -> Unit,
    onLaunch: (AppListEntry) -> Unit,
    onToggleFavorite: (AppListEntry) -> Unit,
    onHide: (AppListEntry) -> Unit,
    onAppInfo: (AppKey) -> Unit,
    onUninstall: (AppListEntry) -> Unit,
    onSortMode: (SortMode) -> Unit,
) {
    when (sheet) {
        HomeSheet.None -> Unit

        is HomeSheet.AppMenu -> {
            val entry = sections.firstEntry(sheet.key)
            if (entry == null) {
                // The app was uninstalled while its sheet was open.
                onDismiss()
            } else {
                MinimalFlowBottomSheet(onDismiss = onDismiss) {
                    AppMenuSheet(
                        entry = entry,
                        configuration = configuration,
                        loadIcon = loadIcon,
                        pxFor = pxFor,
                        onLaunch = { onLaunch(entry); onDismiss() },
                        onToggleFavorite = { onToggleFavorite(entry) },
                        onAppInfo = { onAppInfo(entry.key); onDismiss() },
                        onUninstall = { onUninstall(entry); onDismiss() },
                        onHide = { onHide(entry) },
                    )
                }
            }
        }

        HomeSheet.SortModePicker -> {
            MinimalFlowBottomSheet(onDismiss = onDismiss) {
                SortModeSheet(
                    selected = configuration.sortingMode,
                    usageTrackingEnabled = configuration.privacy.usageTrackingEnabled,
                    onSelect = onSortMode,
                )
            }
        }
    }
}

/** The per-app actions offered by a long press. */
@Composable
private fun AppMenuSheet(
    entry: AppListEntry,
    configuration: LauncherConfiguration,
    loadIcon: IconLoader,
    pxFor: PxConverter,
    onLaunch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = minimalFlowColors()
    Column(modifier = Modifier.fillMaxWidth()) {
        AppRow(
            entry = entry,
            iconSize = configuration.iconSize.dp,
            rowHeight = TouchRowHeight,
            showIcon = true,
            showLabel = true,
            loadIcon = loadIcon,
            pxFor = pxFor,
            onClick = onLaunch,
        )
        HorizontalDivider(color = colors.divider)
        SheetAction(
            label = if (entry.isFavorite) "Remove from favourites" else "Add to favourites",
            onClick = onToggleFavorite,
        )
        SheetAction(label = "App info", onClick = onAppInfo)
        SheetAction(label = "Uninstall", onClick = onUninstall, destructive = true)
        SheetAction(label = "Hide from list", onClick = onHide)
    }
}

/** The sort picker. */
@Composable
private fun SortModeSheet(
    selected: SortMode,
    usageTrackingEnabled: Boolean,
    onSelect: (SortMode) -> Unit,
) {
    val colors = minimalFlowColors()
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetHeader("Sort apps by")
        SortMode.entries.forEach { mode ->
            val needsUsage = mode == SortMode.RECENT || mode == SortMode.FREQUENT
            val disabled = needsUsage && !usageTrackingEnabled

            SheetAction(
                label = if (disabled) "${mode.title} (needs usage tracking)" else mode.title,
                onClick = { if (!disabled) onSelect(mode) },
                enabled = !disabled,
                selected = mode == selected,
            )
        }
        if (!usageTrackingEnabled) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "Turn on usage tracking in Settings to sort by recent or " +
                    "most used. Nothing is recorded until you do.",
                color = colors.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = Spacing.screen),
            )
        }
    }
}

@Composable
private fun SheetHeader(text: String) {
    val colors = minimalFlowColors()
    Text(
        text = text,
        color = colors.secondaryText,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md),
    )
}

@Composable
private fun SheetAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = minimalFlowColors()
    val color = when {
        !enabled -> colors.divider
        destructive -> colors.error
        selected -> colors.accent
        else -> colors.primaryText
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchRowHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = color, fontSize = 15.sp)
        if (selected) {
            Text(text = "\u2713", color = colors.accent, fontSize = 15.sp)
        }
    }
}

/** Looks a key up across every section, so the menu works in any sort mode. */
private fun List<AppSection>.firstEntry(key: AppKey): AppListEntry? =
    firstNotNullOfOrNull { section -> section.entries.firstOrNull { it.key == key } }

private val TouchRowHeight = 52.dp
