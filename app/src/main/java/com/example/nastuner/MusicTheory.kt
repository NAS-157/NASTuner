package com.example.nastuner

import kotlin.math.*

private const val A4_INDEX = 57

object MusicTheory {
    @Volatile private var REF_A4 = 440.0
    val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun setReferenceA4(hz: Double) { if (hz.isFinite() && hz in 350.0..500.0) REF_A4 = hz }
    fun getReferenceA4(): Double = REF_A4
    fun estimateOctave(frequency: Double): Int {
        if (frequency <= 0.0) return 4
        val nEst = (A4_INDEX + 12 * log2(frequency / REF_A4)).roundToInt()
        return floor(nEst / 12.0).toInt()
    }
    
    fun frequencyToNote(frequency: Double): TuningData {
        if (frequency <= 0.0) return TuningData(frequency = frequency)
        val noteNumber = 12 * log2(frequency / REF_A4)
        val roundedNoteIndex = (A4_INDEX + noteNumber).roundToInt()
        val noteIndex = (roundedNoteIndex % 12).let { if (it < 0) it + 12 else it }
        val note = NOTES[noteIndex]
        val idealFrequency = REF_A4 * 2.0.pow((roundedNoteIndex - A4_INDEX) / 12.0)
        val deviationInHz = frequency - idealFrequency
        
        return TuningData(note, deviationInHz, frequency)
    }

    fun calculateDeviationInHz(frequency: Double, targetNoteName: String): Double {
        val targetNoteIndex = NOTES.indexOf(targetNoteName)
        if(targetNoteIndex == -1) return 0.0

        // Find nearest octave of the target note around the detected frequency
        val nEst = (A4_INDEX + 12 * log2(frequency / REF_A4)).roundToInt()
        val kBase = floor((nEst - targetNoteIndex) / 12.0).toInt()
        val candidates = listOf(
            targetNoteIndex + 12 * kBase,
            targetNoteIndex + 12 * (kBase + 1)
        )
        val targetIdealIndex = candidates.minByOrNull { idx ->
            val f = REF_A4 * 2.0.pow((idx - A4_INDEX) / 12.0)
            kotlin.math.abs(f - frequency)
        } ?: (targetNoteIndex + 12 * kBase)
        val targetIdealFrequency = REF_A4 * 2.0.pow((targetIdealIndex - A4_INDEX) / 12.0)

        return frequency - targetIdealFrequency
    }

}
