package io.github.ricardofsc10.offsideplayer.ui.theme

import io.github.ricardofsc10.offsideplayer.data.epg.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadcastTextTest {
    @Test
    fun `nome curto tira o país e a qualidade`() {
        assertEquals("ARENA 1", channelTag("PT: Arena 1 FHD"))
        assertEquals("SPORT TV 1", channelTag("PT | Sport TV 1 HD"))
        assertEquals("CANAL SETE", channelTag("Canal Sete 4K"))
        assertEquals("TRIBUNA SUR 1", channelTag("ES: Tribuna Sur 1 ᴴᴰ"))
    }

    @Test
    fun `nome só com a qualidade fica como está`() {
        assertEquals("HD", channelTag("HD"))
    }

    @Test
    fun `separa a competição do jogo`() {
        val match = matchTitle("Liga: Leões do Mar - Atlético Norte")
        assertEquals("Liga", match.competition)
        assertEquals("Leões do Mar – Atlético Norte", match.title)
    }

    @Test
    fun `só o desporto tem competição`() {
        val novela = EpgProgram("c", 0, 1, "Novela: Laços de Família", sport = false)
        assertNull(programTitle(novela).competition)
        assertEquals("Novela: Laços de Família", programTitle(novela).title)
        val jogo = EpgProgram("c", 0, 1, "Taça: Ribeira FC - Os Unidos", sport = true)
        assertEquals("Taça", programTitle(jogo).competition)
    }
}
