package io.github.ricardofsc10.offsideplayer.data.xtream

import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XtreamClientTest {
    private val server = MockWebServer()
    private val client = XtreamClient(
        OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build(),
    )
    private lateinit var credentials: XtreamCredentials

    @Before
    fun setUp() {
        server.start()
        credentials = XtreamCredentials(server.url("/").toString().trimEnd('/'), "test", "a b/c")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `login envia as credenciais codificadas`() = runBlocking {
        respond(200, """{"user_info":{"auth":1,"status":"Active"}}""")
        client.login(credentials)
        val url = server.takeRequest().url
        assertEquals("/player_api.php", url.encodedPath)
        assertEquals("test", url.queryParameter("username"))
        assertEquals("a b/c", url.queryParameter("password"))
        assertEquals(null, url.queryParameter("action"))
    }

    @Test(expected = XtreamException.InvalidCredentials::class)
    fun `HTTP 401 são credenciais inválidas`(): Unit = runBlocking {
        respond(401, "")
        client.login(credentials)
    }

    @Test(expected = XtreamException.InvalidCredentials::class)
    fun `HTTP 403 são credenciais inválidas`(): Unit = runBlocking {
        respond(403, "")
        client.categories(credentials)
    }

    @Test
    fun `HTTP 500 é erro do servidor`() = runBlocking {
        respond(500, "")
        try {
            client.login(credentials)
            throw AssertionError("devia falhar")
        } catch (e: XtreamException.ServerError) {
            assertEquals(500, e.code)
        }
    }

    @Test(expected = XtreamException.Network::class)
    fun `servidor inacessível é erro de rede`(): Unit = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        client.login(credentials.copy(server = "http://127.0.0.1:$closedPort"))
    }

    @Test(expected = XtreamException.Timeout::class)
    fun `sem resposta dá timeout`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body("{}").headersDelay(2, TimeUnit.SECONDS).build(),
        )
        client.login(credentials)
    }

    @Test
    fun `lê canais por lotes e devolve o total`() = runBlocking {
        val body = (1..5).joinToString(",", "[", "]") { """{"name":"Canal $it","stream_id":$it}""" }
        respond(200, body)
        val batches = mutableListOf<Int>()
        val total = client.forEachChannelBatch(credentials, batchSize = 2) { batches += it.size }
        assertEquals(5, total)
        assertEquals(listOf(2, 2, 1), batches)
        assertEquals("get_live_streams", server.takeRequest().url.queryParameter("action"))
    }

    @Test(expected = XtreamException.MalformedResponse::class)
    fun `canais que não são lista dão resposta inválida`(): Unit = runBlocking {
        respond(200, """{"user_info":{"auth":0}}""")
        client.forEachChannelBatch(credentials) { }
    }

    @Test
    fun `URL do stream tem credenciais codificadas e extensão certa`() {
        val base = credentials.copy(server = "http://example.test:8080")
        assertEquals(
            "http://example.test:8080/live/test/a%20b%2Fc/7.ts",
            client.streamUrl(base, 7, StreamFormat.MPEG_TS),
        )
        assertEquals(
            "http://example.test:8080/live/test/a%20b%2Fc/7.m3u8",
            client.streamUrl(base, 7, StreamFormat.HLS),
        )
    }

    @Test
    fun `URL de filme e episódio usa a pasta e a extensão de cada um`() {
        val base = credentials.copy(server = "http://example.test:8080")
        assertEquals(
            "http://example.test:8080/movie/test/a%20b%2Fc/201.mkv",
            client.mediaUrl(base, MediaKind.MOVIE, 201, "mkv"),
        )
        assertEquals(
            "http://example.test:8080/series/test/a%20b%2Fc/1001.mp4",
            client.mediaUrl(base, MediaKind.EPISODE, 1001, "mp4"),
        )
    }

    @Test
    fun `lê filmes e séries por lotes com a ação certa`() = runBlocking {
        respond(200, (1..3).joinToString(",", "[", "]") { """{"name":"Filme $it","stream_id":$it}""" })
        respond(200, (1..4).joinToString(",", "[", "]") { """{"name":"Série $it","series_id":$it}""" })
        assertEquals(3, client.forEachMovieBatch(credentials, batchSize = 2) { })
        assertEquals(4, client.forEachSeriesBatch(credentials, batchSize = 2) { })
        assertEquals("get_vod_streams", server.takeRequest().url.queryParameter("action"))
        assertEquals("get_series", server.takeRequest().url.queryParameter("action"))
    }

    @Test
    fun `pede as categorias de filmes e de séries`() = runBlocking {
        respond(200, """[{"category_id":"1","category_name":"Ação"}]""")
        respond(200, """[{"category_id":"1","category_name":"Drama"}]""")
        assertEquals(listOf(XtreamCategory("1", "Ação")), client.movieCategories(credentials))
        assertEquals(listOf(XtreamCategory("1", "Drama")), client.seriesCategories(credentials))
        assertEquals("get_vod_categories", server.takeRequest().url.queryParameter("action"))
        assertEquals("get_series_categories", server.takeRequest().url.queryParameter("action"))
    }

    @Test
    fun `pede os episódios e o detalhe pelo id`() = runBlocking {
        respond(200, """{"episodes":{"1":[{"id":"5","episode_num":1}]}}""")
        respond(200, """{"info":{"plot":"Resumo"}}""")
        assertEquals(listOf(5L), client.seriesEpisodes(credentials, 42).map { it.id })
        assertEquals("Resumo", client.movieInfo(credentials, 43).plot)
        val episodes = server.takeRequest().url
        assertEquals("get_series_info", episodes.queryParameter("action"))
        assertEquals("42", episodes.queryParameter("series_id"))
        val info = server.takeRequest().url
        assertEquals("get_vod_info", info.queryParameter("action"))
        assertEquals("43", info.queryParameter("vod_id"))
    }

    @Test
    fun `credenciais não aparecem em toString nem nos erros`() = runBlocking {
        assertFalse(credentials.toString().contains("test"))
        respond(500, "")
        val error = runCatching { client.login(credentials) }.exceptionOrNull()
        assertTrue(error is XtreamException)
        assertFalse(error.toString().contains("a b/c"))
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }
}
