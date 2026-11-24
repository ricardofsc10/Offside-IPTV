package io.github.ricardofsc10.offsideplayer.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodRulesTest {

    @Test
    fun `conta como visto a partir de 95 por cento`() {
        assertFalse(ProgressPolicy.isFinished(positionMs = 94_000, durationMs = 100_000))
        assertTrue(ProgressPolicy.isFinished(positionMs = 95_000, durationMs = 100_000))
        assertFalse(ProgressPolicy.isFinished(positionMs = 95_000, durationMs = 0))
    }

    @Test
    fun `retoma só quando vale a pena`() {
        assertEquals(600_000L, ProgressPolicy.resumePositionMs(600_000, 6_000_000, finished = false))
        assertNull(ProgressPolicy.resumePositionMs(null, null, null))
        assertNull(ProgressPolicy.resumePositionMs(5_000, 6_000_000, finished = false))
        assertNull(ProgressPolicy.resumePositionMs(600_000, 6_000_000, finished = true))
        assertNull(ProgressPolicy.resumePositionMs(5_800_000, 6_000_000, finished = false))
        // Sem duração conhecida, retoma na posição guardada.
        assertEquals(600_000L, ProgressPolicy.resumePositionMs(600_000, null, finished = null))
    }

    @Test
    fun `episódios guardados ficam antigos ao fim de 8 horas`() {
        val hour = 60 * 60 * 1000L
        assertTrue(EpisodesPolicy.isStale(null, 10 * hour))
        assertFalse(EpisodesPolicy.isStale(10 * hour, 17 * hour))
        assertTrue(EpisodesPolicy.isStale(10 * hour, 18 * hour))
        assertTrue(EpisodesPolicy.isStale(10 * hour, 9 * hour))
    }

    @Test
    fun `conta extensões por ordem alfabética`() {
        val counter = ExtensionCounter()
        listOf("mp4", "mkv", "mp4").forEach(counter::add)
        assertEquals("mkv:1,mp4:2", counter.toString())
    }

    @Test
    fun `ids que desapareceram do fornecedor`() {
        val seen = IdCollector()
        (1L..3000L).forEach { if (it % 3L != 0L) seen.add(it) }
        val missing = seen.missingFrom(longArrayOf(1, 3, 6, 2999, 3000, 5000))
        assertEquals(listOf(3L, 6L, 3000L, 5000L), missing)
    }
}
