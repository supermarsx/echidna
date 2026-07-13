package com.echidna.app.ui.diagnostics

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object NoteUtils {
    private val noteNames = arrayOf(
        "C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"
    )

    fun frequencyToNoteName(frequency: Float): String {
        if (frequency <= 0f) return "—"
        val midi = frequencyToMidi(frequency)
        val index = ((midi.roundToInt() % 12) + 12) % 12
        return noteNames[index]
    }

    fun centsOff(frequency: Float, target: Float): Float {
        if (frequency <= 0f || target <= 0f) return 0f
        return 1200f * (ln(frequency / target) / ln(2.0)).toFloat()
    }

    fun midiTargetFrequency(frequency: Float): Float {
        val midi = frequencyToMidi(frequency).roundToInt()
        return 440f * 2f.pow((midi - 69) / 12f)
    }

    private fun frequencyToMidi(frequency: Float): Float {
        return 69f + 12f * (ln(frequency / 440f) / ln(2.0)).toFloat()
    }
}
