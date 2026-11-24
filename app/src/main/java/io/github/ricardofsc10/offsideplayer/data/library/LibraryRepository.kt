package io.github.ricardofsc10.offsideplayer.data.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.room.withTransaction
import io.github.ricardofsc10.offsideplayer.data.xtream.LiveChannel
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamException
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Só números, para o logcat. */
data class LiveSyncReport(
    val channels: Int,
    val withEpgId: Int,
    val withArchive: Int,
    val maxArchiveDays: Int,
    val elapsedMs: Long,
) {
    fun logLine(): String =
        "liveSync channels=$channels epgIds=$withEpgId archive=$withArchive archiveDays=$maxArchiveDays ms=$elapsedMs"
}

/** A lista de onde um canal foi aberto. O zapping percorre sempre esta lista. */
sealed interface ZapContext {
    data object Favorites : ZapContext
    data class Category(val id: String) : ZapContext
    data class Search(val query: String) : ZapContext
}

class LibraryRepository(
    private val database: LibraryDatabase,
    private val source: XtreamSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.dao()

    val categories: Flow<List<CategorySummary>> = dao.observeCategories()
    val favoriteChannels: Flow<List<ChannelRow>> = dao.observeFavoriteChannels()
    val syncInfo: Flow<SyncInfoEntity?> = dao.observeSyncInfo()

    fun categoryChannels(categoryId: String, initialIndex: Int? = null): Flow<PagingData<ChannelRow>> =
        pager(initialIndex) { dao.channelsInCategory(categoryId) }

    fun searchChannels(query: String): Flow<PagingData<ChannelRow>> {
        val pattern = TextNormalizer.likePattern(query)
        return pager(null) { dao.searchChannels(pattern) }
    }

    private fun pager(
        initialIndex: Int?,
        source: () -> PagingSource<Int, ChannelRow>,
    ): Flow<PagingData<ChannelRow>> =
        Pager(
            config = PagingConfig(pageSize = 50, prefetchDistance = 50, enablePlaceholders = false),
            initialKey = initialIndex,
            pagingSourceFactory = source,
        ).flow

    /** Canal seguinte ou anterior na lista de onde o canal foi aberto, a dar a volta nas pontas. */
    suspend fun neighbor(context: ZapContext, current: ChannelRow, forward: Boolean): ChannelRow? = when (context) {
        ZapContext.Favorites -> {
            val favorites = dao.observeFavoriteChannels().first()
            val index = favorites.indexOfFirst { it.streamId == current.streamId }
            when {
                favorites.isEmpty() -> null
                forward -> favorites[(index + 1).mod(favorites.size)]
                index <= 0 -> favorites.last()
                else -> favorites[index - 1]
            }
        }
        is ZapContext.Category -> if (forward) {
            dao.nextInCategory(context.id, current.position) ?: dao.nextInCategory(context.id, -1)
        } else {
            dao.previousInCategory(context.id, current.position)
                ?: dao.previousInCategory(context.id, Int.MAX_VALUE)
        }
        is ZapContext.Search -> {
            val pattern = TextNormalizer.likePattern(context.query)
            if (forward) {
                dao.nextInSearch(pattern, current.position) ?: dao.nextInSearch(pattern, -1)
            } else {
                dao.previousInSearch(pattern, current.position)
                    ?: dao.previousInSearch(pattern, Int.MAX_VALUE)
            }
        }
    }

    suspend fun indexInCategory(categoryId: String, position: Int): Int = dao.indexInCategory(categoryId, position)

    suspend fun allowedFormats(): List<String> =
        dao.allowedFormats().orEmpty().split(",").filter { it.isNotBlank() }

    suspend fun isSyncDue(): Boolean = SyncPolicy.isDue(dao.lastSyncAt(), clock())

    /**
     * Atualiza a biblioteca a partir do servidor. Só apaga o que desapareceu depois de ler tudo
     * com sucesso, por isso uma falha a meio não perde canais nem favoritos.
     */
    suspend fun sync(credentials: XtreamCredentials, onProgress: (channels: Int) -> Unit = {}): LiveSyncReport {
        val stamp = clock()
        val account = source.login(credentials)
        val categories = source.categories(credentials)

        var position = 0
        var withEpgId = 0
        var withArchive = 0
        var maxArchiveDays = 0
        val total = source.forEachChannelBatch(credentials) { batch ->
            batch.forEach { channel ->
                if (channel.epgChannelId != null) withEpgId++
                channel.archiveDays?.let {
                    withArchive++
                    maxArchiveDays = maxOf(maxArchiveDays, it)
                }
            }
            dao.upsertChannels(batch.map { it.toEntity(position++, stamp) })
            onProgress(position)
        }
        // Uma resposta vazia é mais provável num problema do servidor do que numa conta sem canais.
        if (total == 0) throw XtreamException.EmptyLibrary()

        database.withTransaction {
            categories.forEachIndexed { index, category ->
                dao.upsertCategory(category.id, category.name, index, stamp)
            }
            dao.deleteCategoriesNotIn(stamp)
            dao.deleteChannelsNotIn(stamp)
            dao.deleteOrphanFavorites()
            dao.saveSyncInfo(
                SyncInfoEntity(
                    lastSyncAt = stamp,
                    accountStatus = account.status,
                    expiresAtEpochSeconds = account.expiresAtEpochSeconds,
                    maxConnections = account.maxConnections,
                    allowedFormats = account.allowedFormats.joinToString(","),
                ),
            )
        }
        return LiveSyncReport(total, withEpgId, withArchive, maxArchiveDays, clock() - stamp)
    }

    suspend fun setCategoryHidden(id: String, hidden: Boolean) = dao.setCategoryHidden(id, hidden)

    suspend fun addFavorite(streamId: Long) = dao.addFavorite(FavoriteEntity(streamId, clock()))

    suspend fun removeFavorite(streamId: Long) = dao.removeFavorite(streamId)

    suspend fun clear() = withContext(Dispatchers.IO) { database.clearAllTables() }

    private fun LiveChannel.toEntity(position: Int, stamp: Long) = ChannelEntity(
        streamId = streamId,
        number = number,
        name = name,
        nameNormalized = TextNormalizer.normalize(name),
        categoryId = categoryId,
        iconUrl = iconUrl,
        epgChannelId = epgChannelId,
        position = position,
        syncStamp = stamp,
        archiveDays = archiveDays,
    )
}
