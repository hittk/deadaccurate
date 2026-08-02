package com.deadaccurate.app

import android.app.Application
import android.media.AudioManager
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deadaccurate.app.audio.AudioInputMonitor
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
    val wiredInputName: String? = null,
    val inputLost: Boolean = false,
    val unprocessedSupported: Boolean = true,
    val streamInfo: StreamInfo? = null,
    val startErrorCode: Int? = null,
) {
    companion object {
        const val SILENCE_DB = -120f
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

    private fun startCapture() {
        val deviceId = inputMonitor.wiredInput.value?.id ?: SYSTEM_DEFAULT_DEVICE
        val preset =
            if (_uiState.value.unprocessedSupported) {
                InputPreset.UNPROCESSED
            } else {
                InputPreset.VOICE_RECOGNITION
            }

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
                streamInfo = null,
            )
        }
    }

    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Level ->
                _uiState.update { it.copy(rmsDb = event.rmsDb, peakDb = event.peakDb) }

            is EngineEvent.Status -> when (event.state) {
                EngineState.RUNNING -> _uiState.update {
                    it.copy(
                        streamInfo = StreamInfo(
                            sampleRate = event.sampleRate,
                            unprocessed = event.unprocessed,
                            exclusiveMode = event.exclusiveMode,
                        ),
                    )
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
