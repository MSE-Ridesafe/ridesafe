package de.uhi.enia.ridesafe.ui.onboarding

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.VehicleDao
import de.uhi.enia.ridesafe.navigation.RidesafeApp
import de.uhi.enia.ridesafe.permissions.AppPermission
import de.uhi.enia.ridesafe.permissions.PermissionAlertCard
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackMode
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.rides.trigger.BluetoothDevices
import de.uhi.enia.ridesafe.rides.trigger.applyAutoTrackMode
import de.uhi.enia.ridesafe.ui.components.EcoLevelDisplay
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.SafetyScoreCard
import de.uhi.enia.ridesafe.ui.screens.garage.BluetoothPickerDialog
import de.uhi.enia.ridesafe.ui.screens.garage.TrackingCard
import de.uhi.enia.ridesafe.ui.screens.garage.VehicleFormScreen
import de.uhi.enia.ridesafe.ui.screens.settings.SavedAddressFormScreen
import de.uhi.enia.ridesafe.ui.screens.settings.SavedAddressViewModel
import de.uhi.enia.ridesafe.ui.theme.RidesafeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Matches the app shell's sub-route slide (RidesafeApp.SLIDE_MS) so the wizard moves like the app.
private const val SLIDE_MS = 250

/**
 * The wizard's steps, in order. [onboardingSteps] filters them by state; the welcome step is
 * always first and doubles as the whole flow's skip-out.
 */
enum class OnboardingStep {
    WELCOME,
    CAR,
    BLUETOOTH,
    AUTO_TRACK,
    PLACE,
    RECORDING,
    SCORES,
}

/** The steps that act on the created car, and so drop out when the car step was skipped. */
private val VehicleBoundSteps = setOf(OnboardingStep.BLUETOOTH, OnboardingStep.AUTO_TRACK)

/**
 * The steps for the current state — pure so the sequencing is unit-testable. The Bluetooth and
 * auto-record steps act *on a vehicle* (GAR-08 mapping, TRK-02 detection), so without a created
 * car they have nothing to work with; skipping the car step therefore skips them too.
 */
fun onboardingSteps(hasVehicle: Boolean): List<OnboardingStep> =
    OnboardingStep.entries.filter { hasVehicle || it !in VehicleBoundSteps }

/** The step after [current], or null when [current] is the last — i.e. advancing finishes. */
fun stepAfter(
    current: OnboardingStep,
    hasVehicle: Boolean,
): OnboardingStep? =
    onboardingSteps(hasVehicle).let { steps ->
        steps.getOrNull(steps.indexOf(current) + 1)
    }

/** The step before [current], or null on the first. */
fun stepBefore(
    current: OnboardingStep,
    hasVehicle: Boolean,
): OnboardingStep? =
    onboardingSteps(hasVehicle).let { steps ->
        steps.indexOf(current).let { if (it > 0) steps[it - 1] else null }
    }

/**
 * Decides between the onboarding wizard and the app proper (ONB-01) — the composable MainActivity
 * hosts. A Settings replay request wins outright; a completed flag skips straight to the app;
 * otherwise the first run is resolved once from the garage ([firstRunDecision]): an install that
 * already has vehicles predates this feature and is marked done silently.
 */
@Composable
fun OnboardingGate() {
    val context = LocalContext.current
    if (OnboardingPrefs.replayRequested) {
        OnboardingFlow(onFinished = { OnboardingPrefs.setCompleted(context) })
        return
    }
    if (OnboardingPrefs.isCompleted(context)) {
        RidesafeApp()
        return
    }
    val decision by
        produceState<FirstRunDecision?>(initialValue = null) {
            val vehicles = RidesafeDatabase.getInstance(context).vehicleDao().observeAll().first()
            value =
                firstRunDecision(completed = false, vehicleCount = vehicles.size).also {
                    if (it == FirstRunDecision.SUPPRESS_AND_MARK_DONE) OnboardingPrefs.setCompleted(context)
                }
        }
    when (decision) {
        // Plain themed ground for the frames the Room read takes on a cold start.
        null -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {}
        FirstRunDecision.SHOW -> OnboardingFlow(onFinished = { OnboardingPrefs.setCompleted(context) })
        else -> RidesafeApp()
    }
}

/**
 * First-launch setup wizard (ONB-01): welcome, then one step per setup flow, every one of them
 * skippable (ONB-07). Steps embed the app's real forms rather than re-implementing them, so
 * whatever is created here is exactly what the matching screen would have created. [onFinished]
 * fires on completing, on skipping out, and on the header's close — finishing is the only exit.
 */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vehicleDao = remember { RidesafeDatabase.getInstance(context).vehicleDao() }

    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    // The car created by the car step, which the Bluetooth step maps devices onto.
    var vehicleId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Latch against a double-tapped save inserting the car twice while the first insert runs.
    var savingCar by remember { mutableStateOf(false) }

    // Advances only while [from] is still the showing step: a double-tapped skip or a save
    // event outracing recomposition would otherwise advance twice and swallow a step — the
    // same defence the app shell's popOwn() applies to its back stack. Callers pass the step
    // their page was composed for, never the live value.
    fun advanceFrom(from: OnboardingStep) {
        if (step != from) return
        stepAfter(from, hasVehicle = vehicleId != null)?.let { step = it } ?: onFinished()
    }

    // System back walks the wizard, not out of the app, except on the first step. A stale
    // second back is harmless here: before the first step it resolves to null and stops.
    BackHandler(enabled = step != OnboardingStep.WELCOME) {
        stepBefore(step, hasVehicle = vehicleId != null)?.let { step = it }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            // The welcome page carries its own start/skip choice; the header would double it.
            if (step != OnboardingStep.WELCOME) {
                val shown = step
                WizardHeader(
                    steps = onboardingSteps(hasVehicle = vehicleId != null),
                    current = shown,
                    onSkip = { advanceFrom(shown) },
                    onClose = onFinished,
                )
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    // Same language as the app shell: forward slides the new step in over a fade,
                    // back fades in what returns while the leaving step slides out right.
                    if (targetState.ordinal >= initialState.ordinal) {
                        slideInHorizontally(tween(SLIDE_MS)) { it } togetherWith fadeOut(tween(SLIDE_MS))
                    } else {
                        fadeIn(tween(SLIDE_MS)) togetherWith slideOutHorizontally(tween(SLIDE_MS)) { it }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                label = "onboardingStep",
            ) { target ->
                when (target) {
                    OnboardingStep.WELCOME ->
                        WelcomePage(
                            onStart = { advanceFrom(OnboardingStep.WELCOME) },
                            onSkipAll = onFinished,
                        )

                    OnboardingStep.CAR ->
                        CarPage(
                            onSave = { vehicle, makePrimary ->
                                if (!savingCar) {
                                    savingCar = true
                                    scope.launch {
                                        vehicleId = vehicleDao.addVehicle(vehicle, makePrimary)
                                        // Advance only once the row exists — the next step reads it.
                                        advanceFrom(OnboardingStep.CAR)
                                        savingCar = false
                                    }
                                }
                            },
                            onSkip = { advanceFrom(OnboardingStep.CAR) },
                        )

                    OnboardingStep.BLUETOOTH ->
                        BluetoothPage(
                            vehicleDao = vehicleDao,
                            vehicleId = requireNotNull(vehicleId),
                            onContinue = { advanceFrom(OnboardingStep.BLUETOOTH) },
                        )

                    OnboardingStep.AUTO_TRACK -> AutoTrackPage(onContinue = { advanceFrom(OnboardingStep.AUTO_TRACK) })

                    OnboardingStep.PLACE ->
                        PlacePage(
                            onSaved = { advanceFrom(OnboardingStep.PLACE) },
                            onSkip = { advanceFrom(OnboardingStep.PLACE) },
                        )

                    OnboardingStep.RECORDING -> RecordingPage(onContinue = { advanceFrom(OnboardingStep.RECORDING) })

                    OnboardingStep.SCORES -> ScoresPage(onFinish = { advanceFrom(OnboardingStep.SCORES) })
                }
            }
        }
    }
}

/** Step dots, a skip and a leave-the-flow close — the wizard's one row of chrome (ONB-07). */
@Composable
private fun WizardHeader(
    steps: List<OnboardingStep>,
    current: OnboardingStep,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    val position = steps.indexOf(current) + 1
    val progressLabel = stringResource(R.string.onboarding_step_progress, position, steps.size)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f).semantics { contentDescription = progressLabel },
        ) {
            steps.forEach { s ->
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (s == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                )
            }
        }
        TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip_step)) }
        IconButton(onClick = onClose) {
            MaterialSymbol(
                symbolName = "close",
                contentDescription = stringResource(R.string.onboarding_leave),
            )
        }
    }
}

/**
 * Shared page frame: scrolling body, pinned primary action, optional secondary action under it.
 * Pinning matters — the action must stay reachable when the body outgrows a short window
 * (landscape, split screen), where a scrolled-away button reads as a dead end.
 */
@Composable
private fun StepPage(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondary: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
            Text(primaryLabel)
        }
        secondary?.invoke(this)
    }
}

/** ONB-01: the pitch — what Ridesafe does, and that all of it stays on the phone (NFR-01). */
@Composable
private fun WelcomePage(
    onStart: () -> Unit,
    onSkipAll: () -> Unit,
) {
    StepPage(
        primaryLabel = stringResource(R.string.onboarding_welcome_start),
        onPrimary = onStart,
        secondary = {
            TextButton(onClick = onSkipAll, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.onboarding_welcome_skip))
            }
        },
    ) {
        Spacer(Modifier.height(32.dp))
        MaterialSymbol(
            symbolName = "directions_car",
            contentDescription = null,
            size = 64.dp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FeatureRow(
            symbolName = "route",
            title = stringResource(R.string.onboarding_feature_rides_title),
            body = stringResource(R.string.onboarding_feature_rides_body),
        )
        FeatureRow(
            symbolName = "health_and_safety",
            title = stringResource(R.string.onboarding_feature_scores_title),
            body = stringResource(R.string.onboarding_feature_scores_body),
        )
        FeatureRow(
            symbolName = "lock",
            title = stringResource(R.string.onboarding_feature_privacy_title),
            body = stringResource(R.string.onboarding_feature_privacy_body),
        )
    }
}

/**
 * ONB-02: create the first car (GAR-02) with the garage's real add form, so the fields, the
 * validation and the primary handling are exactly the Garage tab's. The form's own close (its
 * cancel) skips the step.
 */
@Composable
private fun CarPage(
    onSave: (vehicle: Vehicle, makePrimary: Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.onboarding_car_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        VehicleFormScreen(
            existing = null,
            onSave = onSave,
            onBack = onSkip,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * ONB-03: map paired Bluetooth devices to the created car (GAR-08) — the key to auto-detection
 * (TRK-02) and to assigning rides to the right vehicle (TRK-08). Reuses the garage's tracking
 * card and picker, including the request-on-tap for BLUETOOTH_CONNECT.
 */
@Composable
private fun BluetoothPage(
    vehicleDao: VehicleDao,
    vehicleId: Long,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vehicle by vehicleDao.observe(vehicleId).collectAsState(initial = null)
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showPicker = true
        }

    StepPage(primaryLabel = stringResource(R.string.onboarding_continue), onPrimary = onContinue) {
        StepIntro(
            symbolName = "bluetooth",
            title = stringResource(R.string.onboarding_bluetooth_title),
            body = stringResource(R.string.onboarding_bluetooth_body),
        )
        TrackingCard(
            devices = vehicle?.bluetoothDevices.orEmpty(),
            onLink = {
                if (AppPermission.BLUETOOTH.isGranted(context)) {
                    showPicker = true
                } else {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
            onRemove = { address ->
                vehicle?.let { v ->
                    scope.launch {
                        vehicleDao.update(v.copy(bluetoothDevices = v.bluetoothDevices.filterNot { it.address == address }))
                    }
                }
            },
        )
    }

    if (showPicker) {
        val linkedAddresses =
            vehicle
                ?.bluetoothDevices
                .orEmpty()
                .map { it.address }
                .toSet()
        BluetoothPickerDialog(
            devices = BluetoothDevices.bonded(context).filterNot { it.address in linkedAddresses },
            onPick = { device ->
                showPicker = false
                vehicle?.let { v ->
                    scope.launch { vehicleDao.update(v.copy(bluetoothDevices = v.bluetoothDevices + device)) }
                }
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * ONB-04: opt into automatic recording (SET-06) — the one step that requests permissions, and
 * only after the user flips the switch, keeping the app's ask-when-enabled rule (NFR-05). The
 * existing Settings alert card handles the actual granting: request order, the settings
 * deep-link for background location, and the spent-dialog fallback. Enabling picks the
 * PAIRED_ONLY mode (the SET-06 default recommendation); the mode screen in Settings has the rest.
 */
@Composable
private fun AutoTrackPage(onContinue: () -> Unit) {
    val context = LocalContext.current
    val enabled = AutoTrackPrefs.get(context) != AutoTrackMode.OFF

    // Grants can land in the system settings app (background location); re-read on return.
    LifecycleResumeEffect(enabled) {
        PermissionState.refresh(context)
        onPauseOrDispose { }
    }

    StepPage(primaryLabel = stringResource(R.string.onboarding_continue), onPrimary = onContinue) {
        StepIntro(
            symbolName = "autoplay",
            title = stringResource(R.string.onboarding_autotrack_title),
            body = stringResource(R.string.onboarding_autotrack_body),
        )
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_autotrack_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { turnOn ->
                        applyAutoTrackMode(context, if (turnOn) AutoTrackMode.PAIRED_ONLY else AutoTrackMode.OFF)
                        PermissionState.refresh(context)
                    },
                )
            }
        }
        // Renders only while something is missing, so granting everything clears the page down
        // to its switch — the built-in "you're done" signal.
        PermissionAlertCard()
    }
}

/**
 * ONB-05: save a first place (ADR-01/05) with the real address editor, preset to the Home
 * shortcut — or to a custom place on a replay where Home already exists (each shortcut is a
 * singleton). Saving goes through [SavedAddressViewModel] so rides are re-matched (ADR-07),
 * which matters when replaying with a logbook.
 */
@Composable
private fun PlacePage(
    onSaved: () -> Unit,
    onSkip: () -> Unit,
) {
    val viewModel: SavedAddressViewModel = viewModel()
    val addresses by viewModel.addresses.collectAsState()
    // Latch against a double-tapped save inserting the place twice.
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.onboarding_place_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SavedAddressFormScreen(
            existing = null,
            presetKind =
                if (addresses.none { it.kind == SavedPlaceKind.HOME }) {
                    SavedPlaceKind.HOME
                } else {
                    SavedPlaceKind.CUSTOM
                },
            savedAddresses = addresses,
            onSave = {
                if (!saved) {
                    saved = true
                    viewModel.add(it)
                    onSaved()
                }
            },
            onBack = onSkip,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * ONB-06: how a ride actually gets recorded — the automatic path just configured, and the manual
 * fallback (TRK-07) for a car without a linked device or a trigger that missed.
 */
@Composable
private fun RecordingPage(onContinue: () -> Unit) {
    StepPage(primaryLabel = stringResource(R.string.onboarding_continue), onPrimary = onContinue) {
        StepIntro(
            symbolName = "radio_button_checked",
            title = stringResource(R.string.onboarding_recording_title),
            body = stringResource(R.string.onboarding_recording_body),
        )
        FeatureRow(
            symbolName = "bluetooth",
            title = stringResource(R.string.onboarding_recording_auto_title),
            body = stringResource(R.string.onboarding_recording_auto_body),
        )
        FeatureRow(
            symbolName = "play_arrow",
            title = stringResource(R.string.onboarding_recording_manual_title),
            body =
                stringResource(
                    R.string.onboarding_recording_manual_body,
                    stringResource(R.string.home_record_start),
                ),
        )
        FeatureRow(
            symbolName = "stop_circle",
            title = stringResource(R.string.onboarding_recording_stop_title),
            body = stringResource(R.string.onboarding_recording_stop_body),
        )
    }
}

/**
 * ONB-06: what the safety score (ANL-01/DSH-06) and eco level (ANL-03) mean, shown on the very
 * cards the app uses — with sample values, clearly labelled as such, because a brand-new user has
 * nothing scored yet and the dashboard hides both cards until a first ride is analyzed. Last
 * step, so its primary action finishes the flow.
 */
@Composable
private fun ScoresPage(onFinish: () -> Unit) {
    StepPage(primaryLabel = stringResource(R.string.onboarding_done_cta), onPrimary = onFinish) {
        StepIntro(
            symbolName = "health_and_safety",
            title = stringResource(R.string.onboarding_scores_title),
            body = stringResource(R.string.onboarding_scores_body),
        )
        Text(
            text = stringResource(R.string.onboarding_scores_sample_note),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        SafetyScoreCard(score = SampleScore)
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.ride_detail_section_eco),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                EcoLevelDisplay(level = 2)
            }
        }
    }
}

/** Plausible mid-90s-driver values for the explainer — the penalties are display-irrelevant. */
private val SampleScore =
    SafetyScore(
        total = 87,
        braking = 84,
        acceleration = 91,
        cornering = 88,
        brakingPenalty = 0.0,
        accelerationPenalty = 0.0,
        corneringPenalty = 0.0,
        qualifiedSeconds = 0.0,
    )

/** The icon + title + body block that opens every explainer-style step page. */
@Composable
private fun StepIntro(
    symbolName: String,
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MaterialSymbol(
            symbolName = symbolName,
            contentDescription = null,
            size = 48.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeatureRow(
    symbolName: String,
    title: String,
    body: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        MaterialSymbol(
            symbolName = symbolName,
            contentDescription = null,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun WelcomePreview() {
    RidesafeTheme {
        WelcomePage(onStart = {}, onSkipAll = {})
    }
}

@Preview
@Composable
private fun ScoresPreview() {
    RidesafeTheme {
        ScoresPage(onFinish = {})
    }
}
