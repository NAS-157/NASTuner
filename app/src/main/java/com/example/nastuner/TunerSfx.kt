package com.example.nastuner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

class TunerSfx(private val context: Context) {
    private val sampleRate = 44100
    private val crystalBuf: ShortArray by lazy { generateCrystal(sampleRate) }
    private val beepBuf: ShortArray by lazy { generateBeep(sampleRate) }
    @Volatile private var lastPlayAt = 0L
    private val cooldownMs = 1200L

    fun playInTune(volume: Float, style: SfxStyle) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayAt < cooldownMs) return
        lastPlayAt = now
        val buf = when (style) {
            SfxStyle.CRYSTAL -> crystalBuf
            SfxStyle.BEEP -> beepBuf
        }
        playBuffer(buf, volume.coerceIn(0f, 1f))
    }

    private fun playBuffer(data: ShortArray, volume: Float) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Use MEDIA to ensure playback routes over the media stream even if system effects are silenced
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(sampleRate)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        track.write(data, 0, data.size)
        try {
            // setVolume is API 21+, we're minSdk 35
            track.setVolume(volume)
        } catch (t: Throwable) {
            @Suppress("DEPRECATION")
            track.setStereoVolume(volume, volume)
        }
        track.play()
        // Schedule release after playback
        Thread {
            try {
                // Add a small margin to ensure the buffer fully drains before releasing
                Thread.sleep((1000L * data.size / sampleRate) + 80)
            } catch (_: InterruptedException) {}
            try {
                track.stop()
            } catch (_: Throwable) {}
            try {
                track.release()
            } catch (_: Throwable) {}
        }.start()
    }

    private fun generateBeep(sr: Int): ShortArray {
        val dur = 0.18 // 180 ms
        val n = (dur * sr).toInt()
        val buf = ShortArray(n)
        val f = 1200.0
        val tau = 0.12 // decay seconds
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = exp(-t / tau)
            val s = sin(2 * PI * f * t) * env
            buf[i] = (s * 0.85 * Short.MAX_VALUE).toInt().toShort()
        }
        return buf
    }

    private fun generateCrystal(sr: Int): ShortArray {
        // A short, bright, glassy chime using a few partials and an initial noise tick
        val dur = 0.22
        val n = (dur * sr).toInt()
        val buf = DoubleArray(n)
        val base = 1760.0 // A6 base for brightness
        val partials = arrayOf(1.0, 1.5, 2.0, 2.5, 3.0)
        val decays = arrayOf(0.08, 0.11, 0.14, 0.17, 0.2)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var v = 0.0
            for (p in partials.indices) {
                val f = base * partials[p]
                val env = exp(-t / decays[p])
                v += sin(2 * PI * f * t) * env * (1.0 / (p + 1))
            }
            // Tiny noise burst at start to emulate a brittle hit
            val noise = if (i < (0.01 * sr)) (Math.random() * 2 - 1) * (1.0 - i / (0.01 * sr)) else 0.0
            buf[i] = (v * 0.35 + noise * 0.08)
        }
        // Soft clip
        val out = ShortArray(n)
        for (i in 0 until n) {
            val x = buf[i]
            val y = tanhLike(x)
            out[i] = (y * Short.MAX_VALUE).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
        return out
    }

    private fun tanhLike(x: Double): Double {
        // Fast soft clip approximating tanh without heavy math
        val a = 1.5
        return x * (a + x * x) / (a + 1.0 * x * x)
    }
}
