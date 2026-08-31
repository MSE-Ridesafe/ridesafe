package de.uhi.enia.ridesafe.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.recording.trigger.AutoTrackMode
import de.uhi.enia.ridesafe.recording.trigger.AutoTrackPrefs

/**
 * NFR-05: the runtime permissions Ridesafe asks for, each with the purpose shown to the user.
 * The manifest declares more (FOREGROUND_SERVICE*, INTERNET), but those are install-time and
 * never need a dialog.
 */
enum class AppPermission(
    val permission: String,
    val symbolName: String,
    val titleRes: Int,
    val rationaleRes: Int,
) {
    LOCATION(
        Manifest.permission.ACCESS_FINE_LOCATION,
        "my_location",
        R.string.permission_location_title,
        R.string.permission_location_rationale,
    ),
    BACKGROUND_LOCATION(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        "explore",
        R.string.permission_background_location_title,
        R.string.permission_background_location_rationale,
    ),
    BLUETOOTH(
        Manifest.permission.BLUETOOTH_CONNECT,
        "bluetooth",
        R.string.permission_bluetooth_title,
        R.string.permission_bluetooth_rationale,
    ),
    ACTIVITY(
        Manifest.permission.ACTIVITY_RECOGNITION,
        "directions_car",
        R.string.permission_activity_title,
        R.string.permission_activity_rationale,
    ),
    NOTIFICATIONS(
        Manifest.permission.POST_NOTIFICATIONS,
        "notifications",
        R.string.permission_notifications_title,
        R.string.permission_notifications_rationale,
    ),
    ;

    fun isGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * What the *enabled* features actually need. Nothing is required while automatic recording is
 * OFF (SET-06), so a fresh install doesn't open with a list of demands — switching tracking on
 * is what triggers the first request.
 */
fun requiredFor(mode: AutoTrackMode): List<AppPermission> =
    when (mode) {
        AutoTrackMode.OFF -> {
            emptyList()
        }

        AutoTrackMode.PAIRED_ONLY -> {
            listOf(
                AppPermission.LOCATION,
                AppPermission.BACKGROUND_LOCATION,
                AppPermission.BLUETOOTH,
                AppPermission.NOTIFICATIONS,
            )
        }

        AutoTrackMode.ANY -> {
            listOf(
                AppPermission.LOCATION,
                AppPermission.BACKGROUND_LOCATION,
                AppPermission.BLUETOOTH,
                AppPermission.ACTIVITY,
                AppPermission.NOTIFICATIONS,
            )
        }
    }

/**
 * Which of [requiredFor] are still missing, in grant order. Background location stays hidden
 * until foreground location is held — Android won't grant it before that anyway.
 */
fun missingPermissionsFor(
    context: Context,
    mode: AutoTrackMode,
): List<AppPermission> =
    requiredFor(mode).filter { p ->
        !p.isGranted(context) &&
            (p != AppPermission.BACKGROUND_LOCATION || AppPermission.LOCATION.isGranted(context))
    }

/**
 * The subset of [missing] that can go into a single system request. Background location is
 * dropped on purpose: bundling it with foreground location makes Android ignore the whole
 * request, and it is grantable only from the app's own settings page (see [PermissionAlertCard]).
 */
fun bundleRequest(missing: List<AppPermission>): Array<String> =
    missing
        .filterNot { it == AppPermission.BACKGROUND_LOCATION }
        .map { it.permission }
        .toTypedArray()

/**
 * What the enabled features are still missing, as observable state.
 *
 * Process-global on purpose. Passing this down as a parameter does not work: a screen composed
 * by [androidx.navigation3.ui.NavDisplay] only picks up values captured in `entryProvider` when
 * the back stack changes, so the card kept listing permissions the user had just granted until
 * they navigated away and back — measured, not assumed. Reading this object subscribes each
 * composable directly, on both sides of that boundary (the card, and the navigation badge). Same
 * idiom as [de.uhi.enia.ridesafe.recording.trigger.AutoTracking]; main thread only.
 */
object PermissionState {
    var missing by mutableStateOf(emptyList<AppPermission>())
        private set

    /** Re-reads the system's answer. Cheap, and the only way to learn about a grant. */
    fun refresh(context: Context) {
        missing = missingPermissionsFor(context, AutoTrackPrefs.get(context))
    }
}

private fun appSettingsIntent(context: Context) =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

/**
 * Top-of-Settings alert listing what the enabled features still need, one tappable row each
 * (NFR-05). A tap opens the system dialog; when that dialog is spent — denied twice over, so
 * `shouldShowRequestPermissionRationale` has gone false — it opens the app's settings page
 * instead, because launching the request again would do nothing at all. Background location
 * skips the dialog entirely: Android only offers "Allow all the time" on that page.
 */
@Composable
fun PermissionAlertCard(modifier: Modifier = Modifier) {
    val missing = PermissionState.missing
    if (missing.isEmpty()) return
    val context = LocalContext.current
    val activity = LocalActivity.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val stuck =
                activity != null &&
                    results.any { (permission, granted) ->
                        !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                    }
            if (stuck) context.startActivity(appSettingsIntent(context))
            PermissionState.refresh(context)
        }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MaterialSymbol(
                symbolName = "warning",
                contentDescription = null,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.permissions_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.permissions_card_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        missing.forEachIndexed { index, p ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f))
            }
            Row(
                modifier =
                    Modifier
                        .clickable {
                            if (p == AppPermission.BACKGROUND_LOCATION) {
                                context.startActivity(appSettingsIntent(context))
                            } else {
                                launcher.launch(arrayOf(p.permission))
                            }
                        }.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MaterialSymbol(
                    symbolName = p.symbolName,
                    contentDescription = null,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(p.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(p.rationaleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                MaterialSymbol(
                    symbolName = "chevron_right",
                    contentDescription = null,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
