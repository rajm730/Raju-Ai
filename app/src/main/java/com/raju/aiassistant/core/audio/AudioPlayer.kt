package com.raju.aiassistant.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.raju.aiassistant.domain.voice.PcmSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Plays 16-bit PCM through AudioTrack and reports the loudness of what is playing, so the
 * reactor can pulse with the voice. Cancelling the coroutine stops playback immediately.
 */
class AudioPlayer(context: Context, private val callStyle: () -> Boolean = { false }) : PcmSink {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    /** In a hands-free conversation the voice goes through the call path, so the echo canceller can remove it. */
    private val callAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val mediaFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(mediaAttributes).build()
    private val callFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(callAttributes).build()
    @Volatile private var focus: AudioFocusRequest = mediaFocus

    /** What is playing right now, for [levelNow]. */
    @Volatile private var current: Pair<AudioTrack, PcmAudio>? = null

    override fun requestFocus() {
        focus = if (callStyle()) callFocus else mediaFocus
        audioManager.requestAudioFocus(focus)
    }

    override fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focus)
    }

    /**
     * 0..1 loudness of the samples leaving the speaker at this moment (from the track's playback
     * head, so it is exact to ~20 ms). Used to tell the assistant's own echo from the user's voice.
     */
    fun levelNow(): Float {
        val (track, audio) = current ?: return 0f
        val head = runCatching { track.playbackHeadPosition }.getOrNull() ?: return 0f
        val bytesPerFrame = 2 * audio.channels
        val start = head * bytesPerFrame
        if (start >= audio.data.size) return 0f
        return pcmLevel(audio.data, start, (audio.sampleRate / 50) * bytesPerFrame)
    }

    override suspend fun play(audio: PcmAudio, onLevel: (Float) -> Unit): Unit = withContext(Dispatchers.IO) {
        val channelMask = if (audio.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(audio.sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(audio.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(if (callStyle()) callAttributes else mediaAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val bytesPerFrame = 2 * audio.channels
        val chunk = (audio.sampleRate / 20) * bytesPerFrame // ~50 ms
        try {
            current = track to audio
            track.play()
            var offset = 0
            while (offset < audio.data.size) {
                coroutineContext.ensureActive()
                val len = minOf(chunk, audio.data.size - offset)
                onLevel(pcmLevel(audio.data, offset, len))
                val written = track.write(audio.data, offset, len)
                if (written < 0) break
                offset += written
            }
            // Wait for the buffered tail to finish playing.
            val totalFrames = audio.frameCount
            while (track.playbackHeadPosition < totalFrames && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                coroutineContext.ensureActive()
                delay(20)
            }
        } finally {
            current = null
            onLevel(0f)
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }
}
