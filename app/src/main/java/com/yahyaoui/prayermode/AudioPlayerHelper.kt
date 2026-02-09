package com.yahyaoui.prayermode

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

class AudioPlayerHelper(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private val tag = "AudioPlayerHelper"
    private val lock = Any()
    private val audioManager: AudioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    suspend fun playAudioFromRaw(resourceId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.DEBUG) Log.d(tag, "Starting audio playback - resourceId: $resourceId")
            releaseMediaPlayer()

            if (!requestAudioFocus()) {
                if (BuildConfig.DEBUG) Log.w(tag, "Failed to gain audio focus, playing anyway")
            }

            return@withContext suspendCancellableCoroutine { continuation ->
                val player = try {
                    MediaPlayer.create(context, resourceId)
                } catch (e: Exception) {
                    Log.e(tag, "Error creating MediaPlayer: ${e.message}", e)
                    abandonAudioFocus()
                    if (continuation.context.isActive) continuation.resume(false)
                    return@suspendCancellableCoroutine
                }

                if (player == null) {
                    Log.e(tag, "Failed to create MediaPlayer (null result)")
                    abandonAudioFocus()
                    if (continuation.context.isActive) continuation.resume(false)
                    return@suspendCancellableCoroutine
                }

                synchronized(lock) {
                    mediaPlayer = player
                }

                player.apply {
                    if (BuildConfig.DEBUG) Log.d(tag, "MediaPlayer created successfully")

                    setOnCompletionListener {
                        if (BuildConfig.DEBUG) Log.d(tag, "Audio playback completed successfully")
                        releaseMediaPlayer()
                        abandonAudioFocus()
                        if (continuation.context.isActive) {
                            continuation.resume(true)
                        }
                    }

                    setOnErrorListener { _, what, extra ->
                        Log.e(tag, "Playback error: what=$what, extra=$extra")
                        releaseMediaPlayer()
                        abandonAudioFocus()
                        if (continuation.context.isActive) {
                            continuation.resume(false)
                        }
                        true
                    }

                    try {
                        start()
                        if (BuildConfig.DEBUG) Log.d(tag, "Playback started")
                    } catch (e: Exception) {
                        Log.e(tag, "Error starting playback: ${e.message}", e)
                        releaseMediaPlayer()
                        abandonAudioFocus()
                        if (continuation.context.isActive) {
                            continuation.resume(false)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    if (BuildConfig.DEBUG) Log.d(tag, "Coroutine cancelled, releasing media player")
                    releaseMediaPlayer()
                    abandonAudioFocus()
                }
            }
        } catch (e: IOException) {
            Log.e(tag, "IOException in playAudioFromRaw: ${e.message}", e)
            abandonAudioFocus()
            false
        } catch (e: IllegalStateException) {
            Log.e(tag, "IllegalStateException in playAudioFromRaw: ${e.message}", e)
            abandonAudioFocus()
            false
        } catch (e: Exception) {
            Log.e(tag, "Unexpected error in playAudioFromRaw: ${e.message}", e)
            abandonAudioFocus()
            false
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (BuildConfig.DEBUG) Log.d(tag, "Audio focus change: $focusChange")
                }
                .build()

            audioFocusRequest = focusRequest
            val result = audioManager.requestAudioFocus(focusRequest)
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

            if (BuildConfig.DEBUG) Log.d(tag, "Audio focus request result: ${if (hasAudioFocus) "GRANTED" else "DENIED"}")

            hasAudioFocus
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { focusChange ->
                    if (BuildConfig.DEBUG) Log.d(tag, "Audio focus change: $focusChange")
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

            if (BuildConfig.DEBUG) Log.d(tag, "Audio focus request result: ${if (hasAudioFocus) "GRANTED" else "DENIED"}")

            hasAudioFocus
        }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }

        hasAudioFocus = false
        if (BuildConfig.DEBUG) Log.d(tag, "Audio focus abandoned")
    }

    fun releaseMediaPlayer() {
        synchronized(lock) {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) {
                        if (BuildConfig.DEBUG) Log.d(tag, "Stopping current audio playback")
                        it.stop()
                    }
                    it.release()
                    if (BuildConfig.DEBUG) Log.d(tag, "MediaPlayer released")
                } catch (e: Exception) {
                    Log.e(tag, "Error releasing MediaPlayer: ${e.message}", e)
                }
            }
            mediaPlayer = null
        }
    }
}