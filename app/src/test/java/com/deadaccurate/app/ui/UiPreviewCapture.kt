package com.deadaccurate.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.deadaccurate.app.TimegrapherUiState
import com.deadaccurate.app.trace.TracePoint
import com.deadaccurate.app.ui.theme.DeadAccurateTheme
import com.deadaccurate.app.watchlog.Measurement
import com.deadaccurate.app.watchlog.WatchEntry
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Not a test of behavior: renders the real composables to PNG so UI work
// can be reviewed without a device. Writes into the module build dir.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h2200dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPreviewCapture {

    private fun render(name: String, widthPx: Int, heightPx: Int, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity).apply { setContent { DeadAccurateTheme(content) } }
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val out = File("build/ui-previews").apply { mkdirs() }
        FileOutputStream(File(out, name)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun measureScreen() {
        val trace = List(300) { i ->
            TracePoint(
                deviationMs = (sin(i / 18f) * 6f) + (i % 7 - 3) * 0.4f,
                accepted = true,
            )
        }
        render("measure.png", WIDTH_PX, HEIGHT_PX) {
            CaptureContent(
                state = TimegrapherUiState(
                    hasPermission = true,
                    capturing = true,
                    rateValid = true,
                    secPerDay = 3.1f,
                    beatErrorMs = 0.4f,
                    activeBph = 21600,
                    detectedBph = 21600,
                    rateLocked = true,
                    rateTickCount = 412,
                    rmsDb = -47f,
                    peakDb = -38f,
                    gateThresholdDb = -52f,
                    gateOpen = true,
                    measurementSettled = true,
                    amplitudeDeg = 271f,
                    liftTimeMs = 8.6f,
                    tracePoints = trace,
                    traceHalfRangeMs = 83f,
                    movementGuess = com.deadaccurate.app.watchlog.MovementGuesser.Guess(
                        "NH34",
                        "Blizzard",
                        0.96f,
                    ),
                ),
                actions = previewActions(),
            )
        }
    }

    @Test
    fun watchLogScreen() {
        val now = 1_754_300_000_000
        fun m(daysAgo: Int, rate: Float, be: Float) = Measurement(
            timestampMs = now - daysAgo * 86_400_000L,
            bph = 21600,
            secPerDay = rate,
            beatErrorMs = be,
            mode = "CORRELATION",
            bandScores = emptyList(),
        )
        render("watchlog.png", WIDTH_PX, HEIGHT_PX) {
            CaptureContent(
                state = TimegrapherUiState(
                    hasPermission = true,
                    showWatchLog = true,
                    watches = listOf(
                        WatchEntry(
                            id = "1",
                            name = "Blizzard",
                            movementRef = "NH34",
                            measurements = listOf(
                                m(0, -2.1f, 0.2f),
                                m(3, -2.8f, 0.3f),
                                m(9, -1.4f, 0.2f),
                                m(15, -3.9f, 0.4f),
                            ),
                        ),
                        WatchEntry(
                            id = "2",
                            name = "Seagull Tourbillon",
                            movementRef = "ST2533",
                            measurements = listOf(m(1, 202.4f, 0.7f), m(6, 198.9f, 0.9f)),
                        ),
                    ),
                ),
                actions = previewActions(),
            )
        }
    }

    private fun previewActions() = CaptureActions(
        onToggleCapture = {},
        onDismissInputLost = {},
        onSetBphOverride = {},
        onSetGateTrim = {},
        onRecalibrate = {},
        onSetInputPreference = {},
        onSetAnalysisMode = {},
        onDismissOnboarding = {},
        onReplayFile = {},
        onRunDemo = {},
        onExportSession = {},
        onExportHandled = {},
        onRecordDiagnostic = {},
        onAdjustClockCal = {},
        onSetLiftAngle = {},
        watchLog = WatchLogActions(
            onOpenSaveDialog = {},
            onDismissSaveDialog = {},
            onSaveResult = { _, _, _ -> },
            onShowWatchLog = {},
            onUpdateWatch = { _, _, _ -> },
            onDeleteWatch = {},
        ),
    )

    private companion object {
        const val WIDTH_PX = 824
        const val HEIGHT_PX = 3000
    }
}
