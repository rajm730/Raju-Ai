package com.raju.aiassistant.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Plays 16-bit PCM through AudioTrack and reports the loudness of what is playing, so the
 * reactor can pulse with the voice. Cancelling the coroutine stops playback immediately.
 */
class AudioPlayer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    fun requestFocus(): Boolean = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    suspend fun play(audio: PcmAudio, onLevel: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val channelMask = if (audio.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(audio.sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(audio.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val bytesPerFrame = 2 * audio.channels
        val chunk = (audio.sampleRate / 20) * bytesPerFrame // ~50 ms
        try {
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
            onLevel(0f)
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }
}
