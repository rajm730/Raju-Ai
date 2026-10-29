package com.raju.aiassistant.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.raju.aiassistant.domain.assistant.ConversationMode

/**
 * Call-style audio for hands-free conversations: the phone's acoustic echo canceller only works in
 * communication mode, so while the user and Seema talk (and the user may interrupt her), the audio
 * mode is set to MODE_IN_COMMUNICATION and routed to the loudspeaker (or a connected headset), like
 * a speakerphone call. Normal audio is restored on [exit] — when the user presses Stop or leaves the app.
 */
class ConversationAudio(context: Context) : ConversationMode {
    private val am: AudioManager? = context.getSystemService(AudioManager::class.java)

    @Volatile
    override var active: Boolean = false
        private set

    override fun enter() {
        val am = am ?: return
        if (active) return
        active = runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            routeToSpeakerOrHeadset(am)
            true
        }.getOrDefault(false)
    }

    override fun exit() {
        val am = am ?: return
        if (!active) return
        active = false
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun routeToSpeakerOrHeadset(am: AudioManager) {
        val headsetTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = am.availableCommunicationDevices
            val target = devices.firstOrNull { it.type in headsetTypes } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            target?.let { am.setCommunicationDevice(it) }
        } else {
            val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = !headset
        }
    }
}
