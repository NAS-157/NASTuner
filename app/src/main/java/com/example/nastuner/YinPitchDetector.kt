package com.example.nastuner

import kotlin.math.*

/** YIN (de Cheveigné & Kawahara, 2002). No Android or third-party dependencies. */
class YinPitchDetector(private val sampleRate: Double, private val frameSize: Int) {
    data class Pitch(val frequency: Double, val confidence: Double)
    private val difference = DoubleArray(frameSize / 2)

    fun detect(samples: DoubleArray, minHz: Double, maxHz: Double): Pitch? {
        require(samples.size == frameSize)
        if (!minHz.isFinite() || !maxHz.isFinite() || minHz <= 0 || maxHz <= minHz) return null
        val minLag = floor(sampleRate / maxHz).toInt().coerceAtLeast(2)
        val maxLag = ceil(sampleRate / minHz).toInt().coerceAtMost(difference.size - 2)
        if (minLag >= maxLag) return null
        val window = frameSize / 2
        var cumulative = 0.0
        difference[0] = 1.0
        // Use a constant comparison window at every lag; do not window the input with Hann.
        for (lag in 1..maxLag + 1) {
            var sum = 0.0
            for (i in 0 until window) {
                val delta = samples[i] - samples[i + lag]
                sum += delta * delta
            }
            cumulative += sum
            difference[lag] = if (cumulative > 1e-20) sum * lag / cumulative else 1.0
        }
        // First sufficiently periodic trough avoids choosing an integer multiple of the period.
        var lag = minLag
        while (lag <= maxLag) {
            if (difference[lag] < 0.12) {
                while (lag < maxLag && difference[lag + 1] < difference[lag]) lag++
                val left = difference[lag - 1]
                val center = difference[lag]
                val right = difference[lag + 1]
                val denominator = left - 2 * center + right
                val offset = if (abs(denominator) > 1e-20) {
                    (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
                } else 0.0
                val period = lag + offset
                // Refine over several periods to reduce interpolation bias at high notes.
                // Raw squared difference has a minimum exactly at the waveform's period.
                val multiple = minOf(4, ((window - 4) / period).toInt()).coerceAtLeast(1)
                val centerLag = (period * multiple).roundToInt()
                fun squaredDifference(tau: Int): Double {
                    var sum = 0.0
                    for (i in 0 until window) {
                        val delta = samples[i] - samples[i + tau]
                        sum += delta * delta
                    }
                    return sum
                }
                val search = (centerLag - 2).coerceAtLeast(2)..(centerLag + 2).coerceAtMost(window - 2)
                val refinedLag = search.minByOrNull { squaredDifference(it) } ?: lag
                val a = squaredDifference(refinedLag - 1)
                val b = squaredDifference(refinedLag)
                val c = squaredDifference(refinedLag + 1)
                val curvature = a - 2 * b + c
                val correction = if (curvature > 1e-20) (0.5 * (a - c) / curvature).coerceIn(-0.5, 0.5) else 0.0
                val frequency = sampleRate * multiple / (refinedLag + correction)
                return if (frequency in minHz..maxHz) Pitch(frequency, (1 - center).coerceIn(0.0, 1.0)) else null
            }
            lag++
        }
        return null
    }
}

/** Streaming anti-alias FIR followed by 4:1 decimation (44.1 kHz -> 11.025 kHz). */
class PitchDecimator {
    private val history = DoubleArray(63)
    private var position = 0
    private var phase = 0
    private val coefficients = DoubleArray(63) { i ->
        val x = i - 31
        val cutoff = 1800.0 / 44100.0
        val sinc = if (x == 0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
        sinc * (0.5 - 0.5 * cos(2 * PI * i / 62))
    }.also { values ->
        val sum = values.sum()
        for (i in values.indices) values[i] /= sum
    }

    fun push(sample: Double): Double? {
        history[position] = sample
        position = (position + 1) % history.size
        phase = (phase + 1) % 4
        if (phase != 0) return null
        var result = 0.0
        var index = position
        for (coefficient in coefficients) {
            index = if (index == 0) history.lastIndex else index - 1
            result += coefficient * history[index]
        }
        return result
    }
}
