package com.deadaccurate.app

import android.app.Application
import android.media.AudioManager
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.deadaccurate.app.audio.AudioInputMonitor
import com.deadaccurate.app.audio.RawRecorder
import com.deadaccurate.app.export.SessionExporter
import com.deadaccurate.app.export.SessionTick
import com.deadaccurate.app.replay.DemoSignal
import com.deadaccurate.app.replay.ReplayAnalyzer
import com.deadaccurate.app.replay.WavReader
import com.deadaccurate.app.replay.WavWriter
import com.deadaccurate.app.settings.AnalysisMode
import com.deadaccurate.app.settings.InputPreference
import java.io.IOException
import com.deadaccurate.app.settings.SettingsRepository
import com.deadaccurate.app.settings.settingsDataStore
import com.deadaccurate.app.trace.TraceComputer
import com.deadaccurate.app.trace.TracePoint
import com.deadaccurate.app.watchlog.Measurement
import com.deadaccurate.app.watchlog.MovementGuesser
import com.deadaccurate.app.watchlog.WatchEntry
import com.deadaccurate.app.watchlog.WatchLogRepository
import java.io.File
import com.deadaccurate.engine.AudioEngine
import com.deadaccurate.engine.EngineEvent
import com.deadaccurate.engine.EngineState
import com.deadaccurate.engine.InputPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StreamInfo(
    val sampleRate: Int,
    val unprocessed: Boolean,
    val exclusiveMode: Boolean,
)

/**
 * A finished measurement, frozen the moment the save dialog opens so the
 * numbers can't wiggle under the user while they type a watch name.
 */
data class PendingResult(
    val bph: Int,
    /** Corrected (includes the clock calibration) — the number that matters. */
    val secPerDay: Float,
    val beatErrorMs: Float?,
    val mode: AnalysisMode,
    /** Acoustic signature captured with the result; teaches the guesser. */
    val bandScores: List<Float>,
    val guess: MovementGuesser.Guess?,
)

data class TimegrapherUiState(
    val hasPermission: Boolean = false,
    val capturing: Boolean = false,
    val rmsDb: Float = SILENCE_DB,
    val peakDb: Float = SILENCE_DB,
    val gateThresholdDb: Float? = null,
    val gateOpen: Boolean = false,
    val calibrating: Boolean = false,
    val gateTrimDb: Float = 0f,
    /** null = auto-detect (FR-4); a value pins the rate. */
    val bphOverride: Int? = null,
    /** Rate in effect (override or locked detection); 0 while searching. */
    val activeBph: Int = 0,
    /** Detector's own lock; keeps reporting under an override. */
    val detectedBph: Int = 0,
    val rateLocked: Boolean = false,
    val rateValid: Boolean = false,
    /** Raw measurement against the device audio clock. */
    val secPerDay: Float = 0f,
    /** User-measured correction for the audio crystal's ppm error. */
    val clockCalSecPerDay: Float = 0f,
    val beatErrorMs: Float? = null,
    val rateTickCount: Int = 0,
    val tracePoints: List<TracePoint> = emptyList(),
    val traceHalfRangeMs: Float = DEFAULT_HALF_RANGE_MS,
    val wiredInputName: String? = null,
    val inputPreference: InputPreference = InputPreference.AUTO,
    val analysisMode: AnalysisMode = AnalysisMode.EDGE,
    val inputLost: Boolean = false,
    val noTicksHint: Boolean = false,
    val onboardingDismissed: Boolean = true,
    val unprocessedSupported: Boolean = true,
    val streamInfo: StreamInfo? = null,
    val startErrorCode: Int? = null,
    /** Name of the WAV being (or last) analyzed offline; null = live mode. */
    val replayFileName: String? = null,
    val replayError: String? = null,
    /** Set when an export is ready; the screen launches the share sheet. */
    val exportUri: Uri? = null,
    /** Diagnostic recording in progress: seconds remaining. */
    val recordingSecondsLeft: Int? = null,
    /** Set when a diagnostic WAV is ready to share. */
    val recordUri: Uri? = null,
    /** The rate reading has been stable long enough to call it done. */
    val measurementSettled: Boolean = false,
    /** Saved watches with their measurement history, newest first. */
    val watches: List<WatchEntry> = emptyList(),
    val showSaveDialog: Boolean = false,
    val pendingResult: PendingResult? = null,
    val showWatchLog: Boolean = false,
) {
    /** What the readout shows: measurement plus the clock correction. */
    val correctedSecPerDay: Float get() = secPerDay + clockCalSecPerDay

    /** The pinned rate contradicts what the signal actually looks like. */
    val overrideDisagrees: Boolean
        get() = bphOverride != null && detectedBph > 0 && detectedBph != bphOverride

    companion object {
        const val SILENCE_DB = -120f
        const val DEFAULT_HALF_RANGE_MS = 62.5f
        val STANDARD_RATES = listOf(14400, 16200, 18000, 19800, 21600, 25200, 28800, 36000)
        const val TRACE_CAPACITY = 480
    }
}

class TimegrapherViewModel(application: Application) : AndroidViewModel(application) {

    private val audioManager = requireNotNull(application.getSystemService<AudioManager>())
    private val inputMonitor = AudioInputMonitor(audioManager)
    private val engine = AudioEngine()
    private val settingsRepository = SettingsRepository(application.settingsDataStore)
    private val replayAnalyzer = ReplayAnalyzer(application.contentResolver)
    private val watchLog = WatchLogRepository(File(application.filesDir, "watch_log.json"))

    private val unprocessedSupported: Boolean =
        audioManager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED,
        ) == "true"

    private val _uiState = MutableStateFlow(
        TimegrapherUiState(unprocessedSupported = unprocessedSupported),
    )
    val uiState: StateFlow<TimegrapherUiState> = _uiState

    private var eventJob: Job? = null
    private var traceComputer: TraceComputer? = null
    private var traceBph = 0
    private val traceBuffer = ArrayDeque<TracePoint>()
    private val sessionTicks = ArrayDeque<SessionTick>()
    private var levelsSinceTick = 0

    // Latest acoustic signature from the engine (kept out of UiState — it
    // updates ~2 Hz and nothing recomposes on it).
    private var lastSignature: List<Float> = emptyList()
    private var lastSignatureBph = 0

    // Settle detection: recent valid sec/day readings for the current rate.
    private val settleWindow = ArrayDeque<Float>()
    private var settleBph = 0
    private var resultPromptShown = false

    init {
        viewModelScope.launch {
            inputMonitor.wiredInput.collect { device ->
                _uiState.update { state ->
                    state.copy(wiredInputName = device?.productName?.toString())
                }
            }
        }
        viewModelScope.launch { loadSettings() }
        viewModelScope.launch {
            watchLog.load()
            watchLog.watches.collect { entries ->
                _uiState.update { it.copy(watches = entries) }
            }
        }
    }

    private suspend fun loadSettings() {
        val stored = settingsRepository.settings.first()
        engine.setGateTrimDb(stored.gateTrimDb)
        engine.setBphOverride(stored.bphOverride ?: 0)
        engine.setAnalysisMode(stored.analysisMode.native)
        _uiState.update {
            it.copy(
                gateTrimDb = stored.gateTrimDb,
                bphOverride = stored.bphOverride,
                inputPreference = stored.inputPreference,
                analysisMode = stored.analysisMode,
                onboardingDismissed = stored.onboardingDismissed,
                clockCalSecPerDay = stored.clockCalSecPerDay,
            )
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasPermission = granted) }
        // Revoked mid-session (e.g. via system settings): release the stream
        // and return to the rationale screen.
        if (!granted && _uiState.value.capturing) {
            stopCapture()
        }
    }

    fun toggleCapture() {
        if (_uiState.value.capturing) stopCapture() else startCapture()
    }

    fun dismissInputLost() {
        _uiState.update { it.copy(inputLost = false) }
    }

    /** [bph] null returns to auto-detection. */
    fun setBphOverride(bph: Int?) {
        engine.setBphOverride(bph ?: 0)
        _uiState.update { it.copy(bphOverride = bph) }
        // The trace resets when the engine reports the new active rate.
        viewModelScope.launch { settingsRepository.setBphOverride(bph) }
    }

    fun setGateTrimDb(trimDb: Float) {
        engine.setGateTrimDb(trimDb)
        _uiState.update { it.copy(gateTrimDb = trimDb) }
        viewModelScope.launch { settingsRepository.setGateTrimDb(trimDb) }
    }

    /** Switches detection path; applies live, the trace restarts. */
    fun setAnalysisMode(mode: AnalysisMode) {
        if (mode == _uiState.value.analysisMode) return
        engine.setAnalysisMode(mode.native)
        _uiState.update { it.copy(analysisMode = mode) }
        viewModelScope.launch { settingsRepository.setAnalysisMode(mode) }
        // Edge draws per-tick dots, correlation draws the folded phase —
        // the two traces don't mix.
        clearAnalysis()
    }

    fun setInputPreference(preference: InputPreference) {
        _uiState.update { it.copy(inputPreference = preference) }
        viewModelScope.launch { settingsRepository.setInputPreference(preference) }
        // A new input needs a new stream.
        if (_uiState.value.capturing) {
            stopCapture()
            startCapture()
        }
    }

    fun dismissOnboarding() {
        _uiState.update { it.copy(onboardingDismissed = true) }
        viewModelScope.launch { settingsRepository.setOnboardingDismissed(true) }
    }

    /** Adjusts the clock calibration by [deltaSecPerDay]; 0 delta resets. */
    fun adjustClockCal(deltaSecPerDay: Float) {
        val value =
            if (deltaSecPerDay == 0f) {
                0f
            } else {
                (_uiState.value.clockCalSecPerDay + deltaSecPerDay)
                    .coerceIn(-CLOCK_CAL_LIMIT, CLOCK_CAL_LIMIT)
            }
        _uiState.update { it.copy(clockCalSecPerDay = value) }
        viewModelScope.launch { settingsRepository.setClockCalSecPerDay(value) }
    }

    fun recalibrateGate() {
        engine.recalibrateGate()
    }

    /** Analyzes a recorded WAV through the same chain as live capture. */
    fun replayFile(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "recording"
        analyzeOffline(name) { onStart ->
            replayAnalyzer.analyze(
                uri = uri,
                config = ReplayAnalyzer.Config(
                    bphOverride = _uiState.value.bphOverride ?: 0,
                    gateTrimDb = _uiState.value.gateTrimDb,
                    analysisMode = _uiState.value.analysisMode.native,
                ),
                onStart = onStart,
                onEvent = ::onEngineEvent,
            )
        }
    }

    /** Runs the bundled synthetic movement — a zero-hardware demo. */
    fun runDemo() {
        analyzeOffline("demo movement (synthetic)") { onStart ->
            // Gate trim forced to 0: the demo must always produce a clean
            // result regardless of the user's live-capture settings.
            replayAnalyzer.analyzeSamples(
                sampleRate = DemoSignal.SAMPLE_RATE,
                samples = DemoSignal.generate(),
                config = ReplayAnalyzer.Config(
                    bphOverride = _uiState.value.bphOverride ?: 0,
                    gateTrimDb = 0f,
                    analysisMode = _uiState.value.analysisMode.native,
                ),
                onStart = onStart,
                onEvent = ::onEngineEvent,
            )
        }
    }

    private fun analyzeOffline(
        sourceName: String,
        block: suspend (onStart: (Int) -> Unit) -> Unit,
    ) {
        if (_uiState.value.capturing) stopCapture()
        viewModelScope.launch {
            clearAnalysis()
            _uiState.update { it.copy(replayFileName = sourceName, replayError = null) }
            try {
                block { sampleRate ->
                    _uiState.update {
                        it.copy(
                            streamInfo = StreamInfo(
                                sampleRate = sampleRate,
                                unprocessed = false,
                                exclusiveMode = false,
                            ),
                        )
                    }
                }
            } catch (e: WavReader.UnsupportedWavException) {
                _uiState.update { it.copy(replayError = e.message) }
            } catch (e: IOException) {
                _uiState.update {
                    it.copy(replayError = e.message ?: "Could not read the recording")
                }
            }
        }
    }

    private fun clearAnalysis() {
        traceBph = 0
        traceComputer = null
        traceBuffer.clear()
        sessionTicks.clear()
        levelsSinceTick = 0
        settleWindow.clear()
        settleBph = 0
        resultPromptShown = false
        lastSignature = emptyList()
        lastSignatureBph = 0
        _uiState.update {
            it.copy(
                tracePoints = emptyList(),
                activeBph = 0,
                detectedBph = 0,
                rateLocked = false,
                rateValid = false,
                beatErrorMs = null,
                rateTickCount = 0,
                noTicksHint = false,
                streamInfo = null,
                measurementSettled = false,
                showSaveDialog = false,
                pendingResult = null,
            )
        }
    }

    private fun startCapture() {
        val deviceId =
            if (_uiState.value.inputPreference == InputPreference.BUILT_IN) {
                SYSTEM_DEFAULT_DEVICE
            } else {
                inputMonitor.wiredInput.value?.id ?: SYSTEM_DEFAULT_DEVICE
            }
        val preset =
            if (_uiState.value.unprocessedSupported) {
                InputPreset.UNPROCESSED
            } else {
                InputPreset.VOICE_RECOGNITION
            }

        // The DSP chain reads these when its thread spins up.
        engine.setBphOverride(_uiState.value.bphOverride ?: 0)
        engine.setGateTrimDb(_uiState.value.gateTrimDb)
        engine.setAnalysisMode(_uiState.value.analysisMode.native)

        val result = engine.start(deviceId, preset)
        if (result != 0) {
            _uiState.update { it.copy(startErrorCode = result) }
            return
        }

        eventJob = viewModelScope.launch {
            engine.events.collect(::onEngineEvent)
        }
        clearAnalysis()
        _uiState.update {
            it.copy(
                capturing = true,
                inputLost = false,
                startErrorCode = null,
                replayFileName = null,
                replayError = null,
            )
        }
    }

    private fun stopCapture() {
        engine.stop()
        eventJob?.cancel()
        eventJob = null
        _uiState.update {
            it.copy(
                capturing = false,
                rmsDb = TimegrapherUiState.SILENCE_DB,
                peakDb = TimegrapherUiState.SILENCE_DB,
                gateThresholdDb = null,
                gateOpen = false,
                calibrating = false,
                rateValid = false,
                noTicksHint = false,
                streamInfo = null,
            )
        }
    }

    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Level -> onLevel(event)

            is EngineEvent.Tick -> onTick(event)

            is EngineEvent.Phase -> onPhase(event)

            is EngineEvent.Rate -> onRate(event)

            is EngineEvent.Signature -> {
                lastSignature = event.bandScores
                lastSignatureBph = event.bph
            }

            is EngineEvent.Status -> onStatus(event)
        }
    }

    private fun onLevel(event: EngineEvent.Level) {
        // Level frames arrive at ~30 Hz; NO_TICKS_LEVEL_FRAMES of them with
        // no tick means nothing is clearing the gate (architecture §6).
        if (event.calibrating) {
            levelsSinceTick = 0
        } else {
            ++levelsSinceTick
        }
        val showHint = levelsSinceTick > NO_TICKS_LEVEL_FRAMES
        _uiState.update {
            it.copy(
                rmsDb = event.rmsDb,
                peakDb = event.peakDb,
                gateThresholdDb = event.gateThresholdDb,
                gateOpen = event.gateOpen,
                calibrating = event.calibrating,
                noTicksHint = showHint,
            )
        }
    }

    private fun onStatus(event: EngineEvent.Status) {
        when (event.state) {
            EngineState.RUNNING -> {
                _uiState.update {
                    it.copy(
                        streamInfo = StreamInfo(
                            sampleRate = event.sampleRate,
                            unprocessed = event.unprocessed,
                            exclusiveMode = event.exclusiveMode,
                        ),
                    )
                }
                // New stream, new audio clock: restart the tape.
                if (traceBph > 0) resetTrace(traceBph)
            }

            // FR-2: on input loss, stop and prompt instead of silently
            // degrading to another route.
            EngineState.DISCONNECTED -> {
                stopCapture()
                _uiState.update { it.copy(inputLost = true) }
            }

            EngineState.IDLE, EngineState.ERROR -> Unit
        }
    }

    private fun onRate(event: EngineEvent.Rate) {
        // The trace follows the active grid; when confidence is lost
        // (activeBph 0) it keeps drawing against the last locked grid.
        if (event.activeBph > 0 && event.activeBph != traceBph) {
            resetTrace(event.activeBph)
        }
        val settled = updateSettle(event)
        _uiState.update {
            it.copy(
                activeBph = event.activeBph,
                detectedBph = event.detectedBph,
                rateLocked = event.locked,
                rateValid = event.rateValid,
                secPerDay = event.secPerDay,
                beatErrorMs = event.beatErrorMs,
                rateTickCount = event.tickCount,
                measurementSettled = settled,
            )
        }
        // The reading just settled for the first time this session: this is
        // "the measurement finished" — offer to save it, once.
        if (settled && !resultPromptShown) {
            resultPromptShown = true
            openSaveDialog()
        }
    }

    /** True when the recent valid readings have stopped drifting. */
    private fun updateSettle(event: EngineEvent.Rate): Boolean {
        if (!event.rateValid || event.activeBph == 0) {
            settleWindow.clear()
            return false
        }
        if (event.activeBph != settleBph) {
            settleBph = event.activeBph
            settleWindow.clear()
            resultPromptShown = false
        }
        settleWindow.addLast(event.secPerDay)
        while (settleWindow.size > SETTLE_FRAMES) settleWindow.removeFirst()
        return settleWindow.size >= SETTLE_FRAMES &&
            (settleWindow.max() - settleWindow.min()) <= SETTLE_RANGE_SEC_PER_DAY
    }

    /** Correlation-mode trace: the folded peak's drift, one dot per ~0.5 s. */
    private fun onPhase(phase: EngineEvent.Phase) {
        levelsSinceTick = 0
        traceBuffer.addLast(TracePoint(phase.phaseDeviationMs, accepted = true))
        while (traceBuffer.size > TimegrapherUiState.TRACE_CAPACITY) {
            traceBuffer.removeFirst()
        }
        _uiState.update {
            it.copy(
                tracePoints = traceBuffer.toList(),
                traceHalfRangeMs = phase.periodMs / 2f,
            )
        }
    }

    private fun onTick(tick: EngineEvent.Tick) {
        levelsSinceTick = 0
        val computer = traceComputer ?: return
        val deviationMs = computer.addTick(tick.deltaFrames)
        traceBuffer.addLast(TracePoint(deviationMs, tick.accepted))
        while (traceBuffer.size > TimegrapherUiState.TRACE_CAPACITY) {
            traceBuffer.removeFirst()
        }
        sessionTicks.addLast(SessionTick(computer.timestampMs, deviationMs, tick.accepted))
        while (sessionTicks.size > SESSION_TICK_CAPACITY) {
            sessionTicks.removeFirst()
        }
        _uiState.update { it.copy(tracePoints = traceBuffer.toList()) }
    }

    /** Writes the session CSV and hands a share Uri to the UI. */
    fun exportSession() {
        val ticks = sessionTicks.toList()
        val state = _uiState.value
        if (ticks.isEmpty() || state.activeBph == 0) return
        val summary = SessionExporter.Summary(
            source = state.replayFileName?.let { "replay:$it" } ?: "live",
            bph = state.activeBph,
            rawSecPerDay = state.secPerDay,
            clockCalSecPerDay = state.clockCalSecPerDay,
            beatErrorMs = state.beatErrorMs,
            sampleRate = state.streamInfo?.sampleRate,
        )
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                SessionExporter.writeForSharing(
                    getApplication(),
                    SessionExporter.buildCsv(summary, ticks),
                )
            }
            _uiState.update { it.copy(exportUri = uri) }
        }
    }

    /** The screen has launched the share sheet for the current export. */
    fun onExportHandled() {
        _uiState.update { it.copy(exportUri = null, recordUri = null) }
    }

    /**
     * Records 60 s of exactly what the analysis hears and offers the WAV
     * for sharing — the raw material for tuning against real signals.
     */
    fun recordDiagnostic() {
        if (_uiState.value.recordingSecondsLeft != null) return
        if (_uiState.value.capturing) stopCapture()
        viewModelScope.launch {
            _uiState.update { it.copy(recordingSecondsLeft = RECORD_SECONDS) }
            try {
                val recording = RawRecorder().record(
                    seconds = RECORD_SECONDS,
                    unprocessedSupported = unprocessedSupported,
                ) { left ->
                    _uiState.update { it.copy(recordingSecondsLeft = left) }
                }
                val uri = withContext(Dispatchers.IO) {
                    SessionExporter.writeBytesForSharing(
                        getApplication(),
                        "deadaccurate-recording.wav",
                        WavWriter.toWavBytes(recording.samples, recording.sampleRate),
                    )
                }
                _uiState.update { it.copy(recordUri = uri) }
            } catch (e: IllegalStateException) {
                _uiState.update { it.copy(replayError = e.message) }
            } finally {
                _uiState.update { it.copy(recordingSecondsLeft = null) }
            }
        }
    }

    /** Freezes the current reading and opens the save dialog. */
    fun openSaveDialog() {
        val state = _uiState.value
        if (!state.rateValid || state.activeBph == 0) return
        // The signature is only meaningful if the folding path agrees on
        // the rate being displayed (it always runs, in either mode).
        val scores = if (lastSignatureBph == state.activeBph) lastSignature else emptyList()
        val guess = MovementGuesser.guess(state.activeBph, scores, watchLog.watches.value)
        _uiState.update {
            it.copy(
                showSaveDialog = true,
                pendingResult = PendingResult(
                    bph = state.activeBph,
                    secPerDay = state.correctedSecPerDay,
                    beatErrorMs = state.beatErrorMs,
                    mode = state.analysisMode,
                    bandScores = scores,
                    guess = guess,
                ),
            )
        }
    }

    fun dismissSaveDialog() {
        _uiState.update { it.copy(showSaveDialog = false, pendingResult = null) }
    }

    /**
     * Saves the frozen result to [watchId] (or a new watch named
     * [newWatchName]); a non-blank [movementRef] labels the calibre and
     * teaches the movement recognizer.
     */
    fun saveResult(watchId: String?, newWatchName: String?, movementRef: String?) {
        val pending = _uiState.value.pendingResult ?: return
        viewModelScope.launch {
            watchLog.saveMeasurement(
                watchId = watchId,
                newWatchName = newWatchName,
                movementRef = movementRef,
                measurement = Measurement(
                    timestampMs = System.currentTimeMillis(),
                    bph = pending.bph,
                    secPerDay = pending.secPerDay,
                    beatErrorMs = pending.beatErrorMs,
                    mode = pending.mode.name,
                    bandScores = pending.bandScores,
                ),
            )
            _uiState.update { it.copy(showSaveDialog = false, pendingResult = null) }
        }
    }

    fun setShowWatchLog(show: Boolean) {
        _uiState.update { it.copy(showWatchLog = show) }
    }

    fun deleteWatch(watchId: String) {
        viewModelScope.launch { watchLog.deleteWatch(watchId) }
    }

    private fun resetTrace(bph: Int) {
        traceBph = bph
        val rate = _uiState.value.streamInfo?.sampleRate
        traceComputer = rate?.let { TraceComputer(it, bph) }
        traceBuffer.clear()
        _uiState.update {
            it.copy(
                tracePoints = emptyList(),
                traceHalfRangeMs = traceComputer?.halfPeriodMs
                    ?: TimegrapherUiState.DEFAULT_HALF_RANGE_MS,
            )
        }
    }

    override fun onCleared() {
        engine.release()
        inputMonitor.release()
    }

    private companion object {
        const val SYSTEM_DEFAULT_DEVICE = 0

        // ~5 s of 30 Hz level frames with no tick (architecture §6).
        const val NO_TICKS_LEVEL_FRAMES = 150

        // Beyond ±10 s/day the crystal isn't the problem.
        const val CLOCK_CAL_LIMIT = 10f

        // ~40 minutes at 8 ticks/s; oldest ticks roll off beyond this.
        const val SESSION_TICK_CAPACITY = 20_000

        // Long enough for the correlation path to lock, settle, and produce
        // a valid rate offline — 30 s clips end before the rate window fills.
        const val RECORD_SECONDS = 60

        // "Measurement finished": this many consecutive valid rate frames
        // (~2 Hz) spanning no more than this much s/day. The user watches
        // the number climb while the regression window fills; when it stops
        // moving, the result is offered for saving.
        const val SETTLE_FRAMES = 16
        const val SETTLE_RANGE_SEC_PER_DAY = 0.8f
    }
}
