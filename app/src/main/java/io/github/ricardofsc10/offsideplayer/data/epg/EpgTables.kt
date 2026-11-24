package io.github.ricardofsc10.offsideplayer.data.epg

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow

/** Um canal do guia, com os nomes por onde pode ser ligado, separados por uma mudança de linha. */
@Entity(tableName = "epg_channels")
data class EpgChannelEntity(
    @PrimaryKey val id: String,
    val names: String,
)

@Entity(
    tableName = "epg_programs",
    primaryKeys = ["channelId", "startMs"],
    indices = [Index("stopMs")],
)
data class EpgProgramEntity(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
    val sport: Boolean,
)

/** O canal do guia de cada canal do fornecedor. */
@Entity(tableName = "epg_links", indices = [Index("channelId")])
data class EpgLinkEntity(
    @PrimaryKey val streamId: Long,
    val channelId: String,
)

/** Uma única linha. Guarda só um resumo do endereço, porque o endereço pode ter credenciais. */
@Entity(tableName = "epg_info")
data class EpgInfoEntity(
    @PrimaryKey val id: Int = 1,
    val importedAt: Long,
    val sourceKey: String,
    val channels: Int,
    val programs: Int,
    val linked: Int,
)

data class EpgLinkRow(val streamId: Long, val channelId: String)

@Dao
interface EpgDao {
    @Query("DELETE FROM epg_channels")
    suspend fun clearChannels()

    @Query("DELETE FROM epg_programs")
    suspend fun clearPrograms()

    @Query("DELETE FROM epg_links")
    suspend fun clearLinks()

    @Insert
    suspend fun insertChannels(channels: List<EpgChannelEntity>)

    @Insert
    suspend fun insertPrograms(programs: List<EpgProgramEntity>)

    @Insert
    suspend fun insertLinks(links: List<EpgLinkEntity>)

    @Upsert
    suspend fun saveInfo(info: EpgInfoEntity)

    @Query("SELECT * FROM epg_info WHERE id = 1")
    suspend fun info(): EpgInfoEntity?

    @Query("UPDATE epg_info SET linked = :linked WHERE id = 1")
    suspend fun setLinked(linked: Int)

    @Query("SELECT * FROM epg_channels")
    suspend fun channels(): List<EpgChannelEntity>

    @Query("SELECT streamId, epgChannelId, name FROM channels")
    suspend fun libraryChannels(): List<ChannelEpgKey>

    @Query("SELECT streamId, channelId FROM epg_links")
    suspend fun links(): List<EpgLinkRow>

    // Só os que ainda não acabaram e só de canais ligados, para o guia em memória ficar pequeno.
    @Query(
        """
        SELECT channelId, startMs, stopMs, title, sport FROM epg_programs
        WHERE stopMs > :nowMs AND channelId IN (SELECT DISTINCT channelId FROM epg_links)
        ORDER BY channelId, startMs
        """,
    )
    suspend fun programsAfter(nowMs: Long): List<EpgProgram>

    // Canais do fornecedor ligados a estes canais do guia, sem os das categorias escondidas.
    @Query(
        """
        SELECT ch.streamId, ch.number, ch.name, ch.categoryId, ch.iconUrl, ch.position,
            (f.streamId IS NOT NULL) AS favorite
        FROM channels ch
        JOIN epg_links l ON l.streamId = ch.streamId
        LEFT JOIN favorites f ON f.streamId = ch.streamId
        LEFT JOIN categories c ON c.id = ch.categoryId
        WHERE l.channelId IN (:channelIds) AND (c.hidden IS NULL OR c.hidden = 0)
        ORDER BY ch.position
        """,
    )
    suspend fun channelsLinkedTo(channelIds: List<String>): List<ChannelRow>

    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun favoriteCount(): Int

    @Query("SELECT COUNT(*) FROM favorites f JOIN epg_links l ON l.streamId = f.streamId")
    suspend fun linkedFavoriteCount(): Int

    @Query(
        """
        SELECT COUNT(*) FROM channels ch LEFT JOIN categories c ON c.id = ch.categoryId
        WHERE c.hidden IS NULL OR c.hidden = 0
        """,
    )
    suspend fun visibleChannelCount(): Int

    @Query(
        """
        SELECT COUNT(*) FROM channels ch JOIN epg_links l ON l.streamId = ch.streamId
        LEFT JOIN categories c ON c.id = ch.categoryId
        WHERE c.hidden IS NULL OR c.hidden = 0
        """,
    )
    suspend fun linkedVisibleChannelCount(): Int

    @Query("SELECT COUNT(*) FROM favorites f JOIN channels ch ON ch.streamId = f.streamId WHERE ch.archiveDays > 0")
    suspend fun archivedFavoriteCount(): Int

    @Query(
        """
        SELECT COUNT(*) FROM channels ch LEFT JOIN categories c ON c.id = ch.categoryId
        WHERE (c.hidden IS NULL OR c.hidden = 0) AND ch.archiveDays > 0
        """,
    )
    suspend fun archivedVisibleChannelCount(): Int

    @Query("SELECT description FROM epg_programs WHERE channelId = :channelId AND startMs = :startMs")
    suspend fun description(channelId: String, startMs: Long): String?
}
