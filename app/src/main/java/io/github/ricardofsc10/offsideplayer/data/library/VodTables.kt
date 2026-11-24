package io.github.ricardofsc10.offsideplayer.data.library

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Os IDs de categoria podem repetir-se entre filmes, séries e canais, por isso o tipo faz parte da chave. */
enum class VodKind { MOVIE, SERIES }

@Entity(tableName = "vod_categories", primaryKeys = ["kind", "id"])
data class VodCategoryEntity(
    val kind: VodKind,
    val id: String,
    val name: String,
    val position: Int,
    val hidden: Boolean,
    val syncStamp: Long,
)

// Sem índice no nome: a pesquisa usa LIKE '%texto%', que não o aproveita, e o índice só
// atrasava a escrita de dezenas de milhares de títulos em cada sincronização.
@Entity(
    tableName = "movies",
    indices = [Index("categoryId", "position")],
)
data class MovieEntity(
    @PrimaryKey val streamId: Long,
    val name: String,
    val nameNormalized: String,
    val categoryId: String?,
    val posterUrl: String?,
    val containerExtension: String,
    val rating: String?,
    val addedAtEpochSeconds: Long?,
    val position: Int,
    val syncStamp: Long,
)

@Entity(
    tableName = "series",
    indices = [Index("categoryId", "position")],
)
data class SeriesEntity(
    @PrimaryKey val seriesId: Long,
    val name: String,
    val nameNormalized: String,
    val categoryId: String?,
    val coverUrl: String?,
    val plot: String?,
    val releaseDate: String?,
    val rating: String?,
    val lastModifiedEpochSeconds: Long?,
    val position: Int,
    val syncStamp: Long,
)

@Entity(tableName = "episodes", indices = [Index("seriesId", "season", "number")])
data class EpisodeEntity(
    @PrimaryKey val id: Long,
    val seriesId: Long,
    val season: Int,
    val number: Int?,
    val title: String?,
    val containerExtension: String,
    val durationSeconds: Int?,
    val plot: String?,
    val imageUrl: String?,
    /** Ordem dentro da série, pela ordem do servidor depois de ordenada por temporada e número. */
    val position: Int,
)

/** Quando os episódios de cada série foram pedidos pela última vez. */
@Entity(tableName = "episodes_fetch")
data class EpisodesFetchEntity(
    @PrimaryKey val seriesId: Long,
    val fetchedAt: Long,
)

enum class ProgressKind { MOVIE, EPISODE }

/**
 * Onde ficou cada filme ou episódio. Ligado ao ID do fornecedor, como os favoritos, para sobreviver
 * às sincronizações. Num episódio, [seriesId] serve para "Continuar a ver" mostrar a série.
 */
@Entity(tableName = "playback_progress", primaryKeys = ["kind", "id"], indices = [Index("updatedAt")])
data class PlaybackProgressEntity(
    val kind: ProgressKind,
    val id: Long,
    val seriesId: Long?,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
)

/**
 * Filmes e séries tirados à mão de "Continuar a ver". Voltam à lista se forem vistos outra vez
 * depois de [dismissedAt]. A posição e os episódios vistos ficam guardados.
 */
@Entity(tableName = "continue_dismissed", primaryKeys = ["kind", "id"])
data class ContinueDismissedEntity(
    val kind: VodKind,
    val id: Long,
    val dismissedAt: Long,
)

/** Favoritos de filmes e séries, ligados ao ID do fornecedor como os dos canais. */
@Entity(tableName = "vod_favorites", primaryKeys = ["kind", "id"])
data class VodFavoriteEntity(
    val kind: VodKind,
    val id: Long,
    val addedAt: Long,
)

/** Estado da última sincronização de filmes e séries. Tem uma única linha. */
@Entity(tableName = "vod_sync_info")
data class VodSyncInfoEntity(
    @PrimaryKey val id: Int = 1,
    val lastSyncAt: Long,
    val movieCount: Int,
    val seriesCount: Int,
)

data class VodCategorySummary(
    val id: String,
    val name: String,
    val hidden: Boolean,
    val itemCount: Int,
)

data class MovieRow(
    val streamId: Long,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val containerExtension: String,
    val positionMs: Long?,
    val durationMs: Long?,
    val favorite: Boolean,
)

data class SeriesRow(
    val seriesId: Long,
    val name: String,
    val categoryId: String?,
    val coverUrl: String?,
    val plot: String?,
    val releaseDate: String?,
    val favorite: Boolean,
)

/** Uma linha de "Continuar a ver" no início: um filme ou uma série, com a hora em que foi visto. */
data class ContinueRow(
    val id: Long,
    val title: String,
    val artworkUrl: String?,
    val positionMs: Long?,
    val durationMs: Long?,
    val favorite: Boolean,
    val updatedAt: Long,
)

data class EpisodeRow(
    val id: Long,
    val seriesId: Long,
    val season: Int,
    val number: Int?,
    val title: String?,
    val containerExtension: String,
    val durationSeconds: Int?,
    val plot: String?,
    val imageUrl: String?,
    val positionMs: Long?,
    val durationMs: Long?,
    val finished: Boolean?,
)

@Dao
interface VodDao {
    // Não toca em `hidden`, para a escolha do utilizador sobreviver às sincronizações.
    @Query(
        """
        INSERT INTO vod_categories (kind, id, name, position, hidden, syncStamp)
        VALUES (:kind, :id, :name, :position, 0, :syncStamp)
        ON CONFLICT(kind, id) DO UPDATE SET
            name = excluded.name, position = excluded.position, syncStamp = excluded.syncStamp
        """,
    )
    suspend fun upsertCategory(kind: VodKind, id: String, name: String, position: Int, syncStamp: Long)

    @Upsert
    suspend fun upsertMovies(movies: List<MovieEntity>)

    @Upsert
    suspend fun upsertSeries(series: List<SeriesEntity>)

    @Query("DELETE FROM vod_categories WHERE kind = :kind AND syncStamp != :syncStamp")
    suspend fun deleteCategoriesNotIn(kind: VodKind, syncStamp: Long)

    @Query("SELECT * FROM movies WHERE streamId IN (:ids)")
    suspend fun moviesById(ids: List<Long>): List<MovieEntity>

    @Query("SELECT * FROM series WHERE seriesId IN (:ids)")
    suspend fun seriesById(ids: List<Long>): List<SeriesEntity>

    @Query("SELECT streamId FROM movies")
    suspend fun movieIds(): LongArray

    @Query("SELECT seriesId FROM series")
    suspend fun seriesIds(): LongArray

    @Query("DELETE FROM movies WHERE streamId IN (:ids)")
    suspend fun deleteMovies(ids: List<Long>)

    @Query("DELETE FROM series WHERE seriesId IN (:ids)")
    suspend fun deleteSeries(ids: List<Long>)

    @Query("DELETE FROM episodes WHERE seriesId NOT IN (SELECT seriesId FROM series)")
    suspend fun deleteOrphanEpisodes()

    @Query("DELETE FROM episodes_fetch WHERE seriesId NOT IN (SELECT seriesId FROM series)")
    suspend fun deleteOrphanEpisodeFetches()

    @Query(
        """
        DELETE FROM playback_progress
        WHERE (kind = 'MOVIE' AND id NOT IN (SELECT streamId FROM movies))
            OR (kind = 'EPISODE' AND seriesId NOT IN (SELECT seriesId FROM series))
        """,
    )
    suspend fun deleteOrphanProgress()

    @Upsert
    suspend fun saveSyncInfo(info: VodSyncInfoEntity)

    @Query("SELECT lastSyncAt FROM vod_sync_info WHERE id = 1")
    suspend fun lastSyncAt(): Long?

    @Query("SELECT * FROM vod_sync_info WHERE id = 1")
    fun observeSyncInfo(): Flow<VodSyncInfoEntity?>

    @Query(
        """
        SELECT c.id, c.name, c.hidden, COUNT(m.streamId) AS itemCount
        FROM vod_categories c LEFT JOIN movies m ON m.categoryId = c.id
        WHERE c.kind = 'MOVIE'
        GROUP BY c.id ORDER BY c.position
        """,
    )
    fun observeMovieCategories(): Flow<List<VodCategorySummary>>

    @Query(
        """
        SELECT c.id, c.name, c.hidden, COUNT(s.seriesId) AS itemCount
        FROM vod_categories c LEFT JOIN series s ON s.categoryId = c.id
        WHERE c.kind = 'SERIES'
        GROUP BY c.id ORDER BY c.position
        """,
    )
    fun observeSeriesCategories(): Flow<List<VodCategorySummary>>

    @Query("UPDATE vod_categories SET hidden = :hidden WHERE kind = :kind AND id = :id")
    suspend fun setCategoryHidden(kind: VodKind, id: String, hidden: Boolean)

    @Query(
        """
        SELECT m.streamId, m.name, m.categoryId, m.posterUrl, m.containerExtension, p.positionMs, p.durationMs,
            (f.id IS NOT NULL) AS favorite
        FROM movies m
        LEFT JOIN playback_progress p ON p.kind = 'MOVIE' AND p.id = m.streamId
        LEFT JOIN vod_favorites f ON f.kind = 'MOVIE' AND f.id = m.streamId
        WHERE m.categoryId = :categoryId
        ORDER BY m.position
        """,
    )
    fun moviesInCategory(categoryId: String): PagingSource<Int, MovieRow>

    @Query(
        """
        SELECT s.seriesId, s.name, s.categoryId, s.coverUrl, s.plot, s.releaseDate, (f.id IS NOT NULL) AS favorite
        FROM series s
        LEFT JOIN vod_favorites f ON f.kind = 'SERIES' AND f.id = s.seriesId
        WHERE s.categoryId = :categoryId
        ORDER BY s.position
        """,
    )
    fun seriesInCategory(categoryId: String): PagingSource<Int, SeriesRow>

    // Como nos canais: um item de uma categoria escondida não aparece na pesquisa.
    @Query(
        """
        SELECT m.streamId, m.name, m.categoryId, m.posterUrl, m.containerExtension, p.positionMs, p.durationMs,
            (f.id IS NOT NULL) AS favorite
        FROM movies m
        LEFT JOIN playback_progress p ON p.kind = 'MOVIE' AND p.id = m.streamId
        LEFT JOIN vod_favorites f ON f.kind = 'MOVIE' AND f.id = m.streamId
        LEFT JOIN vod_categories c ON c.kind = 'MOVIE' AND c.id = m.categoryId
        WHERE m.nameNormalized LIKE :pattern ESCAPE '\' AND (c.hidden IS NULL OR c.hidden = 0)
        ORDER BY m.position
        """,
    )
    fun searchMovies(pattern: String): PagingSource<Int, MovieRow>

    @Query(
        """
        SELECT s.seriesId, s.name, s.categoryId, s.coverUrl, s.plot, s.releaseDate, (f.id IS NOT NULL) AS favorite
        FROM series s
        LEFT JOIN vod_favorites f ON f.kind = 'SERIES' AND f.id = s.seriesId
        LEFT JOIN vod_categories c ON c.kind = 'SERIES' AND c.id = s.categoryId
        WHERE s.nameNormalized LIKE :pattern ESCAPE '\' AND (c.hidden IS NULL OR c.hidden = 0)
        ORDER BY s.position
        """,
    )
    fun searchSeries(pattern: String): PagingSource<Int, SeriesRow>

    @Query(
        """
        SELECT m.streamId, m.name, m.categoryId, m.posterUrl, m.containerExtension, p.positionMs, p.durationMs,
            (f.id IS NOT NULL) AS favorite
        FROM movies m
        LEFT JOIN playback_progress p ON p.kind = 'MOVIE' AND p.id = m.streamId
        LEFT JOIN vod_favorites f ON f.kind = 'MOVIE' AND f.id = m.streamId
        JOIN vod_favorites ff ON ff.kind = 'MOVIE' AND ff.id = m.streamId
        ORDER BY ff.addedAt DESC
        """,
    )
    fun favoriteMovies(): PagingSource<Int, MovieRow>

    @Query(
        """
        SELECT s.seriesId, s.name, s.categoryId, s.coverUrl, s.plot, s.releaseDate, (f.id IS NOT NULL) AS favorite
        FROM series s
        LEFT JOIN vod_favorites f ON f.kind = 'SERIES' AND f.id = s.seriesId
        JOIN vod_favorites ff ON ff.kind = 'SERIES' AND ff.id = s.seriesId
        ORDER BY ff.addedAt DESC
        """,
    )
    fun favoriteSeries(): PagingSource<Int, SeriesRow>

    /** Filmes começados e por acabar, do mais recente para o mais antigo. */
    @Query(
        """
        SELECT m.streamId, m.name, m.categoryId, m.posterUrl, m.containerExtension, p.positionMs, p.durationMs,
            (f.id IS NOT NULL) AS favorite
        FROM movies m
        LEFT JOIN playback_progress p ON p.kind = 'MOVIE' AND p.id = m.streamId
        LEFT JOIN vod_favorites f ON f.kind = 'MOVIE' AND f.id = m.streamId
        LEFT JOIN continue_dismissed d ON d.kind = 'MOVIE' AND d.id = m.streamId
        WHERE p.finished = 0 AND (d.dismissedAt IS NULL OR d.dismissedAt < p.updatedAt)
        ORDER BY p.updatedAt DESC
        LIMIT 50
        """,
    )
    fun continueMovies(): PagingSource<Int, MovieRow>

    /**
     * Séries a meio: o último episódio visto está por acabar, ou já acabou mas há outro a seguir.
     * Uma série vista até ao último episódio sai da lista.
     */
    @Query(
        """
        SELECT s.seriesId, s.name, s.categoryId, s.coverUrl, s.plot, s.releaseDate, (f.id IS NOT NULL) AS favorite
        FROM series s
        LEFT JOIN vod_favorites f ON f.kind = 'SERIES' AND f.id = s.seriesId
        JOIN (
            SELECT seriesId, MAX(updatedAt) AS lastAt FROM playback_progress
            WHERE kind = 'EPISODE' GROUP BY seriesId
        ) lp ON lp.seriesId = s.seriesId
        JOIN playback_progress p ON p.kind = 'EPISODE' AND p.seriesId = s.seriesId AND p.updatedAt = lp.lastAt
        LEFT JOIN episodes e ON e.id = p.id
        LEFT JOIN continue_dismissed d ON d.kind = 'SERIES' AND d.id = s.seriesId
        WHERE (d.dismissedAt IS NULL OR d.dismissedAt < lp.lastAt)
            AND (p.finished = 0
                OR EXISTS (SELECT 1 FROM episodes e2 WHERE e2.seriesId = s.seriesId AND e2.position > e.position))
        GROUP BY s.seriesId
        ORDER BY lp.lastAt DESC
        LIMIT 50
        """,
    )
    fun continueSeries(): PagingSource<Int, SeriesRow>

    /** O episódio por onde continuar uma série: o último visto, ou o seguinte se esse acabou. */
    @Query(
        """
        SELECT p.id FROM playback_progress p
        WHERE p.kind = 'EPISODE' AND p.seriesId = :seriesId
        ORDER BY p.updatedAt DESC LIMIT 1
        """,
    )
    suspend fun lastWatchedEpisode(seriesId: Long): Long?

    /** Os mesmos filmes de [continueMovies], numa lista curta para o início. */
    @Query(
        """
        SELECT m.streamId AS id, m.name AS title, m.posterUrl AS artworkUrl, p.positionMs, p.durationMs,
            (f.id IS NOT NULL) AS favorite, p.updatedAt
        FROM movies m
        JOIN playback_progress p ON p.kind = 'MOVIE' AND p.id = m.streamId
        LEFT JOIN vod_favorites f ON f.kind = 'MOVIE' AND f.id = m.streamId
        LEFT JOIN continue_dismissed d ON d.kind = 'MOVIE' AND d.id = m.streamId
        WHERE p.finished = 0 AND (d.dismissedAt IS NULL OR d.dismissedAt < p.updatedAt)
        ORDER BY p.updatedAt DESC
        LIMIT :limit
        """,
    )
    fun observeContinueMovies(limit: Int): Flow<List<ContinueRow>>

    /**
     * As mesmas séries de [continueSeries], numa lista curta para o início. A barra de progresso
     * é a do último episódio visto, só se ainda estiver a meio.
     */
    @Query(
        """
        SELECT s.seriesId AS id, s.name AS title, s.coverUrl AS artworkUrl,
            CASE WHEN p.finished = 0 THEN p.positionMs END AS positionMs,
            CASE WHEN p.finished = 0 THEN p.durationMs END AS durationMs,
            (f.id IS NOT NULL) AS favorite, lp.lastAt AS updatedAt
        FROM series s
        LEFT JOIN vod_favorites f ON f.kind = 'SERIES' AND f.id = s.seriesId
        JOIN (
            SELECT seriesId, MAX(updatedAt) AS lastAt FROM playback_progress
            WHERE kind = 'EPISODE' GROUP BY seriesId
        ) lp ON lp.seriesId = s.seriesId
        JOIN playback_progress p ON p.kind = 'EPISODE' AND p.seriesId = s.seriesId AND p.updatedAt = lp.lastAt
        LEFT JOIN episodes e ON e.id = p.id
        LEFT JOIN continue_dismissed d ON d.kind = 'SERIES' AND d.id = s.seriesId
        WHERE (d.dismissedAt IS NULL OR d.dismissedAt < lp.lastAt)
            AND (p.finished = 0
                OR EXISTS (SELECT 1 FROM episodes e2 WHERE e2.seriesId = s.seriesId AND e2.position > e.position))
        GROUP BY s.seriesId
        ORDER BY lp.lastAt DESC
        LIMIT :limit
        """,
    )
    fun observeContinueSeries(limit: Int): Flow<List<ContinueRow>>

    @Upsert
    suspend fun dismissContinue(dismissed: ContinueDismissedEntity)

    @Upsert
    suspend fun addFavorite(favorite: VodFavoriteEntity)

    @Query("DELETE FROM vod_favorites WHERE kind = :kind AND id = :id")
    suspend fun removeFavorite(kind: VodKind, id: Long)

    @Query("SELECT COUNT(*) > 0 FROM vod_favorites WHERE kind = :kind AND id = :id")
    fun observeFavorite(kind: VodKind, id: Long): Flow<Boolean>

    @Query(
        """
        DELETE FROM vod_favorites
        WHERE (kind = 'MOVIE' AND id NOT IN (SELECT streamId FROM movies))
            OR (kind = 'SERIES' AND id NOT IN (SELECT seriesId FROM series))
        """,
    )
    suspend fun deleteOrphanFavorites()

    @Query("SELECT * FROM movies WHERE streamId = :streamId")
    suspend fun movie(streamId: Long): MovieEntity?

    @Query("SELECT * FROM series WHERE seriesId = :seriesId")
    suspend fun series(seriesId: Long): SeriesEntity?

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    suspend fun deleteEpisodes(seriesId: Long)

    @Upsert
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    @Upsert
    suspend fun saveEpisodesFetch(fetch: EpisodesFetchEntity)

    @Query("SELECT fetchedAt FROM episodes_fetch WHERE seriesId = :seriesId")
    suspend fun episodesFetchedAt(seriesId: Long): Long?

    @Query(
        """
        SELECT e.id, e.seriesId, e.season, e.number, e.title, e.containerExtension, e.durationSeconds,
            e.plot, e.imageUrl, p.positionMs, p.durationMs, p.finished
        FROM episodes e LEFT JOIN playback_progress p ON p.kind = 'EPISODE' AND p.id = e.id
        WHERE e.seriesId = :seriesId
        ORDER BY e.position
        """,
    )
    fun observeEpisodes(seriesId: Long): Flow<List<EpisodeRow>>

    @Query(
        """
        SELECT e.id, e.seriesId, e.season, e.number, e.title, e.containerExtension, e.durationSeconds,
            e.plot, e.imageUrl, p.positionMs, p.durationMs, p.finished
        FROM episodes e LEFT JOIN playback_progress p ON p.kind = 'EPISODE' AND p.id = e.id
        WHERE e.seriesId = :seriesId AND e.position > (SELECT position FROM episodes WHERE id = :episodeId)
        ORDER BY e.position LIMIT 1
        """,
    )
    suspend fun nextEpisode(seriesId: Long, episodeId: Long): EpisodeRow?

    @Upsert
    suspend fun saveProgress(progress: PlaybackProgressEntity)

    @Query("SELECT * FROM playback_progress WHERE kind = :kind AND id = :id")
    suspend fun progress(kind: ProgressKind, id: Long): PlaybackProgressEntity?

    /** Filmes e episódios começados e por acabar, do mais recente para o mais antigo. */
    @Query("SELECT * FROM playback_progress WHERE finished = 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeUnfinished(limit: Int): Flow<List<PlaybackProgressEntity>>
}
