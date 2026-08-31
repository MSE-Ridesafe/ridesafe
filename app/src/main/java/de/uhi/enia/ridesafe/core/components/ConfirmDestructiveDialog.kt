package de.uhi.enia.ridesafe.core.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R

/**
 * Confirmation prompt shown before any destructive action (UX-01): delete icon, error-tinted
 * confirm, plain cancel. Dismisses itself before invoking [onConfirm].
 */
@Composable
fun ConfirmDestructiveDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.action_delete),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { MaterialSymbol(symbolName = "delete", contentDescription = null) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** The full-width outlined delete button that opens the confirmation above. */
@Composable
fun DestructiveOutlinedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = modifier.fillMaxWidth(),
    ) {
        MaterialSymbol(symbolName = "delete", contentDescription = null, size = 18.dp)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}
