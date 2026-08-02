package com.deadaccurate.app

import android.app.Application
import android.media.AudioManager
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deadaccurate.app.audio.AudioInputMonitor
import com.deadaccurate.app.settings.InputPreference
import com.deadaccurate.app.settings.SettingsRepository
import com.deadaccurate.app.settings.settingsDataStore
import com.deadaccurate.app.trace.TraceComputer
import com.deadaccurate.app.trace.TracePoint
import com.deadaccurate.engine.AudioEngine
import com.deadaccurate.engine.EngineEvent
import com.deadaccurate.engine.EngineState
import com.deadaccurate.engine.InputPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StreamInfo(
    val sampleRate: Int,
    val unprocessed: Boolean,
    val exclusiveMode: Boolean,
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
    val rateLocked: Boolean = false,
    val rateValid: Boolean = false,
    val secPerDay: Float = 0f,
    val beatErrorMs: Float? = null,
    val rateTickCount: Int = 0,
    val tracePoints: List<TracePoint> = emptyList(),
    val traceHalfRangeMs: Float = DEFAULT_HALF_RANGE_MS,
    val wiredInputName: String? = null,
    val inputPreference: InputPreference = InputPreference.AUTO,
    val inputLost: Boolean = false,
    val noTicksHint: Boolean = false,
    val onboardingDismissed: Boolean = true,
    val unprocessedSupported: Boolean = true,
    val streamInfo: StreamInfo? = null,
    val startErrorCode: Int? = null,
) {
    companion object {
        const val SILENCE_DB = -120f
        const val DEFAULT_HALF_RANGE_MS = 62.5f
        val STANDARD_RATES = listOf(18000, 19800, 21600, 25200, 28800, 36000)
        const val TRACE_CAPACITY = 480
    }
}

class TimegrapherViewModel(application: Application) : AndroidViewModel(application) {

    private val audioManager = requireNotNull(application.getSystemService<AudioManager>())
    private val inputMonitor = AudioInputMonitor(audioManager)
    private val engine = AudioEngine()
    private val settingsRepository = SettingsRepository(application.settingsDataStore)

    private val _uiState = MutableStateFlow(
        TimegrapherUiState(unprocessedSupported = unprocessedSourceSupported()),
    )
    val uiState: StateFlow<TimegrapherUiState> = _uiState

    private var eventJob: Job? = null
    private var traceComputer: TraceComputer? = null
    private var traceBph = 0
    private val traceBuffer = ArrayDeque<TracePoint>()
    private var levelsSinceTick = 0

    init {
        viewModelScope.launch {
            inputMonitor.wiredInput.collect { device ->
                _uiState.update { state ->
                    state.copy(wiredInputName = device?.productName?.toString())
                }
            }
        }
        viewModelScope.launch { loadSettings() }
    }

    private suspend fun loadSettings() {
        val stored = settingsRepository.settings.first()
        engine.setGateTrimDb(stored.gateTrimDb)
        engine.setBphOverride(stored.bphOverride ?: 0)
        _uiState.update {
            it.copy(
                gateTrimDb = stored.gateTrimDb,
                bphOverride = stored.bphOverride,
                inputPreference = stored.inputPreference,
                onboardingDismissed = stored.onboardingDismissed,
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

    fun recalibrateGate() {
        engine.recalibrateGate()
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

        val result = engine.start(deviceId, preset)
        if (result != 0) {
            _uiState.update { it.copy(startErrorCode = result) }
            return
        }

        eventJob = viewModelScope.launch {
            engine.events.collect(::onEngineEvent)
        }
        levelsSinceTick = 0
        _uiState.update {
            it.copy(
                capturing = true,
                inputLost = false,
                noTicksHint = false,
                startErrorCode = null,
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

            is EngineEvent.Rate -> onRate(event)

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
        _uiState.update {
            it.copy(
                activeBph = event.activeBph,
                rateLocked = event.locked,
                rateValid = event.rateValid,
                secPerDay = event.secPerDay,
                beatErrorMs = event.beatErrorMs,
                rateTickCount = event.tickCount,
            )
        }
    }

    private fun onTick(tick: EngineEvent.Tick) {
        levelsSinceTick = 0
        val computer = traceComputer ?: return
        traceBuffer.addLast(TracePoint(computer.addTick(tick.deltaFrames), tick.accepted))
        while (traceBuffer.size > TimegrapherUiState.TRACE_CAPACITY) {
            traceBuffer.removeFirst()
        }
        _uiState.update { it.copy(tracePoints = traceBuffer.toList()) }
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

    private fun unprocessedSourceSupported(): Boolean =
        audioManager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED,
        ) == "true"

    override fun onCleared() {
        engine.release()
        inputMonitor.release()
    }

    private companion object {
        const val SYSTEM_DEFAULT_DEVICE = 0

        // ~5 s of 30 Hz level frames with no tick (architecture §6).
        const val NO_TICKS_LEVEL_FRAMES = 150
    }
}
