package com.deadaccurate.app

import android.app.Application
import android.media.AudioManager
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deadaccurate.app.audio.AudioInputMonitor
import com.deadaccurate.app.trace.TraceComputer
import com.deadaccurate.app.trace.TracePoint
import com.deadaccurate.engine.AudioEngine
import com.deadaccurate.engine.EngineEvent
import com.deadaccurate.engine.EngineState
import com.deadaccurate.engine.InputPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val rateTickCount: Int = 0,
    val tracePoints: List<TracePoint> = emptyList(),
    val traceHalfRangeMs: Float = DEFAULT_HALF_RANGE_MS,
    val wiredInputName: String? = null,
    val inputLost: Boolean = false,
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

    private val _uiState = MutableStateFlow(
        TimegrapherUiState(unprocessedSupported = unprocessedSourceSupported()),
    )
    val uiState: StateFlow<TimegrapherUiState> = _uiState

    private var eventJob: Job? = null
    private var traceComputer: TraceComputer? = null
    private var traceBph = 0
    private val traceBuffer = ArrayDeque<TracePoint>()

    init {
        viewModelScope.launch {
            inputMonitor.wiredInput.collect { device ->
                _uiState.update { state ->
                    state.copy(wiredInputName = device?.productName?.toString())
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasPermission = granted) }
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
    }

    fun setGateTrimDb(trimDb: Float) {
        engine.setGateTrimDb(trimDb)
        _uiState.update { it.copy(gateTrimDb = trimDb) }
    }

    fun recalibrateGate() {
        engine.recalibrateGate()
    }

    private fun startCapture() {
        val deviceId = inputMonitor.wiredInput.value?.id ?: SYSTEM_DEFAULT_DEVICE
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
        _uiState.update {
            it.copy(capturing = true, inputLost = false, startErrorCode = null)
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
                streamInfo = null,
            )
        }
    }

    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Level -> _uiState.update {
                it.copy(
                    rmsDb = event.rmsDb,
                    peakDb = event.peakDb,
                    gateThresholdDb = event.gateThresholdDb,
                    gateOpen = event.gateOpen,
                    calibrating = event.calibrating,
                )
            }

            is EngineEvent.Tick -> onTick(event)

            is EngineEvent.Rate -> onRate(event)

            is EngineEvent.Status -> onStatus(event)
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
                rateTickCount = event.tickCount,
            )
        }
    }

    private fun onTick(tick: EngineEvent.Tick) {
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
    }
}
