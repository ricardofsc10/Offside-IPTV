package io.github.ricardofsc10.offsideplayer.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamVodParserTest {

    @Test
    fun `lê filmes com variações de formato`() {
        val movies = parseMovies(
            """
            [{"num":1,"name":"Filme Um","stream_type":"movie","stream_id":201,"stream_icon":"http://example.test/1.jpg",
              "rating":"7.5","added":"1700000000","category_id":"10","container_extension":"MKV"},
             {"name":"Filme Dois","stream_id":"202","stream_icon":"","rating":null,"added":1700000001,
              "category_id":11,"container_extension":"mp4"},
             {"name":"Filme Três","stream_id":203,"category_id":[]}]
            """,
        )
        assertEquals(listOf(201L, 202L, 203L), movies.map { it.streamId })
        assertEquals("http://example.test/1.jpg", movies[0].posterUrl)
        assertEquals("mkv", movies[0].containerExtension)
        assertEquals("7.5", movies[0].rating)
        assertEquals(1700000000L, movies[0].addedAtEpochSeconds)
        assertNull(movies[1].posterUrl)
        assertNull(movies[1].rating)
        assertEquals("11", movies[1].categoryId)
        assertNull(movies[2].categoryId)
        assertEquals("mp4", movies[2].containerExtension)
    }

    @Test
    fun `ignora filmes sem id ou nome`() {
        val movies = parseMovies(
            """[{"name":"Sem id"},{"stream_id":1},{"name":"Id inválido","stream_id":"x"},{"name":"Válido","stream_id":2}]""",
        )
        assertEquals(listOf(2L), movies.map { it.streamId })
    }

    @Test
    fun `lê séries com as duas formas da data`() {
        val series = parseSeries(
            """
            [{"name":"Série Um","series_id":301,"cover":"http://example.test/s.jpg","plot":"Resumo",
              "releaseDate":"2020-01-01","rating":"8","last_modified":"1700000000","category_id":"20"},
             {"name":"Série Dois","series_id":"302","cover":"","release_date":"2021-05-05","category_id":21},
             {"name":"Sem id"}]
            """,
        )
        assertEquals(listOf(301L, 302L), series.map { it.seriesId })
        assertEquals("2020-01-01", series[0].releaseDate)
        assertEquals(1700000000L, series[0].lastModifiedEpochSeconds)
        assertEquals("2021-05-05", series[1].releaseDate)
        assertNull(series[1].coverUrl)
    }

    @Test
    fun `lê episódios agrupados por temporada num objeto`() {
        val episodes = XtreamParser.parseSeriesEpisodes(
            """
            {"seasons":[],"info":{"name":"Série"},
             "episodes":{
               "2":[{"id":"1003","episode_num":1,"title":"T2 E1","container_extension":"mkv","season":2,
                     "info":{"duration_secs":2700,"plot":"Resumo","movie_image":"http://example.test/e.jpg"}}],
               "1":[{"id":"1002","episode_num":"2","title":"T1 E2","container_extension":"mp4",
                     "info":{"duration":"00:44:30"}},
                    {"id":1001,"episode_num":1,"title":"T1 E1","info":[]}]}}
            """,
        )
        assertEquals(listOf(1001L, 1002L, 1003L), episodes.map { it.id })
        assertEquals(listOf(1, 1, 2), episodes.map { it.season })
        assertEquals(2, episodes[1].number)
        assertEquals(2670, episodes[1].durationSeconds)
        assertEquals(2700, episodes[2].durationSeconds)
        assertEquals("mkv", episodes[2].containerExtension)
        assertEquals("http://example.test/e.jpg", episodes[2].imageUrl)
        assertNull(episodes[0].durationSeconds)
        assertEquals("mp4", episodes[0].containerExtension)
    }

    @Test
    fun `lê episódios numa lista de listas`() {
        val episodes = XtreamParser.parseSeriesEpisodes(
            """
            {"episodes":[[{"id":"2001","episode_num":1,"season":1}],
                         [{"id":"2002","episode_num":1,"season":"2"},{"id":"2001","episode_num":1,"season":1}]]}
            """,
        )
        assertEquals(listOf(2001L, 2002L), episodes.map { it.id })
        assertEquals(listOf(1, 2), episodes.map { it.season })
    }

    @Test
    fun `série sem episódios dá lista vazia`() {
        assertTrue(XtreamParser.parseSeriesEpisodes("""{"episodes":[]}""").isEmpty())
        assertTrue(XtreamParser.parseSeriesEpisodes("""{"seasons":[]}""").isEmpty())
    }

    @Test(expected = XtreamException.MalformedResponse::class)
    fun `episódios numa resposta que não é objeto são inválidos`() {
        XtreamParser.parseSeriesEpisodes("[]")
    }

    @Test
    fun `lê o detalhe de um filme`() {
        val info = XtreamParser.parseMovieInfo(
            """
            {"info":{"plot":"Resumo","duration":"01:35:10","releasedate":"2019-03-01","genre":"Drama",
                     "movie_image":"http://example.test/m.jpg"},
             "movie_data":{"stream_id":201}}
            """,
        )
        assertEquals("Resumo", info.plot)
        assertEquals(5710, info.durationSeconds)
        assertEquals("2019-03-01", info.releaseDate)
        assertEquals("Drama", info.genre)
        assertEquals("http://example.test/m.jpg", info.coverUrl)
    }

    @Test
    fun `detalhe sem dados vem com info em lista vazia`() {
        val info = XtreamParser.parseMovieInfo("""{"info":[],"movie_data":{}}""")
        assertNull(info.plot)
        assertNull(info.durationSeconds)
    }

    @Test
    fun `duração inválida ou a zero é ignorada`() {
        val zero = XtreamParser.parseMovieInfo("""{"info":{"duration_secs":0,"duration":"00:00:00"}}""")
        assertNull(zero.durationSeconds)
        val invalid = XtreamParser.parseMovieInfo("""{"info":{"duration":"1h30"}}""")
        assertNull(invalid.durationSeconds)
    }

    private fun parseMovies(text: String): List<Movie> =
        text.byteInputStream().use { XtreamParser.movies(it).toList() }

    private fun parseSeries(text: String): List<Series> =
        text.byteInputStream().use { XtreamParser.series(it).toList() }
}
