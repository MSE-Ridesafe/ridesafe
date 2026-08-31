@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.feature.garage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import kotlinx.coroutines.launch

/**
 * What to do with the vehicle's photo: replace it, or remove it. Opened only while a photo exists —
 * with none, the only sensible action is the picker, which the header launches directly instead of
 * hiding it behind a one-option sheet. Same anatomy as ExportFormatSheet; removal is error-colored
 * but unconfirmed, because a photo is re-added in two taps, unlike the data deletions that warrant
 * ConfirmDestructiveDialog.
 */
@Composable
internal fun VehiclePhotoSheet(
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectionInProgress by remember { mutableStateOf(false) }

    fun select(action: () -> Unit) {
        if (selectionInProgress) return
        selectionInProgress = true
        scope.launch {
            sheetState.hide()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!selectionInProgress) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.vehicle_photo_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 12.dp),
            )
            PhotoOption(
                title = stringResource(R.string.vehicle_photo_replace),
                symbolName = "photo_library",
                enabled = !selectionInProgress,
                onClick = { select(onReplace) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 72.dp, end = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            PhotoOption(
                title = stringResource(R.string.vehicle_photo_remove),
                symbolName = "delete",
                enabled = !selectionInProgress,
                onClick = { select(onRemove) },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PhotoOption(
    title: String,
    symbolName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: Color = Color.Unspecified,
) {
    ListItem(
        modifier =
            Modifier
                .padding(horizontal = 8.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        colors =
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
                leadingIconColor = color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                disabledHeadlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        leadingContent = {
            MaterialSymbol(
                symbolName = symbolName,
                contentDescription = null,
            )
        },
    )
}
