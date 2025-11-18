package com.example.nastuner

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jtransforms.fft.DoubleFFT_1D
import com.example.nastuner.Instrument

private const val NOTE_LATCH_THRESHOLD = 3 // 3 frames seguidos para cambiar nota (evita parpadeos)
private const val MIN_FREQUENCY_HZ = 40
@Volatile private var NOTE_LATCH_MS = 2000L // ≈2s estable antes de cambiar etiqueta (se siente sólido)
private const val IN_TUNE_HOLD_MS = 1500L // Mantener cerca de 0 si te centraste y luego la señal se apaga

class AudioAnalyzer {
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(8192)
    val tuningState = MutableStateFlow(TuningData())
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val fft = DoubleFFT_1D(bufferSize.toLong())
    @Volatile private var sensitivity = 0.5f
    @Volatile private var expectedFrequency: Double? = null
    @Volatile private var ambientProvided: Boolean = false
    @Volatile private var minSearchHz: Double = MIN_FREQUENCY_HZ.toDouble()
    @Volatile private var maxSearchHz: Double = (sampleRate / 2).toDouble()
    private val hannWindow: DoubleArray by lazy {
        DoubleArray(bufferSize) { n -> 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * n / (bufferSize - 1)) }
    }

    // Estado para estabilidad (evita saltos y parpadeos)
    private var lastNote: String? = null
    private var noteLatchCounter = 0
    private var smoothedDeviation: Double = 0.0
    private val smoothingFactor = 0.2 // EMA alpha (slightly more smoothing)
    private var committedNote: String? = null
    private var candidateNote: String? = null
    private var candidateSinceMs: Long = 0L
    // Puerta de ruido: si hay poca energía, lo tratamos como silencio
    @Volatile private var lastRms: Double = 0.0
    private var lastStableAtMs: Long = 0L
    // Calibración de ambiente: aprende el ruido base sin tocar
    @Volatile private var ambientRmsBase: Double = 0.008
    @Volatile private var calibrateUntilMs: Long = 0L
    private val rmsSamples: MutableList<Double> = mutableListOf()

    fun triggerAmbientCalibration(durationMs: Long = 1500L) {
        calibrateUntilMs = SystemClock.elapsedRealtime() + durationMs
        rmsSamples.clear()
    }
    suspend fun calibrateAmbient(durationMs: Long = 1500L): Double {
        triggerAmbientCalibration(durationMs)
        val waitUntil = SystemClock.elapsedRealtime() + durationMs + 20
        while (SystemClock.elapsedRealtime() < waitUntil) { delay(50) }
        return ambientRmsBase
    }

    // Auto-calibrate A4 by asking user to play an "A" (La) for a short duration
    suspend fun autoCalibrateA4(durationMs: Long = 2000L): Double? {
        val end = SystemClock.elapsedRealtime() + durationMs
        val samples = mutableListOf<Double>()
        while (SystemClock.elapsedRealtime() < end) {
            val v = tuningState.value
            if (v.frequency > 0 && v.note == "A") {
                samples.add(v.frequency)
            }
            delay(50)
        }
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val f = sorted[sorted.size / 2]
        val octave = MusicTheory.estimateOctave(f)
        val newA4 = f * 2.0.pow(4 - octave)
        return newA4
    }

    fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity.coerceIn(0f, 1f)
    }

    fun setExpectedFrequency(freq: Double?) {
        expectedFrequency = freq?.takeIf { it.isFinite() && it > 0 }
    }
    fun setAmbientBase(value: Double) {
        if (value.isFinite() && value in 0.001..0.05) {
            ambientRmsBase = value
            ambientProvided = true
        }
    }
    fun getAmbientBase(): Double = ambientRmsBase

    fun setFrequencyBounds(minHz: Double, maxHz: Double) {
        minSearchHz = minHz.coerceAtLeast(20.0)
        maxSearchHz = maxHz.coerceAtMost((sampleRate / 2).toDouble())
        if (minSearchHz >= maxSearchHz) {
            minSearchHz = MIN_FREQUENCY_HZ.toDouble()
            maxSearchHz = (sampleRate / 2).toDouble()
        }
    }

    fun setNoteLatchMs(ms: Long) {
        NOTE_LATCH_MS = ms.coerceIn(0L, 5000L)
    }

    @SuppressLint("MissingPermission")
    suspend fun start() = withContext(Dispatchers.IO) {
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        val shortBuffer = ShortArray(bufferSize)
    val fftBuffer = DoubleArray(bufferSize)
        isRecording = true
        audioRecord?.startRecording()

        while (isRecording) {
            val readSize = audioRecord?.read(shortBuffer, 0, bufferSize) ?: 0
            if (readSize > 0) {
                // Copiar señal, quitar DC y aplicar ventana Hann (suaviza bordes)
                var mean = 0.0
                for (i in 0 until readSize) { mean += shortBuffer[i] }
                mean /= readSize.coerceAtLeast(1)
                var sumSquares = 0.0
                for (i in 0 until readSize) {
                    val v = (shortBuffer[i] - mean)
                    sumSquares += (v * v)
                    fftBuffer[i] = v * hannWindow[i]
                }
                // Silencio por RMS (normalizado a 16‑bit)
                val rms = kotlin.math.sqrt(sumSquares / readSize.coerceAtLeast(1)) / 32768.0
                lastRms = rms
                val nowTs = SystemClock.elapsedRealtime()
                // Recolectar RMS durante la ventana de calibración
                if (nowTs <= calibrateUntilMs) {
                    if (rms.isFinite()) rmsSamples.add(rms)
                    if (nowTs + 5 >= calibrateUntilMs) {
                        // Finalizar calibración (mediana robusta)
                        if (rmsSamples.isNotEmpty()) {
                            val sorted = rmsSamples.sorted()
                            val median = sorted[sorted.size / 2]
                            ambientRmsBase = median.coerceIn(0.001, 0.05)
                        }
                        calibrateUntilMs = 0L
                        rmsSamples.clear()
                    }
                }
                // Puerta de ruido = max(ambiente*1.6, base según sensibilidad)
                val baseGate = 0.008 + (0.012 * (1.0 - sensitivity.coerceIn(0f, 1f)))
                val silenceThreshold = max(ambientRmsBase * 1.6, baseGate) // ambient-calibrated gate
                if (rms < silenceThreshold) {
                    // Silencio: conservar última nota mostrada; freq/desviación en 0
                    val frozen = committedNote ?: "..."
                    tuningState.value = TuningData(note = frozen, deviationInHz = 0.0, frequency = 0.0)
                    continue
                }
                // Rellenar con ceros si readSize < bufferSize
                for (i in readSize until bufferSize) { fftBuffer[i] = 0.0 }

                fft.realForward(fftBuffer)
                val spectrum = DoubleArray(bufferSize / 2)
                for (i in spectrum.indices) {
                    val real = fftBuffer[2 * i]
                    val imag = fftBuffer[2 * i + 1]
                    spectrum[i] = sqrt(real * real + imag * imag)
                }

                val ffResult = findFundamentalFrequencyWithStats(spectrum)
                val fundamentalFrequency = ffResult.frequency

                if (fundamentalFrequency > 0) {
                    val now = SystemClock.elapsedRealtime()
                    val detected = MusicTheory.frequencyToNote(fundamentalFrequency)
                    val newNote = detected.note

                    if (newNote != candidateNote) {
                        candidateNote = newNote
                        candidateSinceMs = now
                        // No resetear duro el suavizado; que converja piola
                    }

                    val stable = (now - candidateSinceMs) >= NOTE_LATCH_MS
                    val labelNote = if (stable) newNote else (committedNote ?: "...")
                    if (stable && labelNote != committedNote) {
                        committedNote = newNote
                    }

                    // Tip clave: calcular desviación según la nota detectada actual (no la etiqueta latcheada)
                    // para que la UI reaccione al tiro al tono correcto.
                    val deviationToDetected = detected.deviationInHz

                    // Si te centraste y el RMS cae, mantener cerca de 0 un rato para que no se desvíe al apagarse la cuerda.
                    // Umbral práctico aquí es más estricto que el de la UI (que es más amable).
                    val inTuneThreshold = when {
                        fundamentalFrequency < 80 -> 1.8
                        fundamentalFrequency <= 500 -> 1.0
                        else -> 0.6
                    }
                    val wasInTuneNow = kotlin.math.abs(deviationToDetected) <= inTuneThreshold
                    if (wasInTuneNow) {
                        lastStableAtMs = now
                    }
                    val baseGate2 = 0.008 + (0.012 * (1.0 - sensitivity.coerceIn(0f, 1f)))
                    val silenceThreshold2 = max(ambientRmsBase * 1.6, baseGate2)
                    val decaying = lastRms < (silenceThreshold2 * 1.8)
                    val holdActive = decaying && (now - lastStableAtMs) <= IN_TUNE_HOLD_MS
                    val targetDeviation = if (holdActive) 0.0 else deviationToDetected

                    // EMA: suaviza la desviación para que no "titee"
                    val alpha = if (holdActive) 0.12 else smoothingFactor
                    smoothedDeviation = (targetDeviation * alpha) + (smoothedDeviation * (1 - alpha))

                    if (abs(smoothedDeviation) < 50) { // Update only if deviation is reasonable (in Hz)
                        // Confianza = SNR + cercanía + estabilidad (escala 0..1)
                        val snr = ffResult.snr
                        val snrScore = ((snr - 1.0) / 6.0).coerceIn(0.0, 1.0) // ~1..7 mapped to 0..1
                        val proximityScore = kotlin.math.exp(-kotlin.math.abs(deviationToDetected) / 6.0).coerceIn(0.0, 1.0)
                        val stabilityScore = ((now - candidateSinceMs).toDouble() / NOTE_LATCH_MS).coerceIn(0.0, 1.0)
                        val confidence = (0.55 * snrScore) + (0.3 * proximityScore) + (0.15 * stabilityScore)
                        tuningState.value = TuningData(
                            note = labelNote ?: newNote,
                            deviationInHz = smoothedDeviation,
                            frequency = fundamentalFrequency,
                            confidence = confidence
                        )
                    }
                } else {
                    // Sin fundamental: congelar en la nota fijada si existe
                    val frozen = committedNote ?: "..."
                    tuningState.value = TuningData(note = frozen, deviationInHz = 0.0, frequency = 0.0, confidence = 0.0)
                }
            }
        }
    }

    private data class FreqStats(val frequency: Double, val snr: Double)
    private fun findFundamentalFrequencyWithStats(spectrum: DoubleArray): FreqStats {
        val hps = spectrum.clone()
        // HPS (Espectro de Producto Armónico): resalta la fundamental multiplicando armónicos
        for (h in 2..6) {
            val limit = spectrum.size / h
            for (i in 0 until limit) {
                hps[i] *= spectrum[i * h]
            }
        }

        var minIndex = (minSearchHz * bufferSize / sampleRate).toInt().coerceAtLeast(1)
        var maxIndex = min(hps.size / 2, (maxSearchHz * bufferSize / sampleRate).toInt().coerceAtLeast(minIndex + 10))

        expectedFrequency?.let { exp ->
            // Narrow search around expected frequency to avoid octave/harmonic confusion
            val low = (exp * 0.55).coerceAtLeast(minSearchHz)
            val high = (exp * 1.8).coerceAtMost(maxSearchHz)
            minIndex = max(minIndex, (low * bufferSize / sampleRate).toInt())
            maxIndex = min(maxIndex, (high * bufferSize / sampleRate).toInt())
        }

        // Suavizar HPS: promedio corto para bajar picos falsos sin matar el peak
        val smoothed = DoubleArray(hps.size)
        for (i in 0 until hps.size) {
            var sum = 0.0
            var cnt = 0
            for (k in -1..1) {
                val idx = i + k
                if (idx in 0 until hps.size) { sum += hps[idx]; cnt++ }
            }
            smoothed[i] = if (cnt > 0) sum / cnt else hps[i]
        }

        // Umbral adaptativo según ruido local + sensibilidad; piso de ruido por mediana (robusto)
        val bandEnd = minIndex + 200.coerceAtMost(maxIndex - minIndex)
        val band = if (bandEnd > minIndex) smoothed.copyOfRange(minIndex, bandEnd).sorted() else emptyList<Double>()
    val noiseFloor = if (band.isNotEmpty()) band[band.size / 2] else 0.0
    var multiplier = 2.2 - 1.2 * sensitivity // sens=0 -> x2.2, sens=1 -> x1.0
    if (expectedFrequency != null) multiplier *= 0.8 // more permissive when target is known
    val adaptiveThreshold = noiseFloor * multiplier

        var maxAmplitude = -1.0
        var fundamentalIndex = -1
        for (i in minIndex until maxIndex - 1) { 
            if (smoothed[i] > maxAmplitude) {
                maxAmplitude = smoothed[i]
                fundamentalIndex = i
            }
        }

    if (fundamentalIndex <= minIndex) return FreqStats(0.0, 0.0)
    // SNR extra: si el pico no sobresale, descartamos
    val snrThreshold = if (expectedFrequency != null) 1.1 else 1.3
    val snrVal = if (noiseFloor > 0) (maxAmplitude / noiseFloor) else 0.0
    val snrOk = if (noiseFloor > 0) snrVal >= snrThreshold else maxAmplitude > 0
    if (maxAmplitude <= adaptiveThreshold && !snrOk) return FreqStats(0.0, snrVal)

        // Interpolación parabólica alrededor del pico (mejor precisión sub-bin)
        val i = fundamentalIndex
    val a = kotlin.math.ln(smoothed[i - 1].coerceAtLeast(1e-12))
    val b = kotlin.math.ln(smoothed[i].coerceAtLeast(1e-12))
    val c = kotlin.math.ln(smoothed[i + 1].coerceAtLeast(1e-12))
        val denom = (a - 2 * b + c)
        val delta = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
        val peakIndex = i + delta.coerceIn(-1.0, 1.0)

        var freq = peakIndex * sampleRate / bufferSize
        expectedFrequency?.let { exp ->
            // Ajustar por octavas a la opción más plausible según esperado
            while (freq > 0 && freq > exp * 1.6) freq /= 2.0
            while (freq > 0 && freq < exp * 0.62) freq *= 2.0
        }
        return FreqStats(freq, snrVal)
    }

    fun stop() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}