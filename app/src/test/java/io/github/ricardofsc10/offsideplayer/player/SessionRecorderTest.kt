package io.github.ricardofsc10.offsideplayer.player

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecorderTest {
    private var now = 0L
    private fun recorder() = SessionRecorder("abcd1234", "MINIMUM", 0L) { now }

    /** Avança o relógio em amostras de meio segundo, como o monitor do player. */
    private fun SessionRecorder.play(ms: Long, bufferMs: Long, slow: Boolean = false) {
        val end = now + ms
        while (now < end) {
            now += 500
            onSample(bufferMs, playing = true, slow = slow)
        }
    }

    private fun SessionRecorder.wait(ms: Long) {
        val end = now + ms
        while (now < end) {
            now += 500
            onSample(0, playing = false, slow = false)
        }
    }

    @Test
    fun `zapping sem problemas não fica guardado`() {
        val recorder = recorder()
        recorder.onOpen("MPEG_TS")
        now = 1_500
        recorder.onFirstFrame()
        recorder.play(5_000, bufferMs = 4_000)
        assertNull(recorder.finish(PlaybackStats()))
    }

    @Test
    fun `canal deixado antes da imagem e sem erro não fica guardado`() {
        val recorder = recorder()
        recorder.onOpen("MPEG_TS")
        recorder.wait(3_000)
        assertNull(recorder.finish(PlaybackStats()))
    }

    @Test
    fun `paragem nos primeiros segundos fica guardada mesmo com pouco tempo`() {
        val recorder = recorder()
        recorder.onOpen("MPEG_TS")
        now = 2_000
        recorder.onFirstFrame()
        recorder.play(8_000, bufferMs = 1_500)
        recorder.onStallStarted(bufferedMs = 0)
        recorder.wait(3_000)
        recorder.onStallEnded()
        recorder.play(10_000, bufferMs = 2_000)

        val summary = recorder.finish(PlaybackStats(rebuffers = 1))
        assertNotNull(summary)
        summary!!
        assertEquals("problem", summary.outcome)
        assertEquals(23L, summary.openS)
        assertEquals(21L, summary.watchedS)
        assertEquals(3_000L, summary.stalledMs)
        assertEquals(
            listOf("open", "first_frame", "stall", "stall_end", "close"),
            summary.events.map { it.type },
        )
        assertEquals(10_000L, summary.events.first { it.type == "stall" }.t)
        // No primeiro minuto há uma amostra a cada 5 segundos.
        assertEquals(listOf(5, 10, 15, 20), summary.samples.map { it.t })
    }

    @Test
    fun `canal que nunca abre fica guardado com as tentativas e o erro`() {
        val recorder = recorder()
        recorder.onOpen("MPEG_TS")
        recorder.wait(8_000)
        recorder.onRetry("NO_FIRST_FRAME")
        recorder.onOpen("HLS")
        recorder.wait(2_000)
        recorder.onFailed("ERROR_CODE_IO_BAD_HTTP_STATUS http=403")
        recorder.wait(2_000)

        val summary = recorder.finish(PlaybackStats())!!
        assertEquals("problem", summary.outcome)
        assertEquals(0L, summary.watchedS)
        assertEquals(1, summary.retries)
        assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS http=403", summary.failure)
        assertNull(summary.firstFrameMs)
        assertEquals(listOf("open", "retry", "open", "failed", "close"), summary.events.map { it.type })
        assertEquals("HLS", summary.events[2].detail)
    }

    @Test
    fun `reconexão também conta como problema`() {
        val recorder = recorder()
        recorder.onFirstFrame()
        recorder.play(10_000, bufferMs = 3_000)
        recorder.onReconnect()
        assertEquals("problem", recorder.finish(PlaybackStats())?.outcome)
    }

    @Test
    fun `canal visto um minuto sem problemas fica como visto`() {
        val recorder = recorder()
        now = 3_000
        recorder.onFirstFrame()
        recorder.play(30_000, bufferMs = 5_000)
        recorder.play(30_000, bufferMs = 2_000, slow = true)
        recorder.onFlow(FlowReport(bytes = 5_000_000, waitedMs = 9_900, elapsedMs = 10_000, maxReadMs = 300))
        recorder.onFlow(FlowReport(bytes = 5_000_000, waitedMs = 11_000, elapsedMs = 10_000, maxReadMs = 1_800))

        val summary = recorder.finish(PlaybackStats(slowdowns = 1))!!
        assertEquals("watched", summary.outcome)
        assertEquals(60L, summary.watchedS)
        assertEquals(30_000L, summary.slowedMs)
        assertEquals(2_000L, summary.bufferMinMs)
        assertEquals(3_500L, summary.bufferAvgMs)
        assertEquals(4_000, summary.kbpsAvg)
        assertEquals(1, summary.deliveryGaps)
        assertEquals(1_800L, summary.longestReadMs)
        assertTrue(summary.events.any { it.type == "gap" && it.detail == "maxReadMs=1800" })
    }

    @Test
    fun `depois do primeiro minuto as amostras passam a 10 segundos`() {
        val recorder = recorder()
        recorder.onFirstFrame()
        recorder.play(90_000, bufferMs = 4_000)
        val times = recorder.finish(PlaybackStats())!!.samples.map { it.t }
        assertEquals(listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 70, 80, 90), times)
    }

    @Test
    fun `identificador do canal é curto, estável e não é o ID`() {
        val key = SessionPolicy.channelKey(1234)
        assertEquals(8, key.length)
        assertEquals(key, SessionPolicy.channelKey(1234))
        assertNotEquals(key, SessionPolicy.channelKey(1235))
        assertTrue("1234" !in key)
    }

    @Test
    fun `guarda dois canais abertos no mesmo segundo e apaga os mais antigos`() {
        val dir = Files.createTempDirectory("sessions").toFile()
        val store = SessionStore(dir, maxFiles = 2)
        val recorder = recorder()
        recorder.onRetry("NO_FIRST_FRAME")
        val summary = recorder.finish(PlaybackStats())!!
        val first = store.save(summary, 1_000L)
        val sameSecond = store.save(summary, 1_400L)
        assertNotEquals(first.name, sameSecond.name)
        store.save(summary, 3_000L)
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue(!first.exists())
        dir.deleteRecursively()
    }
}
