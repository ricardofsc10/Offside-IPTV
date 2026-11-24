package io.github.ricardofsc10.offsideplayer.data.library

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ricardofsc10.offsideplayer.data.xtream.AccountInfo
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCategory
import io.github.ricardofsc10.offsideplayer.data.xtream.LiveChannel
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamException
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private val credentials = XtreamCredentials("http://example.test", "test", "test")
    private val source = FakeSource()
    private var now = 1_000L
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        repository = LibraryRepository(database, source) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun guardaCategoriasECanaisPelaOrdemDoFornecedor() = runBlocking {
        source.categories = listOf(category("b"), category("a"))
        source.channels = listOf(channel(2, "b"), channel(1, "a"), channel(3, "b"))
        repository.sync(credentials)

        val categories = repository.categories.first()
        assertEquals(listOf("b", "a"), categories.map { it.id })
        assertEquals(listOf(2, 1), categories.map { it.channelCount })
        assertEquals(listOf(2L, 1L, 3L), storedChannelIds())
    }

    @Test
    fun categoriaEscondidaSobreviveASincronizacao() = runBlocking {
        source.categories = listOf(category("a"), category("b"))
        source.channels = listOf(channel(1, "a"))
        repository.sync(credentials)
        repository.setCategoryHidden("a", true)

        source.categories = listOf(category("a", name = "Novo nome"), category("b"))
        now += 1
        repository.sync(credentials)

        val a = repository.categories.first().first { it.id == "a" }
        assertTrue(a.hidden)
        assertEquals("Novo nome", a.name)
        assertFalse(repository.categories.first().first { it.id == "b" }.hidden)
    }

    @Test
    fun favoritoSobreviveEDesapareceComOCanal() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(channel(1, "a"), channel(2, "a"))
        repository.sync(credentials)
        repository.addFavorite(1)
        repository.addFavorite(2)

        source.channels = listOf(channel(2, "a"))
        now += 1
        repository.sync(credentials)

        assertEquals(listOf(2L), repository.favoriteChannels.first().map { it.streamId })
        assertEquals(listOf(2L), storedChannelIds())
    }

    @Test
    fun removeCategoriaQueDesapareceu() = runBlocking {
        source.categories = listOf(category("a"), category("b"))
        source.channels = listOf(channel(1, "a"))
        repository.sync(credentials)

        source.categories = listOf(category("a"))
        now += 1
        repository.sync(credentials)

        assertEquals(listOf("a"), repository.categories.first().map { it.id })
    }

    @Test
    fun falhaAMeioNaoPerdeDados() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(channel(1, "a"), channel(2, "a"))
        repository.sync(credentials)
        repository.addFavorite(1)
        val firstSync = repository.syncInfo.first()?.lastSyncAt

        source.failAfterBatch = true
        now += 1
        try {
            repository.sync(credentials)
            fail("devia falhar")
        } catch (_: XtreamException.Network) {
        }

        assertEquals(listOf(1L, 2L), storedChannelIds().sorted())
        assertEquals(listOf(1L), repository.favoriteChannels.first().map { it.streamId })
        assertEquals(firstSync, repository.syncInfo.first()?.lastSyncAt)
    }

    @Test
    fun listaVaziaDoServidorNaoApagaNada() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(channel(1, "a"))
        repository.sync(credentials)

        source.channels = emptyList()
        now += 1
        try {
            repository.sync(credentials)
            fail("devia falhar")
        } catch (_: XtreamException.EmptyLibrary) {
        }

        assertEquals(listOf(1L), storedChannelIds())
    }

    @Test
    fun guardaEstadoDaContaSemCredenciais() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(channel(1, "a"))
        repository.sync(credentials)

        val info = repository.syncInfo.first()!!
        assertEquals(1_000L, info.lastSyncAt)
        assertEquals("Active", info.accountStatus)
        assertEquals("m3u8,ts", info.allowedFormats)
        assertFalse(info.toString().contains("test"))
    }

    @Test
    fun sincronizacaoDevidaSegueOIntervalo() = runBlocking {
        assertTrue(repository.isSyncDue())
        source.categories = listOf(category("a"))
        source.channels = listOf(channel(1, "a"))
        repository.sync(credentials)
        assertFalse(repository.isSyncDue())

        now += SyncPolicy.INTERVAL_MS
        assertTrue(repository.isSyncDue())
    }

    @Test
    fun limparApagaTudo() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(channel(1, "a"))
        repository.sync(credentials)
        repository.addFavorite(1)

        repository.clear()

        assertTrue(repository.categories.first().isEmpty())
        assertTrue(repository.favoriteChannels.first().isEmpty())
        assertNull(repository.syncInfo.first())
    }

    @Test
    fun canaisDaCategoriaVemPorOrdemEMarcamFavoritos() = runBlocking {
        source.categories = listOf(category("a"), category("b"))
        source.channels = listOf(channel(3, "a"), channel(1, "b"), channel(2, "a"))
        repository.sync(credentials)
        repository.addFavorite(2)

        val rows = database.dao().channelsInCategory("a").all()
        assertEquals(listOf(3L, 2L), rows.map { it.streamId })
        assertEquals(listOf(false, true), rows.map { it.favorite })
    }

    @Test
    fun pesquisaIgnoraAcentosMaiusculasEProcuraNoMeio() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(
            LiveChannel(1, 1, "SPORT São João HD", "a", null, null),
            LiveChannel(2, 2, "Outro", "a", null, null),
        )
        repository.sync(credentials)

        assertEquals(listOf(1L), search("sao joao"))
        assertEquals(listOf(1L), search("JOÃO"))
        assertEquals(emptyList<Long>(), search("inexistente"))
    }

    @Test
    fun pesquisaNaoInterpretaCaracteresEspeciais() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(
            LiveChannel(1, 1, "Canal 100%", "a", null, null),
            LiveChannel(2, 2, "Canal 1000", "a", null, null),
        )
        repository.sync(credentials)

        assertEquals(listOf(1L), search("100%"))
        assertEquals(emptyList<Long>(), search("c_nal"))
    }

    @Test
    fun pesquisaExcluiCategoriasEscondidasMasFavoritoContinua() = runBlocking {
        source.categories = listOf(category("a"), category("b"))
        source.channels = listOf(channel(1, "a"), channel(2, "b"), channel(3, "desconhecida"))
        repository.sync(credentials)
        repository.addFavorite(1)
        repository.setCategoryHidden("a", true)

        assertEquals(listOf(2L, 3L), search("canal"))
        assertEquals(listOf(1L), repository.favoriteChannels.first().map { it.streamId })
    }

    @Test
    fun removerFavoritoTiraDaLista() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(channel(1, "a"))
        repository.sync(credentials)
        repository.addFavorite(1)

        repository.removeFavorite(1)

        assertTrue(repository.favoriteChannels.first().isEmpty())
    }

    @Test
    fun zappingNaCategoriaDaAVoltaNasPontasEIgnoraOutras() = runBlocking {
        source.categories = listOf(category("a"), category("b"))
        source.channels = listOf(channel(1, "a"), channel(2, "b"), channel(3, "a"), channel(4, "a"))
        repository.sync(credentials)
        val context = ZapContext.Category("a")

        assertEquals(3L, zap(context, 1, forward = true))
        assertEquals(4L, zap(context, 3, forward = true))
        assertEquals(1L, zap(context, 4, forward = true))
        assertEquals(4L, zap(context, 1, forward = false))
        assertEquals(1L, zap(context, 3, forward = false))
    }

    @Test
    fun zappingNaPesquisaSoPassaPelosResultadosVisiveis() = runBlocking {
        source.categories = listOf(category("a"), category("b"))
        source.channels = listOf(
            LiveChannel(1, 1, "Sport Um", "a", null, null),
            LiveChannel(2, 2, "Outro", "a", null, null),
            LiveChannel(3, 3, "Sport Dois", "b", null, null),
            LiveChannel(4, 4, "Sport Três", "a", null, null),
        )
        repository.sync(credentials)
        repository.setCategoryHidden("b", true)
        val context = ZapContext.Search("sport")

        assertEquals(4L, zap(context, 1, forward = true))
        assertEquals(1L, zap(context, 4, forward = true))
        assertEquals(4L, zap(context, 1, forward = false))
    }

    @Test
    fun zappingNosFavoritosSegueAOrdemEmQueForamMarcados() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(channel(1, "a"), channel(2, "a"), channel(3, "a"))
        repository.sync(credentials)
        repository.addFavorite(3)
        now += 1
        repository.addFavorite(1)

        assertEquals(1L, zap(ZapContext.Favorites, 3, forward = true))
        assertEquals(3L, zap(ZapContext.Favorites, 1, forward = true))
        assertEquals(1L, zap(ZapContext.Favorites, 3, forward = false))
    }

    @Test
    fun zappingComUmSoCanalFicaNoMesmo() = runBlocking {
        source.categories = listOf(category("a"))
        source.channels = listOf(channel(1, "a"))
        repository.sync(credentials)

        assertEquals(1L, zap(ZapContext.Category("a"), 1, forward = true))
        assertEquals(1L, zap(ZapContext.Category("a"), 1, forward = false))
    }

    private suspend fun zap(context: ZapContext, fromId: Long, forward: Boolean): Long? {
        val current = database.dao().channelsInCategory("a").all().firstOrNull { it.streamId == fromId }
            ?: database.dao().channelsInCategory("b").all().first { it.streamId == fromId }
        return repository.neighbor(context, current, forward)?.streamId
    }

    private suspend fun search(query: String): List<Long> =
        database.dao().searchChannels(TextNormalizer.likePattern(query)).all().map { it.streamId }

    private suspend fun PagingSource<Int, ChannelRow>.all(): List<ChannelRow> {
        val result = load(PagingSource.LoadParams.Refresh(null, 100, false))
        return (result as PagingSource.LoadResult.Page).data
    }

    private fun storedChannelIds(): List<Long> {
        val ids = mutableListOf<Long>()
        database.query("SELECT streamId FROM channels ORDER BY position", null).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    private fun category(id: String, name: String = "Categoria $id") = XtreamCategory(id, name)

    private fun channel(id: Long, categoryId: String) =
        LiveChannel(id, id.toInt(), "Canal $id", categoryId, null, null)

    private class FakeSource : XtreamSource {
        var categories = emptyList<XtreamCategory>()
        var channels = emptyList<LiveChannel>()
        var failAfterBatch = false

        override suspend fun login(credentials: XtreamCredentials) =
            AccountInfo("Active", 1_893_456_000L, 1, 0, listOf("m3u8", "ts"))

        override suspend fun categories(credentials: XtreamCredentials) = categories

        override suspend fun forEachChannelBatch(
            credentials: XtreamCredentials,
            batchSize: Int,
            onBatch: suspend (List<LiveChannel>) -> Unit,
        ): Int {
            channels.chunked(batchSize.coerceAtMost(1)).forEach { batch ->
                onBatch(batch)
                if (failAfterBatch) throw XtreamException.Network()
            }
            return channels.size
        }
    }
}
