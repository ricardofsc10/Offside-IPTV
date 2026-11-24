package io.github.ricardofsc10.offsideplayer.data.library

import android.os.SystemClock
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import io.github.ricardofsc10.offsideplayer.data.xtream.Episode
import io.github.ricardofsc10.offsideplayer.data.xtream.Movie
import io.github.ricardofsc10.offsideplayer.data.xtream.MovieInfo
import io.github.ricardofsc10.offsideplayer.data.xtream.Series
import io.github.ricardofsc10.offsideplayer.data.xtream.VodSource
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Números de uma sincronização, para medir. Não tem títulos. */
data class VodSyncReport(
    val movies: Int,
    val series: Int,
    val movieExtensions: String,
    val elapsedMs: Long,
    /** Parte de [elapsedMs] passada a escrever na base de dados. O resto é rede e leitura. */
    val databaseMs: Long = 0,
    /** Títulos novos ou alterados, os únicos escritos. Os iguais ficam como estavam. */
    val written: Int = 0,
    val deleted: Int = 0,
) {
    fun logLine(): String =
        "vodSync movies=$movies series=$series movieExt=$movieExtensions elapsedMs=$elapsedMs " +
            "dbMs=$databaseMs written=$written deleted=$deleted"
}

/** Um cartão da grelha, igual para filmes e séries. */
data class VodItem(
    val kind: VodKind,
    val id: Long,
    val title: String,
    val artworkUrl: String?,
    /** Fração já vista, de 0 a 1, ou `null` se nunca foi aberto. */
    val progress: Float?,
    val favorite: Boolean,
)

/** O que a coluna da esquerda escolhe para a grelha. */
sealed interface VodShelf {
    data object Search : VodShelf
    data object Continue : VodShelf
    data object Favorites : VodShelf
    data class Category(val id: String, val name: String) : VodShelf
}

/**
 * Filmes e séries. Sincroniza à parte dos canais: uma falha aqui não toca na lista de canais.
 */
/** Quantos títulos cabem na fila "Continuar a ver" do início. */
const val CONTINUE_ROW_LIMIT = 12

/** Quantos IDs vão em cada consulta, abaixo do limite de 999 parâmetros do SQLite. */
private const val QUERY_CHUNK = 500
private const val DELETE_CHUNK = QUERY_CHUNK

/** Títulos escritos por transação na sincronização. */
private const val WRITE_BATCH = 5_000

class VodRepository(
    private val database: LibraryDatabase,
    private val source: VodSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private val dao = database.vodDao()

    val movieCategories: Flow<List<VodCategorySummary>> = dao.observeMovieCategories()
    val seriesCategories: Flow<List<VodCategorySummary>> = dao.observeSeriesCategories()
    val syncInfo: Flow<VodSyncInfoEntity?> = dao.observeSyncInfo()

    fun moviesInCategory(categoryId: String): Flow<PagingData<MovieRow>> = pager { dao.moviesInCategory(categoryId) }

    fun seriesInCategory(categoryId: String): Flow<PagingData<SeriesRow>> = pager { dao.seriesInCategory(categoryId) }

    fun searchMovies(query: String): Flow<PagingData<MovieRow>> {
        val pattern = TextNormalizer.likePattern(query)
        return pager { dao.searchMovies(pattern) }
    }

    fun searchSeries(query: String): Flow<PagingData<SeriesRow>> {
        val pattern = TextNormalizer.likePattern(query)
        return pager { dao.searchSeries(pattern) }
    }

    private fun <T : Any> pager(source: () -> PagingSource<Int, T>): Flow<PagingData<T>> =
        Pager(
            config = PagingConfig(pageSize = 60, prefetchDistance = 60, enablePlaceholders = false),
            pagingSourceFactory = source,
        ).flow

    /** A grelha de uma prateleira. Na pesquisa, um texto vazio dá uma grelha vazia. */
    fun items(kind: VodKind, shelf: VodShelf, query: String = ""): Flow<PagingData<VodItem>> = when (kind) {
        VodKind.MOVIE -> when (shelf) {
            VodShelf.Search -> if (query.isBlank()) emptyItems() else searchMovies(query)
            VodShelf.Continue -> pager { dao.continueMovies() }
            VodShelf.Favorites -> pager { dao.favoriteMovies() }
            is VodShelf.Category -> moviesInCategory(shelf.id)
        }.map { page -> page.map { it.toItem() } }
        VodKind.SERIES -> when (shelf) {
            VodShelf.Search -> if (query.isBlank()) emptyItems() else searchSeries(query)
            VodShelf.Continue -> pager { dao.continueSeries() }
            VodShelf.Favorites -> pager { dao.favoriteSeries() }
            is VodShelf.Category -> seriesInCategory(shelf.id)
        }.map { page -> page.map { it.toItem() } }
    }

    private fun <T : Any> emptyItems(): Flow<PagingData<T>> = flowOf(PagingData.empty())

    suspend fun setFavorite(kind: VodKind, id: Long, favorite: Boolean) {
        if (favorite) dao.addFavorite(VodFavoriteEntity(kind, id, clock())) else dao.removeFavorite(kind, id)
    }

    /** Filmes e séries a meio, os mais recentes primeiro, para a fila do início. */
    fun continueWatching(limit: Int = CONTINUE_ROW_LIMIT): Flow<List<VodItem>> =
        combine(dao.observeContinueMovies(limit), dao.observeContinueSeries(limit)) { movies, series ->
            (movies.map { VodKind.MOVIE to it } + series.map { VodKind.SERIES to it })
                .sortedByDescending { (_, row) -> row.updatedAt }
                .take(limit)
                .map { (kind, row) ->
                    VodItem(
                        kind = kind,
                        id = row.id,
                        title = row.title,
                        artworkUrl = row.artworkUrl,
                        progress = progressFraction(row.positionMs, row.durationMs),
                        favorite = row.favorite,
                    )
                }
        }

    /** O episódio por onde retomar uma série, com a posição. `null` se os episódios não estiverem guardados. */
    suspend fun continueEpisode(seriesId: Long): EpisodeRow? {
        val id = continueEpisodeId(seriesId) ?: return null
        return dao.observeEpisodes(seriesId).first().firstOrNull { it.id == id }
    }

    /** Tira um filme ou série de "Continuar a ver" até voltar a ser visto. */
    suspend fun removeFromContinue(kind: VodKind, id: Long) =
        dao.dismissContinue(ContinueDismissedEntity(kind, id, clock()))

    fun isFavorite(kind: VodKind, id: Long): Flow<Boolean> = dao.observeFavorite(kind, id)

    /** O episódio por onde continuar: o último visto, ou o seguinte se esse já acabou. */
    suspend fun continueEpisodeId(seriesId: Long): Long? {
        val last = dao.lastWatchedEpisode(seriesId) ?: return null
        val progress = dao.progress(ProgressKind.EPISODE, last)
        return if (progress?.finished == true) dao.nextEpisode(seriesId, last)?.id ?: last else last
    }

    suspend fun setCategoryHidden(kind: VodKind, id: String, hidden: Boolean) = dao.setCategoryHidden(kind, id, hidden)

    suspend fun isSyncDue(): Boolean = SyncPolicy.isDue(dao.lastSyncAt(), clock())

    /**
     * Atualiza filmes e séries. Como nos canais, só apaga o que desapareceu depois de ler tudo,
     * por isso uma falha a meio não perde nada. Uma conta sem VOD fica simplesmente vazia.
     */
    suspend fun sync(credentials: XtreamCredentials, onProgress: (items: Int) -> Unit = {}): VodSyncReport {
        val startedAt = elapsed()
        val stamp = clock()
        val movieCategories = source.movieCategories(credentials)
        val seriesCategories = source.seriesCategories(credentials)

        val extensions = ExtensionCounter()
        var databaseMs = 0L
        suspend fun timed(block: suspend () -> Unit) {
            val start = elapsed()
            block()
            databaseMs += elapsed() - start
        }
        // Só se escreve o que é novo ou mudou. Numa lista de 200 000 títulos quase todos ficam
        // iguais de uma sincronização para a outra, e reescrevê-los levava minutos.
        var written = 0
        val seenMovies = IdCollector()
        var moviePosition = 0
        source.forEachMovieBatch(credentials, WRITE_BATCH) { batch ->
            batch.forEach { extensions.add(it.containerExtension) }
            val entities = batch.map { it.toEntity(moviePosition++, stamp) }
            entities.forEach { seenMovies.add(it.streamId) }
            // Uma transação por lote grande: fechar cada transação custa, e eram centenas.
            timed {
                database.withTransaction {
                    entities.chunked(QUERY_CHUNK).forEach { chunk ->
                        val existing = dao.moviesById(chunk.map { it.streamId }).associateBy { it.streamId }
                        val changed = chunk.filter { it.copy(syncStamp = 0) != existing[it.streamId]?.copy(syncStamp = 0) }
                        if (changed.isNotEmpty()) dao.upsertMovies(changed)
                        written += changed.size
                    }
                }
            }
            onProgress(moviePosition)
        }
        val seenSeries = IdCollector()
        var seriesPosition = 0
        source.forEachSeriesBatch(credentials, WRITE_BATCH) { batch ->
            val entities = batch.map { it.toEntity(seriesPosition++, stamp) }
            entities.forEach { seenSeries.add(it.seriesId) }
            timed {
                database.withTransaction {
                    entities.chunked(QUERY_CHUNK).forEach { chunk ->
                        val existing = dao.seriesById(chunk.map { it.seriesId }).associateBy { it.seriesId }
                        val changed = chunk.filter { it.copy(syncStamp = 0) != existing[it.seriesId]?.copy(syncStamp = 0) }
                        if (changed.isNotEmpty()) dao.upsertSeries(changed)
                        written += changed.size
                    }
                }
            }
            onProgress(moviePosition + seriesPosition)
        }

        val cleanupStart = elapsed()
        var deleted = 0
        database.withTransaction {
            movieCategories.forEachIndexed { index, category ->
                dao.upsertCategory(VodKind.MOVIE, category.id, category.name, index, stamp)
            }
            seriesCategories.forEachIndexed { index, category ->
                dao.upsertCategory(VodKind.SERIES, category.id, category.name, index, stamp)
            }
            dao.deleteCategoriesNotIn(VodKind.MOVIE, stamp)
            dao.deleteCategoriesNotIn(VodKind.SERIES, stamp)
            seenMovies.missingFrom(dao.movieIds()).chunked(DELETE_CHUNK).forEach { dao.deleteMovies(it); deleted += it.size }
            seenSeries.missingFrom(dao.seriesIds()).chunked(DELETE_CHUNK).forEach { dao.deleteSeries(it); deleted += it.size }
            dao.deleteOrphanEpisodes()
            dao.deleteOrphanEpisodeFetches()
            dao.deleteOrphanProgress()
            dao.deleteOrphanFavorites()
            dao.saveSyncInfo(VodSyncInfoEntity(lastSyncAt = stamp, movieCount = moviePosition, seriesCount = seriesPosition))
        }
        databaseMs += elapsed() - cleanupStart
        return VodSyncReport(
            moviePosition, seriesPosition, extensions.toString(), elapsed() - startedAt, databaseMs, written, deleted,
        )
    }

    fun episodes(seriesId: Long): Flow<List<EpisodeRow>> = dao.observeEpisodes(seriesId)

    /** Pede os episódios ao servidor se ainda não os houver ou se forem antigos. */
    suspend fun refreshEpisodesIfStale(credentials: XtreamCredentials, seriesId: Long) {
        if (!EpisodesPolicy.isStale(dao.episodesFetchedAt(seriesId), clock())) return
        val episodes = source.seriesEpisodes(credentials, seriesId)
        database.withTransaction {
            dao.deleteEpisodes(seriesId)
            dao.upsertEpisodes(episodes.mapIndexed { index, episode -> episode.toEntity(seriesId, index) })
            dao.saveEpisodesFetch(EpisodesFetchEntity(seriesId, clock()))
        }
    }

    suspend fun nextEpisode(seriesId: Long, episodeId: Long): EpisodeRow? = dao.nextEpisode(seriesId, episodeId)

    suspend fun movieInfo(credentials: XtreamCredentials, streamId: Long): MovieInfo = source.movieInfo(credentials, streamId)

    suspend fun movie(streamId: Long): MovieEntity? = dao.movie(streamId)

    suspend fun series(seriesId: Long): SeriesEntity? = dao.series(seriesId)

    suspend fun progress(kind: ProgressKind, id: Long): PlaybackProgressEntity? = dao.progress(kind, id)

    suspend fun saveProgress(kind: ProgressKind, id: Long, seriesId: Long?, positionMs: Long, durationMs: Long) {
        dao.saveProgress(
            PlaybackProgressEntity(
                kind = kind,
                id = id,
                seriesId = seriesId,
                positionMs = positionMs,
                durationMs = durationMs,
                finished = ProgressPolicy.isFinished(positionMs, durationMs),
                updatedAt = clock(),
            ),
        )
    }

    fun unfinished(limit: Int = 20): Flow<List<PlaybackProgressEntity>> = dao.observeUnfinished(limit)

    private fun MovieRow.toItem() = VodItem(
        kind = VodKind.MOVIE,
        id = streamId,
        title = name,
        artworkUrl = posterUrl,
        progress = progressFraction(positionMs, durationMs),
        favorite = favorite,
    )

    private fun SeriesRow.toItem() = VodItem(
        kind = VodKind.SERIES,
        id = seriesId,
        title = name,
        artworkUrl = coverUrl,
        progress = null,
        favorite = favorite,
    )

    private fun Movie.toEntity(position: Int, stamp: Long) = MovieEntity(
        streamId = streamId,
        name = name,
        nameNormalized = TextNormalizer.normalize(name),
        categoryId = categoryId,
        posterUrl = posterUrl,
        containerExtension = containerExtension,
        rating = rating,
        addedAtEpochSeconds = addedAtEpochSeconds,
        position = position,
        syncStamp = stamp,
    )

    private fun Series.toEntity(position: Int, stamp: Long) = SeriesEntity(
        seriesId = seriesId,
        name = name,
        nameNormalized = TextNormalizer.normalize(name),
        categoryId = categoryId,
        coverUrl = coverUrl,
        plot = plot,
        releaseDate = releaseDate,
        rating = rating,
        lastModifiedEpochSeconds = lastModifiedEpochSeconds,
        position = position,
        syncStamp = stamp,
    )

    private fun Episode.toEntity(seriesId: Long, position: Int) = EpisodeEntity(
        id = id,
        seriesId = seriesId,
        season = season,
        number = number,
        title = title,
        containerExtension = containerExtension,
        durationSeconds = durationSeconds,
        plot = plot,
        imageUrl = imageUrl,
        position = position,
    )
}
