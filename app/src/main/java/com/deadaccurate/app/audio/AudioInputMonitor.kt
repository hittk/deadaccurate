package com.deadaccurate.app.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Watches input devices and exposes the wired-headset input (the piezo path,
 * FR-2) as it comes and goes. The 3.5mm TRRS jack and USB-C analog dongles
 * both surface as TYPE_WIRED_HEADSET.
 */
class AudioInputMonitor(private val audioManager: AudioManager) {

    private val _wiredInput = MutableStateFlow(findWiredInput())
    val wiredInput: StateFlow<AudioDeviceInfo?> = _wiredInput

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            _wiredInput.value = findWiredInput()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            _wiredInput.value = findWiredInput()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun findWiredInput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
}
