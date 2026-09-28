package com.minimalflow.launcher.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.ui.Spacing
import com.minimalflow.launcher.core.ui.cornerRadiusSmall
import com.minimalflow.launcher.core.ui.minimalFlowColors

/**
 * The pieces every list and every sheet in the launcher is built from.
 *
 * They all live here rather than next to each screen so that an app row looks the
 * same in the home list, in search results and in the favourites strip, and so a
 * change to the row design is one edit rather than three.
 */

object MinimalFlowShapes {
    const val LABEL_SIZE_SP = 12
    const val SECTION_SIZE_SP = 11
    const val ROW_LABEL_SIZE_SP = 15
    val TileWidth: Dp = 72.dp
}

/**
 * An app icon that loads asynchronously and shows a neutral placeholder until it
 * arrives.
 *
 * The placeholder is a rounded square in the divider colour rather than a spinner:
 * a list of a few hundred rows must not contain a few hundred spinning animations.
 */
@Composable
fun AppIcon(
    entry: AppListEntry,
    iconSize: Dp,
    loadIcon: suspend (AppListEntry, Int) -> ImageBitmap?,
    pxFor: (Dp) -> Int,
    modifier: Modifier = Modifier,
) {
    val colors = minimalFlowColors()
    val sizePx = pxFor(iconSize)

    val icon by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = entry.key,
        key2 = sizePx,
        key3 = entry.iconSource,
    ) {
        value = loadIcon(entry, sizePx)
    }

    val shape = RoundedCornerShape(cornerRadiusSmall())
    Box(
        modifier = modifier
            .size(iconSize)
            .clip(shape)
            .background(colors.divider),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap == null) {
            Text(
                text = entry.displayLabel.take(1).uppercase(),
                color = colors.secondaryText,
                fontSize = (iconSize.value * 0.4f).sp,
            )
        } else {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

/** One row of the app list: icon, label, and an optional trailing indicator. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    entry: AppListEntry,
    iconSize: Dp,
    rowHeight: Dp,
    showIcon: Boolean,
    showLabel: Boolean,
    loadIcon: suspend (AppListEntry, Int) -> ImageBitmap?,
    pxFor: (Dp) -> Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = minimalFlowColors()
    val interaction = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .then(interaction)
            .padding(horizontal = Spacing.rowHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            AppIcon(
                entry = entry,
                iconSize = iconSize,
                loadIcon = loadIcon,
                pxFor = pxFor,
            )
            Spacer(Modifier.width(Spacing.lg))
        }
        if (showLabel) {
            Text(
                text = entry.displayLabel,
                color = colors.primaryText,
                fontSize = MinimalFlowShapes.ROW_LABEL_SIZE_SP.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.sm))
            trailing()
        }
    }
}

/**
 * A tappable icon with an optional caption, used by the favourites strip and the
 * dock.
 *
 * A fixed tile width is what makes a strip of a dozen favourites fill the width
 * evenly instead of leaving a ragged right edge as labels vary in length.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconTile(
    entry: AppListEntry,
    iconSize: Dp,
    showLabel: Boolean,
    loadIcon: suspend (AppListEntry, Int) -> ImageBitmap?,
    pxFor: (Dp) -> Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = minimalFlowColors()
    val interaction = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }


    Column(
        modifier = modifier
            .then(interaction)
            .padding(vertical = Spacing.sm)
            .width(MinimalFlowShapes.TileWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        AppIcon(
            entry = entry,
            iconSize = iconSize,
            loadIcon = loadIcon,
            pxFor = pxFor,
        )
        if (showLabel) {
            Text(
                text = entry.displayLabel,
                color = colors.secondaryText,
                fontSize = MinimalFlowShapes.LABEL_SIZE_SP.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The A to Z section header of a grouped list. */
@Composable
fun SectionHeader(letter: String, modifier: Modifier = Modifier) {
    val colors = minimalFlowColors()
    Text(
        text = letter,
        color = colors.accent,
        fontSize = MinimalFlowShapes.SECTION_SIZE_SP.sp,
        modifier = modifier.padding(horizontal = Spacing.rowHorizontal, vertical = Spacing.sm),
    )
}

/** An empty state with a title and an optional explanation. */
@Composable
fun EmptyState(title: String, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = minimalFlowColors()
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(text = title, color = colors.secondaryText, fontSize = 14.sp)
        if (detail != null) {
            Text(
                text = detail,
                color = colors.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
