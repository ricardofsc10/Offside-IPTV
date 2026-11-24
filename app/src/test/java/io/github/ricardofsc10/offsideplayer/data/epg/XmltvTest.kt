package io.github.ricardofsc10.offsideplayer.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class XmltvTest {
    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="SportTV1.pt">
            <display-name>PT - Sport TV 1</display-name>
            <icon src="https://example.test/a.png" />
            <display-name lang="pt">VO: SPORT TV 1 ᴴᴰ</display-name>
          </channel>
          <channel id="Kids.pt"><display-name>Kids</display-name></channel>
          <channel><display-name>Sem id</display-name></channel>
          <programme start="20261109200000 +0000" stop="20261109220000 +0000" channel="SportTV1.pt">
            <category lang="pt">Desporto</category>
            <credits><actor>Alguém</actor></credits>
            <title lang="en">Benfica vs Porto</title>
            <title lang="pt">Benfica - Porto</title>
            <desc lang="pt">Liga Portugal.</desc>
          </programme>
          <programme start="20261109100000 +0100" stop="20261109110000 +0100" channel="Kids.pt">
            <title>Desenhos</title>
            <category>Infantil</category>
          </programme>
          <programme start="20261101000000 +0000" stop="20261101010000 +0000" channel="Kids.pt">
            <title>Antigo</title>
          </programme>
          <programme start="mau" stop="20261109110000 +0000" channel="Kids.pt"><title>X</title></programme>
          <programme start="20261109120000 +0000" stop="20261109130000 +0000" channel="Kids.pt"><desc>Sem título</desc></programme>
        </tv>
    """.trimIndent()

    private fun parse(keep: (Long, Long) -> Boolean = { _, _ -> true }): Pair<List<XmltvChannel>, List<XmltvProgram>> {
        val channels = mutableListOf<XmltvChannel>()
        val programs = mutableListOf<XmltvProgram>()
        XmltvParser.parse(KXmlParser(), sample.byteInputStream(), keep, channels::add, programs::add)
        return channels to programs
    }

    @Test
    fun `lê canais com todos os nomes e ignora os que não têm id`() {
        val (channels, _) = parse()
        assertEquals(listOf("SportTV1.pt", "Kids.pt"), channels.map { it.id })
        assertEquals(listOf("PT - Sport TV 1", "VO: SPORT TV 1 ᴴᴰ"), channels[0].names)
    }

    @Test
    fun `lê programas, prefere o título em português e marca o desporto`() {
        val (_, programs) = parse()
        val game = programs.first()
        assertEquals("Benfica - Porto", game.title)
        assertEquals("Liga Portugal.", game.description)
        assertTrue(game.sport)
        assertFalse(programs[1].sport)
        // Sem título ou com data inválida, o programa fica de fora.
        assertEquals(listOf("Benfica - Porto", "Desenhos", "Antigo"), programs.map { it.title })
    }

    @Test
    fun `só guarda os programas da janela pedida`() {
        val from = XmltvParser.parseTime("20261109000000 +0000")!!
        val (_, programs) = parse { _, stop -> stop > from }
        assertEquals(listOf("Benfica - Porto", "Desenhos"), programs.map { it.title })
    }

    @Test
    fun `lê horas com fuso e sem fuso`() {
        assertEquals(1_794_254_400_000L, XmltvParser.parseTime("20261109200000 +0000"))
        assertEquals(1_794_250_800_000L, XmltvParser.parseTime("20261109200000 +0100"))
        assertEquals(1_794_254_400_000L, XmltvParser.parseTime("202611092000"))
        assertNull(XmltvParser.parseTime("2026-11-09"))
        assertNull(XmltvParser.parseTime(null))
    }

    @Test
    fun `liga pelo id, pelo id com outras maiúsculas e pelo nome`() {
        val guide = listOf(
            XmltvChannel("SportTV1.pt", listOf("VO: SPORT TV 1 ᴴᴰ")),
            XmltvChannel("Kids.pt", listOf("Kids")),
        )
        val (links, report) = EpgMatcher.link(
            listOf(
                ChannelEpgKey(1, "SportTV1.pt", "qualquer"),
                ChannelEpgKey(2, "kids.PT", "outro"),
                ChannelEpgKey(3, null, "VO: Sport TV 1 HD"),
                ChannelEpgKey(4, "", "Sem guia"),
            ),
            guide,
        )
        assertEquals(mapOf(1L to "SportTV1.pt", 2L to "Kids.pt", 3L to "SportTV1.pt"), links)
        assertEquals(EpgLinkReport(byId = 2, byName = 1, unlinked = 1), report)
    }

    @Test
    fun `guia dá o programa atual, os seguintes e o desporto a dar`() {
        val sport = EpgProgram("S", 1_000, 2_000, "Jogo", sport = true)
        val after = EpgProgram("S", 2_000, 3_000, "Resumo", sport = false)
        val kids = EpgProgram("K", 1_000, 2_000, "Desenhos", sport = false)
        val guide = EpgGuide(mapOf(1L to "S", 2L to "S", 3L to "K"), mapOf("S" to listOf(sport, after), "K" to listOf(kids)))
        assertEquals(sport, guide.now(2, 1_500))
        assertEquals(after, guide.now(1, 2_000))
        assertNull(guide.now(9, 1_500))
        assertEquals(listOf(sport, after), guide.upcoming(1, 1_500, 5))
        assertEquals(listOf(after), guide.upcoming(1, 2_500, 5))
        assertEquals(mapOf("S" to sport), guide.sportNow(1_500))
        assertEquals(0.5f, sport.progress(1_500))
    }
}
