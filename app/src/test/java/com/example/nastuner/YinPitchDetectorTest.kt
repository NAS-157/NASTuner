package com.example.nastuner

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class YinPitchDetectorTest {
    private val detector = YinPitchDetector(11025.0, 2048)
    private fun signal(frequency: Double, harmonics: DoubleArray = doubleArrayOf(1.0), noise: Double = 0.0, decay: Double = 0.0, dc: Double = 0.0): DoubleArray {
        val random = Random(42)
        return DoubleArray(2048) { i ->
            val t = i / 11025.0
            dc + exp(-decay * t) * harmonics.mapIndexed { h, amplitude ->
                amplitude * sin(2 * PI * frequency * (h + 1) * t + h * 0.31)
            }.sum() + noise * random.nextGaussian()
        }
    }
    private fun assertPitch(expected: Double, samples: DoubleArray, toleranceCents: Double = 2.0) {
        val pitch = detector.detect(samples, 25.0, 1400.0)
        assertNotNull("No pitch for $expected Hz", pitch)
        val error = abs(1200 * log2(pitch!!.frequency / expected))
        assertTrue("$expected Hz -> ${pitch.frequency} Hz ($error cents)", error < toleranceCents)
        assertTrue(pitch.confidence >= 0.88)
    }
    @Test fun openStringsAndDropTunings() {
        for (frequency in listOf(30.8677, 32.7032, 36.7081, 41.2034, 55.0, 65.4064, 69.2957, 73.4162, 82.4069, 110.0, 146.8324, 195.9977, 246.9417, 261.6256, 329.6276, 391.9954, 440.0, 493.8833)) {
            assertPitch(frequency, signal(frequency))
        }
    }
    @Test fun detectsSmallDetuningOnLowE() {
        for (cents in listOf(-25.0, -5.0, -1.0, 0.0, 1.0, 5.0, 25.0)) {
            val frequency = 82.406889 * 2.0.pow(cents / 1200)
            assertPitch(frequency, signal(frequency), 0.5)
        }
    }
    @Test fun strongHarmonicsDoNotBecomeAnOctave() {
        for (frequency in listOf(41.2034, 65.4064, 82.4069, 110.0, 146.8324, 329.6276)) {
            assertPitch(frequency, signal(frequency, doubleArrayOf(0.15, 1.0, 0.65, 0.3), noise = 0.01))
        }
    }
    @Test fun missingFundamentalWithSecondAndThirdHarmonics() {
        for (frequency in listOf(41.2034, 82.4069, 110.0, 146.8324)) {
            assertPitch(frequency, signal(frequency, doubleArrayOf(0.0, 1.0, 0.7, 0.2)))
        }
    }
    @Test fun decayNoiseAndDcOffset() {
        for (frequency in listOf(41.2034, 82.4069, 146.8324, 329.6276)) {
            assertPitch(frequency, signal(frequency, doubleArrayOf(1.0, 0.5, 0.25), noise = 0.015, decay = 4.0, dc = 0.25))
        }
    }
    @Test fun silenceAndBroadbandNoiseAreRejected() {
        assertNull(detector.detect(DoubleArray(2048), 25.0, 1400.0))
        assertNull(detector.detect(DoubleArray(2048) { 0.3 }, 25.0, 1400.0))
        val random = Random(123)
        repeat(20) { assertNull(detector.detect(DoubleArray(2048) { random.nextGaussian() }, 25.0, 1400.0)) }
    }
    @Test fun microphoneRatePipelineIncludesAntiAliasFiltering() {
        for (frequency in listOf(30.8677, 65.4064, 82.4069, 110.0, 329.6276, 493.8833)) {
            val decimator = PitchDecimator()
            val samples = mutableListOf<Double>()
            repeat(10000) { i ->
                val t = i / 44100.0
                // High-frequency interference must be removed before reducing the sample rate.
                val sample = 0.15 * sin(2 * PI * frequency * t) + 0.3 * sin(2 * PI * 9000 * t)
                decimator.push(sample)?.let { samples.add(it) }
            }
            assertPitch(frequency, samples.takeLast(2048).toDoubleArray())
        }
    }
    @Test fun chromaticSweep() {
        for (midi in 23..88) {
            val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
            assertPitch(frequency, signal(frequency), 3.0)
        }
    }
    @Test fun noteMathAndReferencePitch() {
        MusicTheory.setReferenceA4(440.0)
        assertEquals("E", MusicTheory.frequencyToNote(82.406889).note)
        assertEquals(2, MusicTheory.estimateOctave(82.406889))
        assertEquals(4, MusicTheory.estimateOctave(329.627557))
        try {
            MusicTheory.setReferenceA4(442.0)
            assertEquals(0.0, MusicTheory.frequencyToNote(442.0).deviationInHz, 1e-9)
        } finally { MusicTheory.setReferenceA4(440.0) }
    }
    @Test fun selectedStringsKeepTheirActualOctaves() {
        MusicTheory.setReferenceA4(440.0)
        val guitar = Tuning("Standard", listOf("E", "A", "D", "G", "B", "E"), listOf(2, 2, 3, 3, 3, 4))
        val expected = listOf(82.406889, 110.0, 146.832384, 195.997718, 246.941651, 329.627557)
        guitar.frequencies().zip(expected).forEach { (actual, target) -> assertEquals(target, actual, 0.00001) }
        val lowG = Tuning("Low G", listOf("G", "C", "E", "A"), listOf(3, 4, 4, 4))
        assertEquals(195.997718, lowG.frequencies().first(), 0.00001)
        try {
            MusicTheory.setReferenceA4(442.0)
            assertEquals(110.5, guitar.frequencies()[1], 1e-9)
        } finally { MusicTheory.setReferenceA4(440.0) }
    }
    @Test fun autoModeTargetsTheTuningInsteadOfCongratulatingAnyChromaticNote() {
        val guitar = Tuning("Standard", listOf("E", "A", "D", "G", "B", "E"), listOf(2, 2, 3, 3, 3, 4))
        assertEquals(0, guitar.closestString(82.406889))
        assertEquals(5, guitar.closestString(329.627557))
        // A perfectly chromatic A# is still 100 cents sharp of this tuning's A string.
        val frequency = 110.0 * 2.0.pow(100.0 / 1200)
        val index = guitar.closestString(frequency)!!
        assertEquals(1, index)
        assertEquals(100.0, 1200 * log2(frequency / guitar.frequencies()[index]), 1e-9)
        assertNull(guitar.closestString(0.0))
    }
}
