package io.github.ricardofsc10.offsideplayer.data.xtream

data class XtreamCredentials(
    val server: String,
    val username: String,
    val password: String,
) {
    // Evita que as credenciais apareçam em logs ou em mensagens de erro.
    override fun toString(): String = "XtreamCredentials(***)"
}

data class AccountInfo(
    val status: String?,
    val expiresAtEpochSeconds: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val allowedFormats: List<String>,
)

data class XtreamCategory(
    val id: String,
    val name: String,
)

data class LiveChannel(
    val streamId: Long,
    val number: Int?,
    val name: String,
    val categoryId: String?,
    val iconUrl: String?,
    val epgChannelId: String?,
    /** Dias de arquivo para ver para trás, quando o fornecedor os tem. */
    val archiveDays: Int? = null,
)

data class Movie(
    val streamId: Long,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    /** Extensão do ficheiro, como `mp4` ou `mkv`, que faz parte do URL. */
    val containerExtension: String,
    val rating: String?,
    val addedAtEpochSeconds: Long?,
)

data class Series(
    val seriesId: Long,
    val name: String,
    val categoryId: String?,
    val coverUrl: String?,
    val plot: String?,
    val releaseDate: String?,
    val rating: String?,
    val lastModifiedEpochSeconds: Long?,
)

data class Episode(
    val id: Long,
    val season: Int,
    val number: Int?,
    val title: String?,
    val containerExtension: String,
    val durationSeconds: Int?,
    val plot: String?,
    val imageUrl: String?,
)

data class MovieInfo(
    val plot: String?,
    val durationSeconds: Int?,
    val releaseDate: String?,
    val genre: String?,
    val coverUrl: String?,
)

/** Pasta do URL de cada tipo de ficheiro no servidor. */
enum class MediaKind(val path: String) {
    MOVIE("movie"),
    EPISODE("series"),
}

enum class StreamFormat(val extension: String) {
    MPEG_TS("ts"),
    HLS("m3u8"),
}

/** As mensagens nunca incluem URLs, porque os URLs Xtream contêm as credenciais. */
sealed class XtreamException(message: String) : Exception(message) {
    class Network : XtreamException("network")
    class Timeout : XtreamException("timeout")
    class InvalidCredentials : XtreamException("invalid credentials")
    class AccountInactive(val status: String) : XtreamException("account inactive")
    class ServerError(val code: Int) : XtreamException("server error")
    class MalformedResponse : XtreamException("malformed response")
    class EmptyLibrary : XtreamException("empty library")
}

/** O que a biblioteca precisa do servidor. */
interface XtreamSource {
    suspend fun login(credentials: XtreamCredentials): AccountInfo
    suspend fun categories(credentials: XtreamCredentials): List<XtreamCategory>
    suspend fun forEachChannelBatch(
        credentials: XtreamCredentials,
        batchSize: Int = 500,
        onBatch: suspend (List<LiveChannel>) -> Unit,
    ): Int
}

/** O que os filmes e as séries precisam do servidor. Fica à parte para não pesar no direto. */
interface VodSource {
    suspend fun movieCategories(credentials: XtreamCredentials): List<XtreamCategory>
    suspend fun seriesCategories(credentials: XtreamCredentials): List<XtreamCategory>
    suspend fun forEachMovieBatch(
        credentials: XtreamCredentials,
        batchSize: Int = 500,
        onBatch: suspend (List<Movie>) -> Unit,
    ): Int
    suspend fun forEachSeriesBatch(
        credentials: XtreamCredentials,
        batchSize: Int = 500,
        onBatch: suspend (List<Series>) -> Unit,
    ): Int
    suspend fun seriesEpisodes(credentials: XtreamCredentials, seriesId: Long): List<Episode>
    suspend fun movieInfo(credentials: XtreamCredentials, streamId: Long): MovieInfo
}
