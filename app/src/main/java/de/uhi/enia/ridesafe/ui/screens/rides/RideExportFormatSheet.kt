@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import de.uhi.enia.ridesafe.export.RideExportFormat
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import kotlinx.coroutines.launch

@Composable
internal fun ExportFormatSheet(
    onFormatSelected: (RideExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectionInProgress by remember { mutableStateOf(false) }

    fun select(format: RideExportFormat) {
        if (selectionInProgress) return
        selectionInProgress = true
        scope.launch {
            sheetState.hide()
            onFormatSelected(format)
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
            Column(
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.ride_action_export),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.ride_export_format_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExportFormatOption(
                title = stringResource(R.string.ride_export_format_pdf),
                description = stringResource(R.string.ride_export_format_pdf_description),
                symbolName = "picture_as_pdf",
                enabled = !selectionInProgress,
                onClick = { select(RideExportFormat.PDF) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 72.dp, end = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ExportFormatOption(
                title = stringResource(R.string.ride_export_format_csv),
                description = stringResource(R.string.ride_export_format_csv_description),
                symbolName = "table_view",
                enabled = !selectionInProgress,
                onClick = { select(RideExportFormat.CSV) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 72.dp, end = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ExportFormatOption(
                title = stringResource(R.string.ride_export_format_zip),
                description = stringResource(R.string.ride_export_format_zip_description),
                symbolName = "folder_zip",
                enabled = !selectionInProgress,
                onClick = { select(RideExportFormat.ZIP) },
            )
        }
    }
}

@Composable
private fun ExportFormatOption(
    title: String,
    description: String,
    symbolName: String,
    enabled: Boolean,
    onClick: () -> Unit,
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
                headlineColor = MaterialTheme.colorScheme.onSurface,
                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledHeadlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingContent = {
            MaterialSymbol(
                symbolName = symbolName,
                contentDescription = null,
            )
        },
        trailingContent = {
            MaterialSymbol(
                symbolName = "chevron_right",
                contentDescription = null,
            )
        },
    )
}
