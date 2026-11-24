package io.github.ricardofsc10.offsideplayer.ui.vod

import io.github.ricardofsc10.offsideplayer.data.library.EpisodeRow
import io.github.ricardofsc10.offsideplayer.ui.components.initialOf
import org.junit.Assert.assertEquals
import org.junit.Test

class VodFormatTest {

    @Test
    fun `formata durações`() {
        assertEquals("45 min", formatDuration(2_700))
        assertEquals("1 h 35 min", formatDuration(5_700))
        assertEquals("2 h", formatDuration(7_200))
    }

    @Test
    fun `formata posições`() {
        assertEquals("0:05", formatClock(5_000))
        assertEquals("12:34", formatClock(754_000))
        assertEquals("1:05:09", formatClock(3_909_000))
    }

    @Test
    fun `inicial ignora símbolos`() {
        assertEquals("F", initialOf("filme"))
        assertEquals("P", initialOf("| PT | Canal"))
        assertEquals("1", initialOf("[1] Desporto"))
        assertEquals("?", initialOf("★"))
    }

    // Os textos vêm dos recursos na app. Aqui passam-se os portugueses.
    private fun title(episode: EpisodeRow) = episodeTitle(episode, { "Episódio $it" }, { "Episódio" })
    private fun season(n: Int) = seasonLabel(n, { "Temporada $it" }, { "Extras" })

    @Test
    fun `título do episódio com e sem número ou nome`() {
        assertEquals("3. O regresso", title(episode(number = 3, title = "O regresso")))
        assertEquals("O regresso", title(episode(number = null, title = "O regresso")))
        assertEquals("Episódio 3", title(episode(number = 3, title = " ")))
        assertEquals("Episódio", title(episode(number = null, title = null)))
    }

    @Test
    fun `temporada zero são extras`() {
        assertEquals("Temporada 2", season(2))
        assertEquals("Extras", season(0))
    }

    private fun episode(number: Int?, title: String?) =
        EpisodeRow(1, 1, 1, number, title, "mkv", null, null, null, null, null, null)
}
