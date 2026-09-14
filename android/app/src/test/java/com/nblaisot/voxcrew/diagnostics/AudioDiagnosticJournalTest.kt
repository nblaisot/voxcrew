package com.nblaisot.voxcrew.diagnostics

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticJournalTest {
    @Test
    fun `persists structured events without payload content`() {
        val directory = Files.createTempDirectory("voxcrew-diagnostics").toFile()
        val journal = AudioDiagnosticJournal(
            directory = directory,
            maxFileBytes = 4_096,
            retainedFiles = 2,
            wallClockMs = { 1_700_000_000_000L },
            monotonicMs = { 42L },
        )

        journal.record("capture", "summary", mapOf("maxGapMs" to 81, "route" to "BLUETOOTH"))
        assertTrue(journal.flush())
        journal.close()

        val text = journal.files().single().readText()
        assertTrue(text.contains("\"component\":\"capture\""))
        assertTrue(text.contains("\"maxGapMs\":81"))
        assertTrue(text.contains("\"monotonicMs\":42"))
        assertFalse(text.contains("pcm"))
    }

    @Test
    fun `rotates and bounds retained files`() {
        val directory = Files.createTempDirectory("voxcrew-diagnostics-rotation").toFile()
        val journal = AudioDiagnosticJournal(directory, maxFileBytes = 180, retainedFiles = 3)

        repeat(30) { journal.record("playout", "event", mapOf("index" to it, "message" to "x".repeat(40))) }
        assertTrue(journal.flush())
        journal.close()

        assertEquals(3, journal.files().size)
        assertTrue(journal.files().all { it.length() <= 350L })
    }
}
