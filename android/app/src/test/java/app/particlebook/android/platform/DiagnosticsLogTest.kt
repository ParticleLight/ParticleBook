package app.particlebook.android.platform

import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsLogTest {

    private lateinit var directory: File
    private val fixedNow = Instant.parse("2026-11-05T18:30:00Z").toEpochMilli()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("particlebook-diagnostics-").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun newLog(options: DiagnosticsLog.Options = DiagnosticsLog.Options(maxAgeDays = 0)): DiagnosticsLog =
        DiagnosticsLog(directory, options) { fixedNow }

    @Test
    fun writesRedactedSingleLineWithTagAndThrowable() {
        val log = newLog(DiagnosticsLog.Options(maxAgeDays = 0, maxFileBytes = 4096))
        log.log(
            "Net\nwork",
            "line1\nline2 token=abc user@example.com",
            IllegalStateException("boom\nbad"),
        )

        val text = log.currentFile().readText()
        assertEquals(1, text.count { it == '\n' })
        assertTrue(text.contains("[Net work]"))
        assertTrue(text.contains("line1 | line2"))
        assertTrue(text.contains("token=<redacted>"))
        assertTrue(text.contains("<email>"))
        assertTrue(text.contains("boom | bad"))
        assertNull(log.lastError)
    }

    @Test
    fun rotatesBySizeAndKeepsOnlyConfiguredBackups() {
        val options = DiagnosticsLog.Options(
            maxFileBytes = 120,
            maxBackupFiles = 2,
            maxAgeDays = 0,
            maxLineChars = 4096,
        )
        val log = newLog(options)
        repeat(4) { index ->
            log.log("T", "message-" + index + "-" + "x".repeat(80))
        }

        val current = log.currentFile()
        val backup1 = File(directory, "particlebook.1.log")
        val backup2 = File(directory, "particlebook.2.log")
        val backup3 = File(directory, "particlebook.3.log")
        assertTrue(current.isFile)
        assertTrue(backup1.isFile)
        assertTrue(backup2.isFile)
        assertFalse(backup3.exists())
        assertTrue(current.readText().contains("message-3"))
        assertTrue(backup1.readText().contains("message-2"))
        assertTrue(backup2.readText().contains("message-1"))
        assertFalse(log.exportRedacted().contains("message-0"))
    }

    @Test
    fun zeroBackupsDeletesRotatedCurrent() {
        val options = DiagnosticsLog.Options(
            maxFileBytes = 64,
            maxBackupFiles = 0,
            maxAgeDays = 0,
            maxLineChars = 4096,
        )
        val log = newLog(options)
        log.log("T", "a".repeat(80))
        log.log("T", "b".repeat(80))

        assertTrue(log.currentFile().isFile)
        assertFalse(File(directory, "particlebook.1.log").exists())
        assertTrue(log.currentFile().readText().contains("b".repeat(80)))
    }

    @Test
    fun purgesExpiredBackupsBeforeWriting() {
        val expired = File(directory, "particlebook.1.log")
        expired.writeText("old-token=legacy\n")
        expired.setLastModified(fixedNow - 2L * 86_400_000L)

        val log = newLog(
            DiagnosticsLog.Options(
                maxFileBytes = 4096,
                maxBackupFiles = 3,
                maxAgeDays = 1,
            ),
        )
        log.log("T", "fresh")

        assertFalse(expired.exists())
        assertTrue(log.currentFile().isFile)
    }

    @Test
    fun exportRedactsCurrentAndBackupsAndUsesBasenames() {
        val legacy = File(directory, "particlebook.1.log")
        legacy.writeText("token=legacy-secret\n")
        val log = newLog(DiagnosticsLog.Options(maxAgeDays = 0, maxFileBytes = 4096))
        log.log("T", "email=user@example.com")

        val exported = log.exportRedacted()
        assertTrue(exported.contains("# particlebook.log"))
        assertTrue(exported.contains("# particlebook.1.log"))
        assertTrue(exported.contains("token=<redacted>"))
        assertTrue(exported.contains("<email>"))
        assertFalse(exported.contains("legacy-secret"))
        assertFalse(exported.contains("user@example.com"))
        assertFalse(exported.contains(directory.absolutePath))
    }

    @Test
    fun clearRemovesCurrentAndBackups() {
        val log = newLog(DiagnosticsLog.Options(maxAgeDays = 0, maxFileBytes = 4096))
        log.log("T", "one")
        File(directory, "particlebook.1.log").writeText("old\n")
        File(directory, "particlebook.7.log").writeText("older\n")

        log.clear()

        assertTrue(log.files().isEmpty())
        assertFalse(File(directory, "particlebook.7.log").exists())
        assertNull(log.lastError)
    }

    @Test
    fun ioFailureIsCapturedInsteadOfThrown() {
        val notDirectory = File(directory, "not-a-directory")
        notDirectory.writeText("x")
        val log = DiagnosticsLog(
            notDirectory,
            DiagnosticsLog.Options(maxAgeDays = 0),
        ) { fixedNow }

        log.log("T", "hello")

        assertNotNull(log.lastError)
    }

    @Test
    fun rejectsUnsafeStemAndInvalidOptions() {
        val stemError = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsLog.Options(fileStem = "../escape")
        }
        assertTrue(stemError.message!!.contains("fileStem"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsLog.Options(maxBackupFiles = -1)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsLog.Options(maxAgeDays = -1)
        }
    }
}
