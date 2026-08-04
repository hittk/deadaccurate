package com.deadaccurate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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
    )

    private fun setCapture(state: TimegrapherUiState, actions: CaptureActions = actions()) {
        compose.setContent {
            MaterialTheme { CaptureContent(state = state, actions = actions) }
        }
    }

    @Test
    fun searchingStateShowsPlaceholderReadout() {
        setCapture(TimegrapherUiState(hasPermission = true, capturing = true))
        compose.onNodeWithText("—.— s/d").assertExists()
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
        compose.onNodeWithText("+4.2 s/d").assertExists()
        compose.onNodeWithText("beat error 0.6 ms").assertExists()
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
        compose.onNodeWithText("+4.2 s/d").assertExists()
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
    fun replayFooterLabelsTheSource() {
        setCapture(
            TimegrapherUiState(
                hasPermission = true,
                replayFileName = "watch.wav",
                streamInfo = com.deadaccurate.app.StreamInfo(48000, false, false),
            ),
        )
        compose.onNodeWithText("48000 Hz • replay: watch.wav").assertExists()
    }
}
