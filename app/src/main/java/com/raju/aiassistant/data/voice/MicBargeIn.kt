package com.raju.aiassistant.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.raju.aiassistant.domain.assistant.BargeIn
import com.raju.aiassistant.domain.voice.BargeInLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Listens to the microphone only while the assistant is speaking in a voice conversation, with the
 * phone's echo canceller on, and reports when the user talks over it. Audio is analysed for
 * loudness on the device and never recorded or sent anywhere.
 *
 * @param playbackLevel 0..1 loudness of the assistant's voice right now (0 = silent).
 */
class MicBargeIn(
    private val context: Context,
    private val scope: CoroutineScope,
    private val playbackLevel: () -> Float,
) : BargeIn {
    private var job: Job? = null

    override fun start(onSpeech: () -> Unit) {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        job = scope.launch {
            val heard = withContext(Dispatchers.IO) { runCatching { watch() }.getOrDefault(false) }
            if (heard) onSpeech()
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission") // checked in start()
    private suspend fun watch(): Boolean {
        val rate = 16_000
        val frameBytes = rate / 1000 * 20 * 2
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        // VOICE_COMMUNICATION turns on the phone's echo cancellation path, so its own voice is quieter.
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frameBytes * 8))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val logic = BargeInLogic()
        val frame = ByteArray(frameBytes)
        try {
            recorder.startRecording()
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = recorder.read(frame, 0, frameBytes)
                if (n <= 0) return false
                if (logic.onFrame(rms(frame, n), playbackLevel() > 0.03f)) return true
            }
            return false
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            aec?.release()
            ns?.release()
        }
    }

    private fun rms(frame: ByteArray, length: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val s = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return sqrt(sum / maxOf(1, length / 2))
    }
}
