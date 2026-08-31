@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.uhi.enia.ridesafe.feature.logbook.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.domain.refuel.LogbookAction
import de.uhi.enia.ridesafe.domain.refuel.LogbookActionKind
import de.uhi.enia.ridesafe.domain.refuel.RefuelAssociationCheck
import de.uhi.enia.ridesafe.domain.ride.MergeCheck

@Composable
internal fun SelectionTopBar(
    count: Int,
    allSelected: Boolean,
    action: LogbookAction,
    operationRunning: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAction: () -> Unit,
    exportEnabled: Boolean,
    onExport: () -> Unit,
    deleteEnabled: Boolean,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.ride_selection_count, count)) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            IconButton(onClick = onExit) {
                MaterialSymbol(symbolName = "close", contentDescription = stringResource(R.string.action_exit_selection))
            }
        },
        actions = {
            IconButton(onClick = if (allSelected) onDeselectAll else onSelectAll) {
                MaterialSymbol(
                    symbolName = "select_all",
                    contentDescription =
                        stringResource(if (allSelected) R.string.action_deselect_all else R.string.action_select_all),
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                MaterialSymbol(symbolName = "more_vert", contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    enabled = !operationRunning && action.enabled,
                    onClick = {
                        menuOpen = false
                        onAction()
                    },
                    text = {
                        Column {
                            Text(stringResource(actionLabel(action.kind)))
                            // When disabled, tell the user why the action isn't available right now (MRG-08).
                            listOfNotNull(
                                mergeDisabledReason(action.rideCheck),
                                associationDisabledReason(action.refuelCheck),
                            ).forEach { reason ->
                                Text(
                                    text = stringResource(reason),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = { MaterialSymbol(symbolName = actionIcon(action.kind), contentDescription = null) },
                )
                DropdownMenuItem(
                    enabled = exportEnabled && !operationRunning,
                    onClick = {
                        menuOpen = false
                        onExport()
                    },
                    text = { Text(stringResource(R.string.ride_action_export)) },
                    leadingIcon = { MaterialSymbol(symbolName = "download", contentDescription = null) },
                )
                DropdownMenuItem(
                    enabled = deleteEnabled && !operationRunning,
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.ride_action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        MaterialSymbol(
                            symbolName = "delete",
                            contentDescription = null,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        },
    )
}

private fun actionLabel(kind: LogbookActionKind): Int =
    when (kind) {
        LogbookActionKind.MERGE -> R.string.ride_action_merge
        LogbookActionKind.UNMERGE -> R.string.ride_action_unmerge
        LogbookActionKind.ATTACH -> R.string.ride_action_attach_refuel
        LogbookActionKind.DETACH -> R.string.ride_action_detach_refuel
    }

private fun actionIcon(kind: LogbookActionKind): String =
    when (kind) {
        LogbookActionKind.MERGE -> "merge"
        LogbookActionKind.UNMERGE -> "call_split"
        LogbookActionKind.ATTACH -> "link"
        LogbookActionKind.DETACH -> "link_off"
    }

/** The reason string for a disabled Merge action, or null when merging is allowed (MRG-08). */
private fun mergeDisabledReason(check: MergeCheck): Int? =
    when (check) {
        MergeCheck.OK -> null
        MergeCheck.NOT_ENOUGH -> R.string.merge_reason_not_enough
        MergeCheck.MIXED_VEHICLE -> R.string.merge_reason_mixed_vehicle
        MergeCheck.NOT_CONTIGUOUS -> R.string.merge_reason_not_contiguous
    }

private fun associationDisabledReason(check: RefuelAssociationCheck): Int? =
    when (check) {
        RefuelAssociationCheck.OK -> null
        RefuelAssociationCheck.VEHICLE_MISMATCH -> R.string.refuel_reason_vehicle_mismatch
        RefuelAssociationCheck.OTHER_JOURNEY -> R.string.refuel_reason_other_ride
        RefuelAssociationCheck.NO_CHANGES -> R.string.refuel_reason_already_attached
        RefuelAssociationCheck.NOT_ALL_ATTACHED -> R.string.refuel_reason_not_all_attached
        RefuelAssociationCheck.WRONG_SELECTION -> null
    }
