package com.raju.aiassistant.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.raju.aiassistant.domain.assistant.WakeListener
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
 * Sleep mode: a light, on-device voice detector. It follows the room's noise level, notices when
 * someone starts talking (louder than the noise for ~0.1 s), records until they pause, and hands
 * over that utterance including half a second from before the start. Runs only while the app is on
 * screen in hands-free mode; no audio leaves the phone unless the engine sends that utterance on.
 */
class MicWakeListener(private val context: Context, private val scope: CoroutineScope) : WakeListener {
    private var job: Job? = null

    override fun start(onUtterance: (ByteArray) -> Unit, onError: () -> Unit) {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError()
            return
        }
        job = scope.launch(Dispatchers.IO) {
            val pcm = runCatching { waitForSpeech() }.getOrNull()
            if (!isActive) return@launch
            if (pcm != null) onUtterance(pcm) else onError()
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission") // checked in start()
    private suspend fun waitForSpeech(): ByteArray? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, FRAME_BYTES * 10))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        val preRoll = ArrayDeque<ByteArray>()
        var noise = -1.0
        var loudFrames = 0
        try {
            recorder.startRecording()
            while (true) {
                if (!coroutineContext.isActive) return null
                // 1) Wait for a voice.
                val frame = readFrame(recorder) ?: return null
                preRoll.addLast(frame)
                if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                val level = rms(frame)
                noise = when {
                    noise < 0 -> level
                    level < noise -> noise * 0.9 + level * 0.1
                    else -> noise * 0.998 + level * 0.002
                }
                loudFrames = if (level > max(noise * 3.0, MIN_SPEECH)) loudFrames + 1 else 0
                if (loudFrames < START_FRAMES) continue

                // 2) Record until a pause.
                val out = ByteArrayOutputStream()
                preRoll.forEach { out.write(it) }
                var quietMs = 0
                var speechMs = START_FRAMES * FRAME_MS
                var totalMs = 0
                while (coroutineContext.isActive && totalMs < MAX_UTTERANCE_MS) {
                    val f = readFrame(recorder) ?: break
                    out.write(f)
                    totalMs += FRAME_MS
                    if (rms(f) < max(noise * 2.5, MIN_SPEECH * 0.7)) quietMs += FRAME_MS else { quietMs = 0; speechMs += FRAME_MS }
                    if (quietMs >= END_SILENCE_MS) break
                }
                if (speechMs >= MIN_SPEECH_MS) return out.toByteArray()
                // Too short (a knock, a cough): keep waiting.
                preRoll.clear()
                loudFrames = 0
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
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
        const val PRE_ROLL_FRAMES = 25 // 0.5 s
        const val START_FRAMES = 5 // 0.1 s of voice
        const val MIN_SPEECH = 450.0
        const val MIN_SPEECH_MS = 300
        const val END_SILENCE_MS = 800
        const val MAX_UTTERANCE_MS = 10_000
    }
}
