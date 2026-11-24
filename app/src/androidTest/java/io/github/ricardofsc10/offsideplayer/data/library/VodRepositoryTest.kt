package io.github.ricardofsc10.offsideplayer.data.library

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ricardofsc10.offsideplayer.data.xtream.Episode
import io.github.ricardofsc10.offsideplayer.data.xtream.Movie
import io.github.ricardofsc10.offsideplayer.data.xtream.MovieInfo
import io.github.ricardofsc10.offsideplayer.data.xtream.Series
import io.github.ricardofsc10.offsideplayer.data.xtream.VodSource
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCategory
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VodRepositoryTest {
    private val credentials = XtreamCredentials("http://example.test", "test", "test")
    private val source = FakeVodSource()
    private var now = 1_000L
    private lateinit var database: LibraryDatabase
    private lateinit var repository: VodRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        repository = VodRepository(database, source, clock = { now }, elapsed = { now })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun guardaFilmesESeriesComAsCategoriasDeCadaTipo() = runBlocking {
        // O mesmo ID de categoria nos filmes e nas séries não pode colidir.
        source.movieCategories = listOf(category("1", "Ação"), category("2", "Drama"))
        source.seriesCategories = listOf(category("1", "Comédia"))
        source.movies = listOf(movie(10, "1"), movie(11, "2"), movie(12, "1", "mkv"))
        source.series = listOf(series(20, "1"))
        val report = repository.sync(credentials)

        assertEquals(3, report.movies)
        assertEquals(1, report.series)
        assertEquals("mkv:1,mp4:2", report.movieExtensions)
        assertEquals(listOf("Ação" to 2, "Drama" to 1), repository.movieCategories.first().map { it.name to it.itemCount })
        assertEquals(listOf("Comédia" to 1), repository.seriesCategories.first().map { it.name to it.itemCount })
        assertEquals(listOf(10L, 12L), database.vodDao().moviesInCategory("1").all().map { it.streamId })
    }

    @Test
    fun categoriaEscondidaSobreviveASincronizacao() = runBlocking {
        source.movieCategories = listOf(category("1"))
        source.movies = listOf(movie(10, "1"))
        repository.sync(credentials)
        repository.setCategoryHidden(VodKind.MOVIE, "1", hidden = true)

        now += 1
        repository.sync(credentials)
        assertTrue(repository.movieCategories.first().single().hidden)
        assertTrue(search("filme").isEmpty())
    }

    @Test
    fun apagaOQueDesapareceuEOProgressoDele() = runBlocking {
        source.movies = listOf(movie(10, null), movie(11, null))
        source.series = listOf(series(20, null))
        repository.sync(credentials)
        repository.saveProgress(ProgressKind.MOVIE, 10, null, 60_000, 6_000_000)
        repository.saveProgress(ProgressKind.MOVIE, 11, null, 60_000, 6_000_000)
        repository.saveProgress(ProgressKind.EPISODE, 2001, 20, 60_000, 2_700_000)

        now += 1
        source.movies = listOf(movie(11, null))
        source.series = emptyList()
        repository.sync(credentials)

        assertNull(repository.progress(ProgressKind.MOVIE, 10))
        assertEquals(60_000L, repository.progress(ProgressKind.MOVIE, 11)?.positionMs)
        assertNull(repository.progress(ProgressKind.EPISODE, 2001))
    }

    @Test
    fun falhaAMeioNaoApagaNada() = runBlocking {
        source.movies = listOf(movie(10, null))
        repository.sync(credentials)

        now += 1
        source.movies = listOf(movie(11, null))
        source.failSeries = true
        try {
            repository.sync(credentials)
            fail("devia falhar")
        } catch (_: XtreamException.Network) {
        }
        assertEquals(listOf(10L, 11L), search("filme").sorted())
    }

    @Test
    fun episodiosSaoPedidosSoQuandoEstaoAntigos() = runBlocking {
        source.series = listOf(series(20, null))
        repository.sync(credentials)
        source.episodes = listOf(episode(2001, 1, 1), episode(2002, 1, 2))

        repository.refreshEpisodesIfStale(credentials, 20)
        repository.refreshEpisodesIfStale(credentials, 20)
        assertEquals(1, source.episodeRequests)
        assertEquals(listOf(2001L, 2002L), repository.episodes(20).first().map { it.id })
        assertEquals(2002L, repository.nextEpisode(20, 2001)?.id)
        assertNull(repository.nextEpisode(20, 2002))

        now += EpisodesPolicy.REFRESH_MS
        source.episodes = listOf(episode(2002, 1, 2))
        repository.refreshEpisodesIfStale(credentials, 20)
        assertEquals(2, source.episodeRequests)
        assertEquals(listOf(2002L), repository.episodes(20).first().map { it.id })
    }

    @Test
    fun progressoMarcaVistoESaiDeContinuarAVer() = runBlocking {
        source.movies = listOf(movie(10, null), movie(11, null))
        repository.sync(credentials)
        repository.saveProgress(ProgressKind.MOVIE, 10, null, 600_000, 6_000_000)
        now += 1
        repository.saveProgress(ProgressKind.MOVIE, 11, null, 5_900_000, 6_000_000)

        assertTrue(repository.progress(ProgressKind.MOVIE, 11)!!.finished)
        assertEquals(listOf(10L), repository.unfinished().first().map { it.id })
    }

    @Test
    fun removerDeContinuarAVerDuraAteVoltarAVer() = runBlocking {
        source.movies = listOf(movie(10, null))
        source.series = listOf(series(20, null))
        repository.sync(credentials)
        source.episodes = listOf(episode(2001, 1, 1), episode(2002, 1, 2))
        repository.refreshEpisodesIfStale(credentials, 20)
        repository.saveProgress(ProgressKind.MOVIE, 10, null, 600_000, 6_000_000)
        repository.saveProgress(ProgressKind.EPISODE, 2001, 20, 600_000, 2_700_000)
        val dao = database.vodDao()
        assertEquals(listOf(10L), dao.continueMovies().all().map { it.streamId })
        assertEquals(listOf(20L), dao.continueSeries().all().map { it.seriesId })

        now += 1
        repository.removeFromContinue(VodKind.MOVIE, 10)
        repository.removeFromContinue(VodKind.SERIES, 20)
        assertEquals(emptyList<Long>(), dao.continueMovies().all().map { it.streamId })
        assertEquals(emptyList<Long>(), dao.continueSeries().all().map { it.seriesId })
        // A posição fica guardada.
        assertEquals(600_000L, repository.progress(ProgressKind.MOVIE, 10)!!.positionMs)

        now += 1
        repository.saveProgress(ProgressKind.MOVIE, 10, null, 700_000, 6_000_000)
        repository.saveProgress(ProgressKind.EPISODE, 2002, 20, 60_000, 2_700_000)
        assertEquals(listOf(10L), dao.continueMovies().all().map { it.streamId })
        assertEquals(listOf(20L), dao.continueSeries().all().map { it.seriesId })
    }

    private suspend fun search(query: String): List<Long> =
        database.vodDao().searchMovies(TextNormalizer.likePattern(query)).all().map { it.streamId }

    private suspend fun <T : Any> PagingSource<Int, T>.all(): List<T> {
        val result = load(PagingSource.LoadParams.Refresh(null, 100, false))
        return (result as PagingSource.LoadResult.Page).data
    }

    private fun category(id: String, name: String = "Categoria $id") = XtreamCategory(id, name)

    private fun movie(id: Long, categoryId: String?, extension: String = "mp4") =
        Movie(id, "Filme $id", categoryId, null, extension, null, null)

    private fun series(id: Long, categoryId: String?) =
        Series(id, "Série $id", categoryId, null, null, null, null, null)

    private fun episode(id: Long, season: Int, number: Int) =
        Episode(id, season, number, "Episódio $number", "mkv", 2700, null, null)

    private class FakeVodSource : VodSource {
        var movieCategories = emptyList<XtreamCategory>()
        var seriesCategories = emptyList<XtreamCategory>()
        var movies = emptyList<Movie>()
        var series = emptyList<Series>()
        var episodes = emptyList<Episode>()
        var failSeries = false
        var episodeRequests = 0

        override suspend fun movieCategories(credentials: XtreamCredentials) = movieCategories

        override suspend fun seriesCategories(credentials: XtreamCredentials) = seriesCategories

        override suspend fun forEachMovieBatch(
            credentials: XtreamCredentials,
            batchSize: Int,
            onBatch: suspend (List<Movie>) -> Unit,
        ): Int {
            movies.chunked(1).forEach { onBatch(it) }
            return movies.size
        }

        override suspend fun forEachSeriesBatch(
            credentials: XtreamCredentials,
            batchSize: Int,
            onBatch: suspend (List<Series>) -> Unit,
        ): Int {
            if (failSeries) throw XtreamException.Network()
            series.chunked(1).forEach { onBatch(it) }
            return series.size
        }

        override suspend fun seriesEpisodes(credentials: XtreamCredentials, seriesId: Long): List<Episode> {
            episodeRequests++
            return episodes
        }

        override suspend fun movieInfo(credentials: XtreamCredentials, streamId: Long) =
            MovieInfo(null, null, null, null, null)
    }
}
