package com.example.nastuner

data class TuningData(
    val note: String = "...",
    val deviationInHz: Double = 0.0,
    val frequency: Double = 0.0,
    val confidence: Double = 0.0,
    val message: String? = null,
    val isHeld: Boolean = false
)

