package com.example.nastuner

import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.log2

data class Tuning(val name: String, val notes: List<String>, val octaves: List<Int>) {
    init {
        require(notes.isNotEmpty() && notes.size == octaves.size)
        require(notes.all { it in MusicTheory.NOTES })
    }
    fun frequencies(): List<Double> = notes.mapIndexed { i, note ->
        MusicTheory.getReferenceA4() * 2.0.pow((12 * octaves[i] + MusicTheory.NOTES.indexOf(note) - 57) / 12.0)
    }
    fun closestString(frequency: Double): Int? {
        if (!frequency.isFinite() || frequency <= 0) return null
        val targets = frequencies()
        return targets.indices.minByOrNull { abs(log2(frequency / targets[it])) }
    }
}
