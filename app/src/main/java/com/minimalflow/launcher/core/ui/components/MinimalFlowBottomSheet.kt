package com.minimalflow.launcher.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.minimalflow.launcher.core.ui.cornerRadiusLarge
import com.minimalflow.launcher.core.ui.minimalFlowColors

/**
 * The one bottom sheet every menu in MinimalFlow uses.
 *
 * Wrapping `ModalBottomSheet` rather than using it directly is what keeps the
 * launcher's colours out of the screens: Material 3's sheet takes its colours
 * from the scheme, and the whole point of `MinimalFlowColors` is that a custom
 * theme reaches every surface without any screen having to think about it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinimalFlowBottomSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = minimalFlowColors()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.primaryText,
        shape = RoundedCornerShape(
            topStart = cornerRadiusLarge(),
            topEnd = cornerRadiusLarge(),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Keeps the last row clear of the gesture bar or the nav buttons.
                .navigationBarsPadding(),
            content = content,
        )
    }
}
