package com.minimalflow.launcher.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.search.SearchResult
import com.minimalflow.launcher.core.ui.Spacing
import com.minimalflow.launcher.core.ui.components.AppRow
import com.minimalflow.launcher.core.ui.components.EmptyState
import com.minimalflow.launcher.core.ui.cornerRadius
import com.minimalflow.launcher.core.ui.minimalFlowColors
import kotlinx.coroutines.delay

/**
 * The search surface.
 *
 * It is a separate mode rather than a `Dialog` because it has to cover the whole
 * window, take the back gesture, and be what the up-swipe gesture returns to. A
 * dialog would fight all three.
 */
@Composable
fun SearchScreen(
    viewModel: LauncherViewModel,
    loadIcon: IconLoader,
    pxFor: PxConverter,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = minimalFlowColors()
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val response by viewModel.searchResults.collectAsStateWithLifecycle()
    val recentTerms by viewModel.recentTerms.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }

    // Focus after the first composition so the keyboard comes up with the surface
    // rather than a frame later, which reads as a dropped first character.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = Spacing.screen),
    ) {
        Spacer(Modifier.height(Spacing.lg))

        SearchField(
            query = query,
            focusRequester = focusRequester,
            onQueryChange = viewModel::onQueryChange,
            onClose = {
                viewModel.commitSearch()
                onClose()
            },
        )

        Spacer(Modifier.height(Spacing.lg))

        val results = response?.results.orEmpty()
        when {
            query.isBlank() -> {
                if (recentTerms.isEmpty()) {
                    EmptyState(
                        title = "Search your apps",
                        detail = "Type a name, an alias, or a keyword you set.",
                        modifier = Modifier.padding(top = Spacing.xxl),
                    )
                } else {
                    RecentTerms(
                        terms = recentTerms,
                        onChoose = {
                            viewModel.chooseRecentTerm(it)
                            focusRequester.requestFocus()
                        },
                    )
                }
            }

            results.isEmpty() -> EmptyState(
                title = "Nothing matches \u201C$query\u201D",
                modifier = Modifier.padding(top = Spacing.xxl),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xxl),
            ) {
                items(items = results, key = { it.key.storageKey() }) { result ->
                    ResultRow(
                        result = result,
                        configuration = configuration,
                        loadIcon = loadIcon,
                        pxFor = pxFor,
                        onClick = {
                            viewModel.commitSearch()
                            viewModel.launch(result.entry)
                            onClose()
                        },
                        onLongClick = { viewModel.showAppMenu(result.entry.key) },
                    )
                }
            }
        }
    }
}

/**
 * The search box.
 *
 * `BasicTextField` rather than Material's `TextField`: Material insists on its
 * own container and label layout, and a launcher search field is one rounded
 * rectangle with a caret in it. The cursor colour and the field background both
 * come from the theme.
 */
@Composable
private fun SearchField(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = minimalFlowColors()
    val shape = RoundedCornerShape(cornerRadius())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.primaryText, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search apps",
                            color = colors.secondaryText,
                            fontSize = 16.sp,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            ClearButton(onClick = { onQueryChange("") })
        }
        Spacer(Modifier.size(Spacing.sm))
        Text(
            text = "Cancel",
            color = colors.accent,
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onClose),
        )
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    val colors = minimalFlowColors()
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.divider)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "\u2715", color = colors.secondaryText, fontSize = 11.sp)
    }
}

@Composable
private fun RecentTerms(terms: List<String>, onChoose: (String) -> Unit) {
    val colors = minimalFlowColors()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.none)) {
        Text(
            text = "Recent",
            color = colors.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )
        terms.forEach { term ->
            Text(
                text = term,
                color = colors.primaryText,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(term) }
                    .padding(vertical = Spacing.md),
            )
        }
    }
}

@Composable
private fun ResultRow(
    result: SearchResult,
    configuration: LauncherConfiguration,
    loadIcon: IconLoader,
    pxFor: PxConverter,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    AppRow(
        entry = result.entry,
        iconSize = configuration.iconSize.dp,
        rowHeight = configuration.rowHeight.dp,
        showIcon = configuration.showIcons,
        showLabel = true,
        loadIcon = loadIcon,
        pxFor = pxFor,
        onClick = onClick,
        onLongClick = onLongClick,
        trailing = {
            if (result.matchedOn != null) {
                Text(
                    text = result.matchedOn,
                    color = minimalFlowColors().secondaryText,
                    fontSize = 11.sp,
                )
            }
        },
    )
}
