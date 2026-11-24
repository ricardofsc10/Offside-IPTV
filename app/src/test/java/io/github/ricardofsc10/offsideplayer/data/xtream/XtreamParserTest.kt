package io.github.ricardofsc10.offsideplayer.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamParserTest {

    @Test
    fun `lê conta com campos em texto`() {
        val account = XtreamParser.parseAccount(
            """
            {"user_info":{"auth":1,"status":"Active","exp_date":"1893456000","active_cons":"0",
            "max_connections":"2","allowed_output_formats":["m3u8","ts"]},
            "server_info":{"url":"example.test"}}
            """,
        )
        assertEquals("Active", account.status)
        assertEquals(1893456000L, account.expiresAtEpochSeconds)
        assertEquals(2, account.maxConnections)
        assertEquals(0, account.activeConnections)
        assertEquals(listOf("m3u8", "ts"), account.allowedFormats)
    }

    @Test
    fun `lê conta com campos numéricos e nulos`() {
        val account = XtreamParser.parseAccount(
            """{"user_info":{"auth":"1","status":"Active","exp_date":null,"active_cons":1,"max_connections":1}}""",
        )
        assertNull(account.expiresAtEpochSeconds)
        assertEquals(1, account.activeConnections)
        assertEquals(emptyList<String>(), account.allowedFormats)
    }

    @Test(expected = XtreamException.InvalidCredentials::class)
    fun `auth a zero são credenciais inválidas`() {
        XtreamParser.parseAccount("""{"user_info":{"auth":0}}""")
    }

    @Test
    fun `conta expirada indica o estado`() {
        try {
            XtreamParser.parseAccount("""{"user_info":{"auth":1,"status":"Expired"}}""")
            throw AssertionError("devia falhar")
        } catch (e: XtreamException.AccountInactive) {
            assertEquals("Expired", e.status)
        }
    }

    @Test(expected = XtreamException.MalformedResponse::class)
    fun `resposta de login em lista é inválida`() {
        XtreamParser.parseAccount("[]")
    }

    @Test(expected = XtreamException.MalformedResponse::class)
    fun `resposta de login que não é JSON é inválida`() {
        XtreamParser.parseAccount("<html>erro</html>")
    }

    @Test
    fun `lê categorias com id numérico ou em texto e ignora incompletas`() {
        val categories = XtreamParser.parseCategories(
            """
            [{"category_id":"1","category_name":"Futebol","parent_id":0},
             {"category_id":2,"category_name":"Motores"},
             {"category_id":null,"category_name":"Sem id"},
             {"category_id":"4","category_name":""}]
            """,
        )
        assertEquals(listOf(XtreamCategory("1", "Futebol"), XtreamCategory("2", "Motores")), categories)
    }

    @Test
    fun `lê canais com variações de formato`() {
        val channels = parseChannels(
            """
            [{"num":1,"name":"Canal Um","stream_type":"live","stream_id":101,"stream_icon":"http://example.test/1.png",
              "epg_channel_id":"um.pt","category_id":"1"},
             {"num":"2","name":"Canal Dois","stream_type":"created_live","stream_id":"102","stream_icon":"",
              "epg_channel_id":null,"category_id":2},
             {"name":"Canal Três","stream_id":103,"category_id":[]}]
            """,
        )
        assertEquals(listOf(101L, 102L, 103L), channels.map { it.streamId })
        assertEquals(1, channels[0].number)
        assertEquals("um.pt", channels[0].epgChannelId)
        assertEquals(2, channels[1].number)
        assertNull(channels[1].iconUrl)
        assertNull(channels[1].epgChannelId)
        assertEquals("2", channels[1].categoryId)
        assertNull(channels[2].number)
        assertNull(channels[2].categoryId)
    }

    @Test
    fun `ignora canais sem id ou nome e tipos que não são em direto`() {
        val channels = parseChannels(
            """
            [{"name":"Sem id","stream_type":"live"},
             {"stream_id":1,"stream_type":"live"},
             {"name":"Filme","stream_id":2,"stream_type":"movie"},
             {"name":"Rádio","stream_id":3,"stream_type":"radio_streams"},
             {"name":"Válido","stream_id":"abc"},
             {"name":"Válido","stream_id":4}]
            """,
        )
        assertEquals(listOf(4L), channels.map { it.streamId })
    }

    @Test
    fun `lê os dias de arquivo só quando o canal tem arquivo`() {
        val channels = parseChannels(
            """
            [{"stream_id":1,"name":"A","tv_archive":1,"tv_archive_duration":"7"},
             {"stream_id":2,"name":"B","tv_archive":"0","tv_archive_duration":3},
             {"stream_id":3,"name":"C"}]
            """,
        )
        assertEquals(listOf(7, null, null), channels.map { it.archiveDays })
    }

    @Test
    fun `lista vazia não dá canais`() {
        assertTrue(parseChannels("[]").isEmpty())
    }

    private fun parseChannels(text: String): List<LiveChannel> =
        text.byteInputStream().use { XtreamParser.channels(it).toList() }
}
