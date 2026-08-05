package com.deadaccurate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.deadaccurate.app.TimegrapherUiState
import com.deadaccurate.app.trace.TracePoint
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI state tests (docs/04-milestones.md testing strategy), run on
 * the JVM via Robolectric so they execute in the normal unit-test task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h2000dp")
class TimegrapherScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun actions(
        onDismissInputLost: () -> Unit = {},
        onDismissOnboarding: () -> Unit = {},
        onSetAnalysisMode: (com.deadaccurate.app.settings.AnalysisMode) -> Unit = {},
    ) = CaptureActions(
        onToggleCapture = {},
        onDismissInputLost = onDismissInputLost,
        onSetBphOverride = {},
        onSetGateTrim = {},
        onRecalibrate = {},
        onSetInputPreference = {},
        onSetAnalysisMode = onSetAnalysisMode,
        onDismissOnboarding = onDismissOnboarding,
        onReplayFile = {},
        onRunDemo = {},
        onExportSession = {},
        onExportHandled = {},
        onRecordDiagnostic = {},
        onAdjustClockCal = {},
        watchLog = WatchLogActions(
            onOpenSaveDialog = {},
            onDismissSaveDialog = {},
            onSaveResult = { _, _, _ -> },
            onShowWatchLog = {},
            onUpdateWatch = { _, _, _ -> },
            onDeleteWatch = {},
        ),
    )

    private fun setCapture(state: TimegrapherUiState, actions: CaptureActions = actions()) {
        compose.setContent {
            MaterialTheme { CaptureContent(state = state, actions = actions) }
        }
    }

    @Test
    fun searchingStateShowsPlaceholderReadout() {
        setCapture(TimegrapherUiState(hasPermission = true, capturing = true))
        // Both the rate and beat-error cards read "—.—" while searching.
        compose.onAllNodesWithText("—.—").onFirst().assertExists()
        compose.onNodeWithText("SEC / DAY").assertExists()
        compose.onNodeWithText("searching for beat rate…").assertExists()
    }

    @Test
    fun lockedStateShowsRateBeatErrorAndStatus() {
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                capturing = true,
                rateValid = true,
                secPerDay = 4.2f,
                activeBph = 28800,
                detectedBph = 28800,
                rateLocked = true,
                rateTickCount = 214,
                beatErrorMs = 0.6f,
            ),
        )
        compose.onNodeWithText("+4.2").assertExists()
        compose.onNodeWithText("0.6").assertExists()
        compose.onNodeWithText("BEAT ERROR").assertExists()
        compose.onNodeWithText("locked 28800 bph • 214 ticks").assertExists()
    }

    @Test
    fun clockCalibrationIsAppliedAndDisclosed() {
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                capturing = true,
                rateValid = true,
                secPerDay = 5.9f,
                clockCalSecPerDay = -1.7f,
                activeBph = 28800,
                detectedBph = 28800,
                rateTickCount = 100,
            ),
        )
        compose.onNodeWithText("+4.2").assertExists()
        compose.onNodeWithText("locked 28800 bph • 100 ticks • cal -1.7 s/d").assertExists()
    }

    @Test
    fun inputLostNoticeShowsAndDismisses() {
        var dismissed = false
        setCapture(
            TimegrapherUiState(hasPermission = true, inputLost = true),
            actions(onDismissInputLost = { dismissed = true }),
        )
        compose.onNodeWithText(
            "Input lost — the wired microphone was disconnected. " +
                "Reconnect it and start again.",
        ).assertExists()
        compose.onNodeWithText("Dismiss").performScrollTo().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun noTicksHintAppears() {
        setCapture(TimegrapherUiState(hasPermission = true, noTicksHint = true))
        compose.onNodeWithText(
            "No ticks detected. Try tapping Recalibrate with the watch in " +
                "place, adjusting the gate trim, or pressing the watch more " +
                "firmly against the microphone.",
        ).assertExists()
    }

    @Test
    fun onboardingShowsAndDismisses() {
        var dismissed = false
        setCapture(
            TimegrapherUiState(hasPermission = true, onboardingDismissed = false),
            actions(onDismissOnboarding = { dismissed = true }),
        )
        compose.onNodeWithText("Getting a clean signal").assertExists()
        compose.onNodeWithText("Got it").performScrollTo().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun overrideDisagreementIsFlagged() {
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                capturing = true,
                bphOverride = 18000,
                activeBph = 18000,
                detectedBph = 28800,
            ),
        )
        compose.onNodeWithText(
            "The signal looks like 28800 bph, not the pinned 18000. " +
                "Tap Auto to trust the signal.",
        ).assertExists()
    }

    @Test
    fun exportDisabledWithoutASession() {
        setCapture(TimegrapherUiState(hasPermission = true))
        compose.onNodeWithText("ADVANCED ▼").performScrollTo().performClick()
        compose.onNodeWithText("Export CSV").assertIsNotEnabled()
    }

    @Test
    fun exportEnabledOnceTicksExist() {
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                tracePoints = listOf(TracePoint(1.5f, accepted = true)),
            ),
        )
        compose.onNodeWithText("ADVANCED ▼").performScrollTo().performClick()
        compose.onNodeWithText("Export CSV").performScrollTo().performClick()
    }

    @Test
    fun analysisModeSwitchInvokesCallback() {
        var selected: com.deadaccurate.app.settings.AnalysisMode? = null
        setCapture(
            TimegrapherUiState(hasPermission = true),
            actions(onSetAnalysisMode = { selected = it }),
        )
        compose.onNodeWithText("Phone mic · correlation").performScrollTo().performClick()
        assertTrue(selected == com.deadaccurate.app.settings.AnalysisMode.CORRELATION)
    }

    @Test
    fun permissionRationaleInvokesRequest() {
        var requested = false
        compose.setContent {
            MaterialTheme { PermissionRationale(onRequest = { requested = true }) }
        }
        compose.onNodeWithText("Allow microphone access").performClick()
        assertTrue(requested)
    }

    @Test
    fun correlationLockShowsFirstReadingCountdown() {
        // Locked at 21600 (6 beats/s) with 65 of 125 beats integrated:
        // 60 beats left = 10 s, plus ~12 s of regression window.
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                capturing = true,
                analysisMode = com.deadaccurate.app.settings.AnalysisMode.CORRELATION,
                activeBph = 21600,
                detectedBph = 21600,
                rateLocked = true,
                rateValid = false,
                rateTickCount = 65,
            ),
        )
        compose
            .onNodeWithText("locked 21600 bph • 65 ticks • first reading in ~22s")
            .assertExists()
    }

    @Test
    fun liveMovementGuessIsShownDuringTheTest() {
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                capturing = true,
                movementGuess = com.deadaccurate.app.watchlog.MovementGuesser.Guess(
                    movementRef = "NH34",
                    watchName = "Blizzard",
                    confidence = 0.95f,
                ),
            ),
        )
        compose.onNodeWithText("Sounds like a NH34").assertExists()
    }

    @Test
    fun settledMeasurementOffersToSave() {
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                capturing = true,
                rateValid = true,
                secPerDay = 3.1f,
                activeBph = 21600,
                measurementSettled = true,
            ),
        )
        compose.onNodeWithText("Save result ✓").assertExists()
        compose
            .onNodeWithText("Reading settled — stop the test to save the result.")
            .assertExists()
    }

    // The full SaveResultDialog is not composed under Robolectric — its
    // text fields never let the Robolectric idling strategy go idle (a
    // test-environment artifact, not an app bug). Its pieces are covered
    // instead: the summary content here and the picker chips below.
    @Test
    fun resultSummaryShowsNumbersAndGuess() {
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    ResultSummary(
                        com.deadaccurate.app.PendingResult(
                            bph = 21600,
                            secPerDay = 202.4f,
                            beatErrorMs = 0.7f,
                            mode = com.deadaccurate.app.settings.AnalysisMode.CORRELATION,
                            bandScores = listOf(1f, 2f, 3f, 8f),
                            guess = com.deadaccurate.app.watchlog.MovementGuesser.Guess(
                                movementRef = "ST2533",
                                watchName = "Seagull",
                                confidence = 0.97f,
                            ),
                        ),
                    )
                }
            }
        }
        compose.onNodeWithText("+202.4 s/day").assertExists()
        compose.onNodeWithText("21600 bph • beat error 0.7 ms").assertExists()
        compose.onNodeWithText("Sounds like a ST2533 (heard on “Seagull”)").assertExists()
    }

    @Test
    fun watchPickerSelectsAnExistingWatch() {
        var selected: String? = "unset"
        val watch = com.deadaccurate.app.watchlog.WatchEntry(
            id = "w1",
            name = "Seagull",
            movementRef = "ST2533",
            measurements = emptyList(),
        )
        compose.setContent {
            MaterialTheme {
                WatchPickerChips(
                    watches = listOf(watch),
                    selectedId = null,
                    onSelect = { selected = it },
                )
            }
        }
        compose.onNodeWithText("Seagull").performClick()
        assertTrue(selected == "w1")
    }

    @Test
    fun watchLogDialogListsHistory() {
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                showWatchLog = true,
                watches = listOf(
                    com.deadaccurate.app.watchlog.WatchEntry(
                        id = "w1",
                        name = "SKX007",
                        movementRef = "NH35",
                        measurements = listOf(
                            com.deadaccurate.app.watchlog.Measurement(
                                timestampMs = 1_722_800_000_000,
                                bph = 21600,
                                secPerDay = -2.3f,
                                beatErrorMs = 0.2f,
                                mode = "CORRELATION",
                                bandScores = emptyList(),
                            ),
                        ),
                    ),
                ),
            ),
        )
        compose.onNodeWithText("SKX007").assertExists()
        compose.onNodeWithText("Movement: NH35 • 1 readings").assertExists()
        // The flat all-readings view lists the same measurement with its
        // watch and movement info on the row.
        compose.onNodeWithText("All readings").performClick()
        compose.onNodeWithText("-2.3 s/d • 0.2 ms beat error • 21600 bph").assertExists()
    }

    @Test
    fun replayFooterLabelsTheSource() {
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                replayFileName = "watch.wav",
                streamInfo = com.deadaccurate.app.StreamInfo(48000, false, false),
            ),
        )
        compose.onNodeWithText("ADVANCED ▼").performScrollTo().performClick()
        compose.onNodeWithText("48000 Hz • replay: watch.wav").performScrollTo().assertExists()
    }
}
