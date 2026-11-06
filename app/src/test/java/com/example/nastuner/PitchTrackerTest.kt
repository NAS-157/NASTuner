package com.example.nastuner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class PitchTrackerTest {
    private fun pitch(hz: Double) = YinPitchDetector.Pitch(hz, 0.99)

    @Test fun briefDropoutsPauseConfirmationInsteadOfRestartingIt() {
        for (frequency in listOf(82.4069, 246.9417)) {
            val tracker = PitchTracker()
            tracker.update(pitch(frequency), 0, 250, null)
            tracker.update(pitch(frequency), 100, 250, null)
            tracker.update(null, 150, 250, null)
            assertEquals(0.0, tracker.update(pitch(frequency), 200, 250, null).frequency, 0.0)
            assertEquals(0.0, tracker.update(pitch(frequency), 300, 250, null).frequency, 0.0)
            assertEquals(frequency, tracker.update(pitch(frequency), 350, 250, null).frequency, 0.01)
            assertTrue(tracker.update(null, 400, 250, null).isHeld)
            assertFalse(tracker.update(pitch(frequency), 450, 250, null).isHeld)
        }
    }

    @Test fun longDropoutRestartsConfirmation() {
        val tracker = PitchTracker()
        tracker.update(pitch(82.4), 0, 250, null)
        tracker.update(pitch(82.4), 100, 250, null)
        tracker.update(null, 200, 250, null)
        assertEquals(0.0, tracker.update(pitch(82.4), 300, 250, null).frequency, 0.0)
        assertEquals(0.0, tracker.update(pitch(82.4), 450, 250, null).frequency, 0.0)
        assertEquals(82.4, tracker.update(pitch(82.4), 550, 250, null).frequency, 0.01)
    }

    @Test fun holdsBriefDropoutsAndClearsAfterSilence() {
        val tracker = PitchTracker()
        assertEquals(0.0, tracker.update(pitch(110.0), 0, 250, null).frequency, 0.0)
        assertEquals(110.0, tracker.update(pitch(110.0), 250, 250, null).frequency, 0.01)
        val held = tracker.update(null, 400, 250, null)
        assertTrue(held.isHeld)
        assertEquals(110.0, held.frequency, 0.01)
        assertEquals(0.0, tracker.update(null, 750, 250, null).frequency, 0.0)
        assertEquals(0.0, tracker.update(pitch(110.0), 800, 250, null).frequency, 0.0)
    }

    @Test fun rejectsIsolatedSpikesAndTracksRealChanges() {
        val tracker = PitchTracker()
        repeat(4) { tracker.update(pitch(110.0), it * 50L, 0, null) }
        val spike = tracker.update(pitch(220.0), 200, 0, null)
        assertEquals(110.0, spike.frequency, 0.01)
        tracker.update(pitch(220.0), 250, 0, null)
        assertEquals(220.0, tracker.update(pitch(220.0), 300, 0, null).frequency, 0.01)
    }

    @Test fun smoothsSmallVariationsAndHoldsWhileNewNoteIsConfirmed() {
        val tracker = PitchTracker()
        repeat(7) { tracker.update(pitch(110.0), it * 50L, 250, null) }
        val shifted = 110.0 * 2.0.pow(10.0 / 1200)
        tracker.update(pitch(shifted), 350, 250, null)
        val smoothed = tracker.update(pitch(shifted), 400, 250, null)
        assertTrue(smoothed.frequency > 110.0 && smoothed.frequency < shifted)
        tracker.update(pitch(146.83), 450, 250, null)
        assertTrue(tracker.update(pitch(146.83), 500, 250, null).isHeld)
        assertEquals("D", tracker.update(pitch(146.83), 750, 250, null).note)
    }
}
