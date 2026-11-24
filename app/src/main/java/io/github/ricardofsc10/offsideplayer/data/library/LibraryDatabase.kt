package io.github.ricardofsc10.offsideplayer.data.library

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import io.github.ricardofsc10.offsideplayer.data.epg.EpgChannelEntity
import io.github.ricardofsc10.offsideplayer.data.epg.EpgDao
import io.github.ricardofsc10.offsideplayer.data.epg.EpgInfoEntity
import io.github.ricardofsc10.offsideplayer.data.epg.EpgLinkEntity
import io.github.ricardofsc10.offsideplayer.data.epg.EpgProgramEntity
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
    val hidden: Boolean,
    val syncStamp: Long,
)

@Entity(
    tableName = "channels",
    indices = [Index("categoryId", "position"), Index("nameNormalized")],
)
data class ChannelEntity(
    @PrimaryKey val streamId: Long,
    val number: Int?,
    val name: String,
    val nameNormalized: String,
    val categoryId: String?,
    val iconUrl: String?,
    val epgChannelId: String?,
    val position: Int,
    val syncStamp: Long,
    /** Dias de arquivo do fornecedor, para ver para trás. */
    @ColumnInfo(defaultValue = "NULL") val archiveDays: Int? = null,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val streamId: Long,
    val addedAt: Long,
)

/** Estado da última sincronização bem-sucedida. Tem uma única linha e nunca guarda credenciais. */
@Entity(tableName = "sync_info")
data class SyncInfoEntity(
    @PrimaryKey val id: Int = 1,
    val lastSyncAt: Long,
    val accountStatus: String?,
    val expiresAtEpochSeconds: Long?,
    val maxConnections: Int?,
    val allowedFormats: String,
)

data class ChannelRow(
    val streamId: Long,
    val number: Int?,
    val name: String,
    val categoryId: String?,
    val iconUrl: String?,
    val position: Int,
    val favorite: Boolean,
)

data class CategorySummary(
    val id: String,
    val name: String,
    val hidden: Boolean,
    val channelCount: Int,
)

@Dao
interface LibraryDao {
    // Não toca em `hidden`, para a escolha do utilizador sobreviver às sincronizações.
    @Query(
        """
        INSERT INTO categories (id, name, position, hidden, syncStamp)
        VALUES (:id, :name, :position, 0, :syncStamp)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name, position = excluded.position, syncStamp = excluded.syncStamp
        """,
    )
    suspend fun upsertCategory(id: String, name: String, position: Int, syncStamp: Long)

    @Upsert
    suspend fun upsertChannels(channels: List<ChannelEntity>)

    @Upsert
    suspend fun saveSyncInfo(info: SyncInfoEntity)

    @Query("DELETE FROM categories WHERE syncStamp != :syncStamp")
    suspend fun deleteCategoriesNotIn(syncStamp: Long)

    @Query("DELETE FROM channels WHERE syncStamp != :syncStamp")
    suspend fun deleteChannelsNotIn(syncStamp: Long)

    @Query("DELETE FROM favorites WHERE streamId NOT IN (SELECT streamId FROM channels)")
    suspend fun deleteOrphanFavorites()

    @Query(
        """
        SELECT c.id, c.name, c.hidden, COUNT(ch.streamId) AS channelCount
        FROM categories c LEFT JOIN channels ch ON ch.categoryId = c.id
        GROUP BY c.id ORDER BY c.position
        """,
    )
    fun observeCategories(): Flow<List<CategorySummary>>

    @Query("UPDATE categories SET hidden = :hidden WHERE id = :id")
    suspend fun setCategoryHidden(id: String, hidden: Boolean)

    @Query(
        """
        SELECT ch.streamId, ch.number, ch.name, ch.categoryId, ch.iconUrl, ch.position, 1 AS favorite
        FROM channels ch JOIN favorites f ON f.streamId = ch.streamId
        ORDER BY f.addedAt, ch.position
        """,
    )
    fun observeFavoriteChannels(): Flow<List<ChannelRow>>

    @Query(
        """
        SELECT ch.streamId, ch.number, ch.name, ch.categoryId, ch.iconUrl, ch.position,
            (f.streamId IS NOT NULL) AS favorite
        FROM channels ch LEFT JOIN favorites f ON f.streamId = ch.streamId
        WHERE ch.categoryId = :categoryId
        ORDER BY ch.position
        """,
    )
    fun channelsInCategory(categoryId: String): PagingSource<Int, ChannelRow>

    // Um canal de uma categoria escondida não aparece. Um canal sem categoria conhecida aparece.
    @Query(
        """
        SELECT ch.streamId, ch.number, ch.name, ch.categoryId, ch.iconUrl, ch.position,
            (f.streamId IS NOT NULL) AS favorite
        FROM channels ch
        LEFT JOIN favorites f ON f.streamId = ch.streamId
        LEFT JOIN categories c ON c.id = ch.categoryId
        WHERE ch.nameNormalized LIKE :pattern ESCAPE '\' AND (c.hidden IS NULL OR c.hidden = 0)
        ORDER BY ch.position
        """,
    )
    fun searchChannels(pattern: String): PagingSource<Int, ChannelRow>

    // Zapping: o canal seguinte ou anterior na ordem da lista.
    @Query(
        """
        SELECT ch.streamId, ch.number, ch.name, ch.categoryId, ch.iconUrl, ch.position,
            (f.streamId IS NOT NULL) AS favorite
        FROM channels ch LEFT JOIN favorites f ON f.streamId = ch.streamId
        WHERE ch.categoryId = :categoryId AND ch.position > :position
        ORDER BY ch.position LIMIT 1
        """,
    )
    suspend fun nextInCategory(categoryId: String, position: Int): ChannelRow?

    @Query(
        """
        SELECT ch.streamId, ch.number, ch.name, ch.categoryId, ch.iconUrl, ch.position,
            (f.streamId IS NOT NULL) AS favorite
        FROM channels ch LEFT JOIN favorites f ON f.streamId = ch.streamId
        WHERE ch.categoryId = :categoryId AND ch.position < :position
        ORDER BY ch.position DESC LIMIT 1
        """,
    )
    suspend fun previousInCategory(categoryId: String, position: Int): ChannelRow?

    @Query(
        """
        SELECT ch.streamId, ch.number, ch.name, ch.categoryId, ch.iconUrl, ch.position,
            (f.streamId IS NOT NULL) AS favorite
        FROM channels ch
        LEFT JOIN favorites f ON f.streamId = ch.streamId
        LEFT JOIN categories c ON c.id = ch.categoryId
        WHERE ch.nameNormalized LIKE :pattern ESCAPE '\' AND (c.hidden IS NULL OR c.hidden = 0)
            AND ch.position > :position
        ORDER BY ch.position LIMIT 1
        """,
    )
    suspend fun nextInSearch(pattern: String, position: Int): ChannelRow?

    @Query(
        """
        SELECT ch.streamId, ch.number, ch.name, ch.categoryId, ch.iconUrl, ch.position,
            (f.streamId IS NOT NULL) AS favorite
        FROM channels ch
        LEFT JOIN favorites f ON f.streamId = ch.streamId
        LEFT JOIN categories c ON c.id = ch.categoryId
        WHERE ch.nameNormalized LIKE :pattern ESCAPE '\' AND (c.hidden IS NULL OR c.hidden = 0)
            AND ch.position < :position
        ORDER BY ch.position DESC LIMIT 1
        """,
    )
    suspend fun previousInSearch(pattern: String, position: Int): ChannelRow?

    @Query("SELECT COUNT(*) FROM channels WHERE categoryId = :categoryId AND position < :position")
    suspend fun indexInCategory(categoryId: String, position: Int): Int

    @Query("SELECT allowedFormats FROM sync_info WHERE id = 1")
    suspend fun allowedFormats(): String?

    @Upsert
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE streamId = :streamId")
    suspend fun removeFavorite(streamId: Long)

    @Query("SELECT * FROM sync_info WHERE id = 1")
    fun observeSyncInfo(): Flow<SyncInfoEntity?>

    @Query("SELECT lastSyncAt FROM sync_info WHERE id = 1")
    suspend fun lastSyncAt(): Long?
}

// As versões 2 e 3 só acrescentam tabelas (filmes e séries, e o que se tirou de "Continuar a ver"),
// por isso as migrações são automáticas e mantêm os canais, os favoritos e as categorias escondidas.
@Database(
    entities = [
        CategoryEntity::class, ChannelEntity::class, FavoriteEntity::class, SyncInfoEntity::class,
        VodCategoryEntity::class, MovieEntity::class, SeriesEntity::class, EpisodeEntity::class,
        EpisodesFetchEntity::class, PlaybackProgressEntity::class, VodFavoriteEntity::class, VodSyncInfoEntity::class,
        ContinueDismissedEntity::class,
        EpgChannelEntity::class, EpgProgramEntity::class, EpgLinkEntity::class, EpgInfoEntity::class,
    ],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        // Só tira os índices do nome dos filmes e séries.
        AutoMigration(from = 3, to = 4),
        // Só acrescenta as tabelas do guia.
        AutoMigration(from = 4, to = 5),
        // Só acrescenta os dias de arquivo de cada canal.
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    abstract fun vodDao(): VodDao
    abstract fun epgDao(): EpgDao

    companion object {
        /** Cada conta tem o seu ficheiro, com o nome guardado na conta. */
        fun create(context: Context, name: String): LibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, name).build()
    }
}
