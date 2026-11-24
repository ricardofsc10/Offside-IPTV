package io.github.ricardofsc10.offsideplayer.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRulesTest {
    private val now = 100L * SyncPolicy.INTERVAL_MS

    @Test
    fun `sincroniza se nunca sincronizou`() {
        assertTrue(SyncPolicy.isDue(null, now))
    }

    @Test
    fun `não sincroniza antes das 8 horas`() {
        assertFalse(SyncPolicy.isDue(now - SyncPolicy.INTERVAL_MS + 1, now))
    }

    @Test
    fun `sincroniza às 8 horas`() {
        assertTrue(SyncPolicy.isDue(now - SyncPolicy.INTERVAL_MS, now))
    }

    @Test
    fun `sincroniza se o relógio andou para trás`() {
        assertTrue(SyncPolicy.isDue(now + 1, now))
    }

    @Test
    fun `normaliza acentos e maiúsculas`() {
        assertEquals("sao joao fc", TextNormalizer.normalize("  São João FC "))
        assertEquals("acores", TextNormalizer.normalize("AÇORES"))
    }

    @Test
    fun `padrão de pesquisa normaliza e procura em qualquer parte`() {
        assertEquals("%sao joao%", TextNormalizer.likePattern(" São João "))
    }

    @Test
    fun `padrão de pesquisa trata caracteres especiais como texto`() {
        assertEquals("%100\\%%", TextNormalizer.likePattern("100%"))
        assertEquals("%a\\_b%", TextNormalizer.likePattern("a_b"))
        assertEquals("%a\\\\b%", TextNormalizer.likePattern("a\\b"))
    }
}
