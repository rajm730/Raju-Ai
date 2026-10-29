package com.raju.aiassistant.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.core.content.ContextCompat
import com.raju.aiassistant.domain.assistant.BargeIn
import com.raju.aiassistant.domain.voice.EchoAwareVad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens to the microphone only while the assistant is speaking in a voice conversation and
 * notices the user talking over it (see [EchoAwareVad]). With [capture] it keeps recording until the
 * user pauses and hands over the whole utterance, including about a second from before detection,
 * so no words are lost. Audio stays on the device unless the engine sends that one utterance to the
 * speech-to-text service; nothing is stored.
 *
 * @param playbackLevel 0..1 loudness of the assistant's voice right now (0 = silent).
 */
class MicBargeIn(
    private val context: Context,
    private val scope: CoroutineScope,
    private val playbackLevel: () -> Float,
    /** True while call-style audio is on: then the echo-cancelled microphone path is used. */
    private val callStyle: () -> Boolean = { false },
) : BargeIn {
    private var job: Job? = null

    override fun start(capture: Boolean, onInterrupt: () -> Unit, onUtterance: (ByteArray?) -> Unit) {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        job = scope.launch(Dispatchers.IO) {
            var delivered = false
            try {
                val pcm = runCatching { watch(capture, onInterrupt) }.getOrNull()
                if (capture) {
                    delivered = true
                    onUtterance(pcm)
                }
            } finally {
                if (capture && !delivered) onUtterance(null) // stopped early: never leave the engine waiting
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission") // checked in start()
    private suspend fun watch(capture: Boolean, onInterrupt: () -> Unit): ByteArray? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        // In call-style audio the phone's echo canceller removes most of Seema's own voice from the
        // microphone (VOICE_COMMUNICATION path). Otherwise VOICE_RECOGNITION keeps the echo predictable.
        val source = if (callStyle()) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val recorder = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, FRAME_BYTES * 10))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        val aec = if (callStyle() && AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        val vad = EchoAwareVad()
        val preRoll = ArrayDeque<ByteArray>()
        try {
            recorder.startRecording()
            // 1) Watch for the user talking over the assistant.
            while (true) {
                if (!coroutineContext.isActive) return null
                val frame = readFrame(recorder) ?: return null
                preRoll.addLast(frame)
                if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                if (vad.onFrame(rms(frame), playbackLevel())) break
            }
            onInterrupt()
            if (!capture) return null
            // 2) Keep recording until the user pauses (the assistant is silent now).
            val out = ByteArrayOutputStream()
            preRoll.forEach { out.write(it) }
            val quietLevel = max(vad.noiseFloor * 2.5, 350.0)
            var quietMs = 0
            var totalMs = 0
            while (coroutineContext.isActive && totalMs < MAX_UTTERANCE_MS) {
                val frame = readFrame(recorder) ?: break
                out.write(frame)
                totalMs += FRAME_MS
                quietMs = if (rms(frame) < quietLevel) quietMs + FRAME_MS else 0
                if (quietMs >= END_SILENCE_MS) break
            }
            return out.toByteArray()
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            aec?.release()
        }
    }

    private fun readFrame(recorder: AudioRecord): ByteArray? {
        val frame = ByteArray(FRAME_BYTES)
        var read = 0
        while (read < FRAME_BYTES) {
            val n = recorder.read(frame, read, FRAME_BYTES - read)
            if (n <= 0) return null
            read += n
        }
        return frame
    }

    private fun rms(frame: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < frame.size) {
            val s = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return sqrt(sum / (frame.size / 2))
    }

    private companion object {
        const val RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_BYTES = RATE / 1000 * FRAME_MS * 2
        const val PRE_ROLL_FRAMES = 20 // ~0.4 s before detection: the start of the user's words
        const val END_SILENCE_MS = 1_000
        const val MAX_UTTERANCE_MS = 15_000
    }
}
