package io.github.ricardofsc10.offsideplayer.diagnostics

import io.github.ricardofsc10.offsideplayer.player.SessionEvent
import io.github.ricardofsc10.offsideplayer.player.SessionSummary
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    private val context = ReportContext("0.3.0 (3)", "NVIDIA SHIELD Android TV", "Android 11 (API 30)", "lat=MINIMUM")

    private fun session(n: Int, outcome: String = "problem") = SessionSummary(
        startedAt = "2026-11-24T21:%02d:00+0000".format(n % 60),
        channel = "ab12cd%02d".format(n % 100),
        latencyMode = "MINIMUM",
        outcome = outcome,
        openS = 3600, watchedS = 3590, format = "MPEG_TS", width = 1920, height = 1080, fps = 50f, codec = "avc",
        firstFrameMs = 850, rebuffers = 3, stalledMs = 4200, longestStallMs = 2100, reconnects = 1, retries = 0,
        failure = null, droppedFrames = 12, slowdowns = 4, slowedMs = 30_000, bufferMinMs = 800, bufferAvgMs = 2400,
        kbpsAvg = 8200, deliveryGaps = 5, longestReadMs = 3100,
        events = List(200) { SessionEvent(it * 1000L, "stall", "bufferMs=0") },
        samples = emptyList(),
    )

    @Test
    fun `the link always fits in a QR`() {
        val sessions = List(40) { session(it) }
        val log = List(400) { "11-24 21:00:00 retry attempt=$it format=MPEG_TS code=ERROR_CODE_IO_BAD_HTTP_STATUS http=502" }
        val crash = "crash 11-24 21:00:00 thread=main\njava.lang.IllegalStateException: boom\n" + " at a.B.c(B.kt:1)\n".repeat(80)
        val body = DiagnosticReport.body(context, sessions, log, listOf(crash))
        val url = DiagnosticReport.issueUrl(DiagnosticReport.title(sessions, listOf(crash)), body)
        assertTrue(url.length <= DiagnosticReport.MAX_URL)
        // O mais importante entra sempre: o aparelho, o erro e a sessão mais recente.
        assertTrue("dev NVIDIA SHIELD" in body)
        assertTrue("java.lang.IllegalStateException: boom" in body)
        assertTrue("#1 2026-11-24T21:00" in body)
    }

    @Test
    fun `problem sessions come before the good ones`() {
        val body = DiagnosticReport.body(context, listOf(session(1, "watched"), session(2)), emptyList(), emptyList())
        assertTrue(body.indexOf("problem lat=") < body.indexOf("watched lat="))
    }

    @Test
    fun `the newest log lines are kept in order`() {
        val log = List(400) { "line$it" }
        val body = DiagnosticReport.body(context, emptyList(), log, emptyList())
        assertTrue("line399" in body)
        assertFalse("line0\n" in body)
        assertTrue(body.indexOf("line398") < body.indexOf("line399"))
    }

    @Test
    fun `title counts errors and problem sessions`() {
        assertEquals("Diagnóstico: sem problemas registados", DiagnosticReport.title(emptyList(), emptyList()))
        assertEquals(
            "Diagnóstico: 1 erro, 2 sessões com problemas",
            DiagnosticReport.title(listOf(session(1), session(2), session(3, "watched")), listOf("x")),
        )
    }

    @Test
    fun `redact removes addresses, IPs and account values`() {
        val text = "Failed to connect to provider.example:8080 from 192.168.1.20:5555 at http://provider.example/get.php?username=test&password=test user TEST"
        val clean = Redact.text(text, listOf("provider.example", "test"))
        assertFalse("provider.example" in clean)
        assertFalse("192.168" in clean)
        assertFalse("test" in clean.lowercase())
        assertTrue("<url>" in clean && "<ip>" in clean && "<conta>" in clean)
    }

    @Test
    fun `crash format keeps app frames and hides secrets`() {
        val error = IllegalStateException("cannot reach provider.example", RuntimeException("inner"))
        val text = CrashFormat.format(error, 0L, "main", listOf("provider.example"))
        assertTrue(text.startsWith("crash "))
        assertTrue("java.lang.IllegalStateException: cannot reach <conta>" in text)
        assertTrue("caused by java.lang.RuntimeException: inner" in text)
    }

    @Test
    fun `log keeps only the last lines`() {
        val dir = Files.createTempDirectory("diag").toFile()
        val log = DiagnosticLog(File(dir, "log.txt"), maxLines = 8, clock = { 0L })
        repeat(30) { log.append("event $it") }
        val lines = log.lines()
        assertEquals(8, lines.size)
        assertTrue(lines.last().endsWith("event 29"))
        log.clear()
        assertTrue(log.lines().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `crash store keeps the newest ones`() {
        val dir = Files.createTempDirectory("crash").toFile()
        val store = CrashStore(dir, keep = 2)
        store.save("one", 1)
        store.save("two", 2)
        store.save("three", 3)
        assertEquals(listOf("three", "two"), store.all())
        dir.deleteRecursively()
    }
}
