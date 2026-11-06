package com.example.nastuner

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/** Stabilizes successive pitch observations and tolerates brief dropouts. */
class PitchTracker {
    private val recent = ArrayDeque<Double>()
    private var smoothed: Double? = null
    private var candidate: String? = null
    private var candidateSince = 0L
    private var lastValidAt: Long? = null
    private var interrupted = false
    private var lastConfirmedAt = 0L
    private var lastReading = TuningData()
    private var previousTarget: Double? = null

    fun update(pitch: YinPitchDetector.Pitch?, now: Long, latchMs: Long, target: Double?): TuningData {
        if (target != previousTarget) {
            recent.clear()
            smoothed = null
            candidate = null
            previousTarget = target
        }
        if (pitch == null || pitch.confidence < 0.88) {
            interrupted = true
            if (lastValidAt == null || now - lastValidAt!! > 150) {
                candidate = null
                recent.clear()
                smoothed = null
            }
            if (lastValidAt != null && now - lastValidAt!! < 500 && lastReading.frequency > 0) {
                return lastReading.copy(isHeld = true)
            }
            lastReading = TuningData()
            return lastReading
        }

        if (interrupted) {
            val gap = lastValidAt?.let { now - it }
            if (gap == null || gap > 150) {
                candidate = null
                recent.clear()
                smoothed = null
            } else if (candidate != null) {
                candidateSince += gap
            }
            interrupted = false
        }
        lastValidAt = now
        recent.addLast(log2(pitch.frequency))
        if (recent.size > 3) recent.removeFirst()
        val median = recent.sorted()[recent.size / 2]
        val previous = smoothed
        val smooth = if (previous == null || abs(median - previous) * 1200 > 60) median
        else previous + 0.2 * (median - previous)
        smoothed = smooth
        val frequency = 2.0.pow(smooth)
        val detected = MusicTheory.frequencyToNote(frequency)
        val identity = "${detected.note}${MusicTheory.estimateOctave(frequency)}"
        if (candidate != identity) {
            candidate = identity
            candidateSince = now
        }
        if (now - candidateSince >= latchMs.coerceIn(0, 250)) {
            lastReading = detected.copy(confidence = pitch.confidence)
            lastConfirmedAt = now
            return lastReading
        }
        return if (lastReading.frequency > 0 && now - lastConfirmedAt < 500) lastReading.copy(isHeld = true) else TuningData()
    }
}
