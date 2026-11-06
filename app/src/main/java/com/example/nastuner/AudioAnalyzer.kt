package com.example.nastuner

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.*

class AudioAnalyzer {
    val tuningState = MutableStateFlow(TuningData())
    private val recordLock = Any()
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var sensitivity = 0.5f
    @Volatile private var expectedFrequency: Double? = null
    @Volatile private var bounds = 25.0 to 1400.0
    @Volatile private var noteLatchMs = 120L
    @Volatile private var ambientRmsBase = 0.001
    private val calibrationLock = Any()
    private var calibrateUntilMs = 0L
    private val rmsSamples = mutableListOf<Double>()

    fun triggerAmbientCalibration(durationMs: Long = 1500L) = synchronized(calibrationLock) {
        rmsSamples.clear()
        calibrateUntilMs = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(100)
    }
    suspend fun calibrateAmbient(durationMs: Long = 1500L): Double {
        triggerAmbientCalibration(durationMs)
        delay(durationMs.coerceAtLeast(100) + 250)
        return ambientRmsBase
    }
    suspend fun autoCalibrateA4(durationMs: Long = 2000L): Double? {
        val end = SystemClock.elapsedRealtime() + durationMs
        val samples = mutableListOf<Double>()
        while (SystemClock.elapsedRealtime() < end) {
            val value = tuningState.value
            if (value.frequency > 0 && value.note == "A" && value.confidence >= 0.9) {
                samples.add(value.frequency * 2.0.pow(4 - MusicTheory.estimateOctave(value.frequency)))
            }
            delay(50)
        }
        return samples.sorted().let { if (it.size >= 5) it[it.size / 2] else null }
    }
    fun setSensitivity(value: Float) { sensitivity = value.coerceIn(0f, 1f) }
    fun setExpectedFrequency(freq: Double?) { expectedFrequency = freq?.takeIf { it.isFinite() && it > 0 } }
    fun setAmbientBase(value: Double) {
        if (value.isFinite()) ambientRmsBase = value.coerceIn(0.0001, 0.05)
    }
    fun getAmbientBase(): Double = ambientRmsBase
    fun setFrequencyBounds(minHz: Double, maxHz: Double) {
        if (minHz.isFinite() && maxHz.isFinite() && minHz >= 20 && maxHz > minHz && minHz < 1400) {
            bounds = minHz to maxHz.coerceAtMost(1400.0)
        }
    }
    fun setNoteLatchMs(ms: Long) { noteLatchMs = ms.coerceIn(0, 5000) }

    suspend fun start(useUnprocessed: Boolean = false) {
        try {
            capture(useUnprocessed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            tuningState.value = TuningData(message = "No se pudo abrir el micrófono. Revisa el permiso y vuelve a abrir la app.")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture(useUnprocessed: Boolean) = withContext(Dispatchers.IO) {
        val recorder = synchronized(recordLock) {
            if (isRecording) return@withContext
            val minimum = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) {
                tuningState.value = TuningData(message = "Este dispositivo no admite la captura de audio necesaria.")
                return@withContext
            }
            val created = AudioRecord(
                if (useUnprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, 8192) // AudioRecord buffer capacity is in bytes.
            )
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                created.release()
                tuningState.value = TuningData(message = "El micrófono no está disponible. Cierra otras apps que lo estén usando.")
                return@withContext
            }
            audioRecord = created
            isRecording = true
            created
        }
        val input = ShortArray(2048)
        val decimator = PitchDecimator()
        val ring = DoubleArray(2048)
        val frame = DoubleArray(2048)
        val detector = YinPitchDetector(11025.0, frame.size)
        var position = 0
        var count = 0
        var hop = 0
        var tracker = PitchTracker()
        try {
            synchronized(recordLock) {
                if (!isRecording || audioRecord !== recorder) return@withContext
                recorder.startRecording()
            }
            while (isRecording && currentCoroutineContext().isActive) {
                val read = recorder.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    if (isRecording && currentCoroutineContext().isActive) error("AudioRecord read error: $read")
                    break
                }
                if (read == 0) continue
                for (i in 0 until read) {
                    val sample = decimator.push(input[i] / 32768.0) ?: continue
                    ring[position] = sample
                    position = (position + 1) % ring.size
                    count = (count + 1).coerceAtMost(ring.size)
                    hop++
                    if (count < ring.size || hop < 512) continue
                    hop = 0
                    for (j in frame.indices) frame[j] = ring[(position + j) % ring.size]
                    val mean = frame.average()
                    var energy = 0.0
                    for (j in frame.indices) {
                        frame[j] -= mean
                        energy += frame[j] * frame[j]
                    }
                    val rms = sqrt(energy / frame.size)
                    val now = SystemClock.elapsedRealtime()
                    val calibrating = synchronized(calibrationLock) {
                        if (calibrateUntilMs > 0) {
                            rmsSamples.add(rms)
                            if (now >= calibrateUntilMs) {
                                val sorted = rmsSamples.sorted()
                                ambientRmsBase = sorted[sorted.size / 2].coerceIn(0.0001, 0.05)
                                calibrateUntilMs = 0
                                rmsSamples.clear()
                            }
                            true
                        } else false
                    }
                    val gate = maxOf(0.0003 + 0.002 * (1 - sensitivity), ambientRmsBase * 1.5)
                    val range = bounds
                    // Never force an observed harmonic into the selected string's octave.
                    val pitch = if (!calibrating && rms >= gate) detector.detect(frame, range.first, range.second) else null
                    if (calibrating) {
                        tracker = PitchTracker()
                        tuningState.value = TuningData()
                    } else {
                        tuningState.value = tracker.update(pitch, now, noteLatchMs, expectedFrequency)
                    }
                }
            }
        } finally {
            synchronized(recordLock) {
                if (audioRecord === recorder) {
                    isRecording = false
                    audioRecord = null
                }
                runCatching { recorder.stop() }
                recorder.release()
            }
            tuningState.value = TuningData()
        }
    }
    fun stop() = synchronized(recordLock) {
        isRecording = false
        // Capture coroutine owns release; stop unblocks its pending read.
        audioRecord?.let { runCatching { it.stop() } }
    }
}
