package io.github.ricardofsc10.offsideplayer.data.xtream

import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeToSequence

/**
 * Lê as respostas Xtream. Os servidores variam muito: o mesmo campo pode vir como número,
 * texto, nulo ou até lista vazia, por isso todos os campos passam por [FlexString].
 */
object XtreamParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseAccount(text: String): AccountInfo {
        val dto = try {
            val root = json.parseToJsonElement(text) as? JsonObject
                ?: throw XtreamException.MalformedResponse()
            json.decodeFromJsonElement<LoginDto>(root)
        } catch (_: IllegalArgumentException) {
            throw XtreamException.MalformedResponse()
        }
        val user = dto.userInfo ?: throw XtreamException.MalformedResponse()
        if (user.auth.clean() != "1") throw XtreamException.InvalidCredentials()

        val status = user.status.clean()
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw XtreamException.AccountInactive(status)
        }
        return AccountInfo(
            status = status,
            expiresAtEpochSeconds = user.expiresAt.clean()?.toLongOrNull(),
            maxConnections = user.maxConnections.clean()?.toIntOrNull(),
            activeConnections = user.activeConnections.clean()?.toIntOrNull(),
            allowedFormats = (user.allowedFormats as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content?.lowercase() },
        )
    }

    fun parseCategories(text: String): List<XtreamCategory> {
        val dtos = try {
            json.decodeFromString(ListSerializer(CategoryDto.serializer()), text)
        } catch (_: IllegalArgumentException) {
            throw XtreamException.MalformedResponse()
        }
        return dtos.mapNotNull { dto ->
            val id = dto.id.clean() ?: return@mapNotNull null
            XtreamCategory(id = id, name = dto.name.clean() ?: return@mapNotNull null)
        }
    }

    /**
     * Lê os canais um a um, sem carregar a resposta inteira em memória. O [stream] tem de ficar
     * aberto enquanto a sequência é percorrida. Um formato inválido só falha ao chegar a esse ponto.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun channels(stream: InputStream): Sequence<LiveChannel> =
        json.decodeToSequence(stream, ChannelDto.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
            .mapNotNull { it.toChannel() }

    private fun ChannelDto.toChannel(): LiveChannel? {
        if (type.clean()?.lowercase() in NON_LIVE_TYPES) return null
        return LiveChannel(
            streamId = streamId.clean()?.toLongOrNull() ?: return null,
            number = number.clean()?.toIntOrNull(),
            name = name.clean() ?: return null,
            categoryId = categoryId.clean(),
            iconUrl = icon.clean(),
            epgChannelId = epgChannelId.clean(),
            archiveDays = archiveDuration.clean()?.toIntOrNull()
                ?.takeIf { tvArchive.clean() == "1" && it > 0 },
        )
    }

    /** Lê os filmes um a um, como [channels]. */
    @OptIn(ExperimentalSerializationApi::class)
    fun movies(stream: InputStream): Sequence<Movie> =
        json.decodeToSequence(stream, MovieDto.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
            .mapNotNull { it.toMovie() }

    /** Lê as séries uma a uma, como [channels]. */
    @OptIn(ExperimentalSerializationApi::class)
    fun series(stream: InputStream): Sequence<Series> =
        json.decodeToSequence(stream, SeriesDto.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
            .mapNotNull { it.toSeries() }

    /**
     * Episódios de uma série, ordenados por temporada e número. Os servidores enviam `episodes`
     * como objeto com uma lista por temporada ou como lista de listas.
     */
    fun parseSeriesEpisodes(text: String): List<Episode> {
        val root = parseObject(text)
        val groups: List<Pair<String?, JsonElement>> = when (val episodes = root["episodes"]) {
            is JsonObject -> episodes.entries.map { (season, list) -> season to list }
            is JsonArray -> episodes.map { null to it }
            else -> emptyList()
        }
        return groups
            .flatMap { (seasonKey, list) ->
                val items = when (list) {
                    is JsonArray -> list
                    is JsonObject -> listOf(list)
                    else -> emptyList()
                }
                items.mapNotNull { (it as? JsonObject)?.toEpisode(seasonKey) }
            }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.season }, { it.number ?: Int.MAX_VALUE }))
    }

    /** Detalhe de um filme. Um servidor sem dados envia `info` como lista vazia. */
    fun parseMovieInfo(text: String): MovieInfo {
        val info = parseObject(text)["info"] as? JsonObject ?: JsonObject(emptyMap())
        return MovieInfo(
            plot = info.text("plot") ?: info.text("description"),
            durationSeconds = info.text("duration_secs")?.toIntOrNull()?.takeIf { it > 0 }
                ?: info.text("duration")?.let(::parseDuration),
            releaseDate = info.text("releasedate") ?: info.text("release_date"),
            genre = info.text("genre"),
            coverUrl = info.text("movie_image") ?: info.text("cover_big"),
        )
    }

    private fun parseObject(text: String): JsonObject =
        try {
            json.parseToJsonElement(text) as? JsonObject ?: throw XtreamException.MalformedResponse()
        } catch (_: IllegalArgumentException) {
            throw XtreamException.MalformedResponse()
        }

    private fun JsonObject.toEpisode(seasonKey: String?): Episode? {
        val info = this["info"] as? JsonObject
        return Episode(
            id = text("id")?.toLongOrNull() ?: return null,
            season = text("season")?.toIntOrNull() ?: seasonKey?.toIntOrNull() ?: 0,
            number = text("episode_num")?.toIntOrNull(),
            title = text("title"),
            containerExtension = text("container_extension")?.lowercase() ?: DEFAULT_EXTENSION,
            durationSeconds = info?.text("duration_secs")?.toIntOrNull()?.takeIf { it > 0 }
                ?: info?.text("duration")?.let(::parseDuration),
            plot = info?.text("plot"),
            imageUrl = info?.text("movie_image"),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.clean()

    /** Duração em `HH:MM:SS` ou `MM:SS`. */
    private fun parseDuration(text: String): Int? {
        val parts = text.split(":").map { it.trim().toIntOrNull() ?: return null }
        if (parts.size !in 2..3) return null
        return parts.fold(0) { total, part -> total * 60 + part }.takeIf { it > 0 }
    }

    private fun MovieDto.toMovie(): Movie? = Movie(
        streamId = streamId.clean()?.toLongOrNull() ?: return null,
        name = name.clean() ?: return null,
        categoryId = categoryId.clean(),
        posterUrl = icon.clean(),
        containerExtension = extension.clean()?.lowercase() ?: DEFAULT_EXTENSION,
        rating = rating.clean(),
        addedAtEpochSeconds = added.clean()?.toLongOrNull(),
    )

    private fun SeriesDto.toSeries(): Series? = Series(
        seriesId = seriesId.clean()?.toLongOrNull() ?: return null,
        name = name.clean() ?: return null,
        categoryId = categoryId.clean(),
        coverUrl = cover.clean(),
        plot = plot.clean(),
        releaseDate = releaseDate.clean() ?: releaseDateAlt.clean(),
        rating = rating.clean(),
        lastModifiedEpochSeconds = lastModified.clean()?.toLongOrNull(),
    )

    // Quase todos os servidores enviam a extensão. Sem ela, MP4 é a mais comum.
    private const val DEFAULT_EXTENSION = "mp4"

    private fun String?.clean(): String? =
        this?.trim()?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }

    private val NON_LIVE_TYPES = setOf("movie", "series", "radio_streams")
}

/** Falha de formato durante a leitura. O erro original pode citar conteúdo, por isso é descartado. */
internal fun isMalformed(error: Throwable): Boolean =
    error is SerializationException || error is IllegalArgumentException

private object FlexString : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("FlexString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
private data class LoginDto(
    @SerialName("user_info") val userInfo: UserInfoDto? = null,
)

@Serializable
private data class UserInfoDto(
    @Serializable(with = FlexString::class) val auth: String? = null,
    @Serializable(with = FlexString::class) val status: String? = null,
    @SerialName("exp_date") @Serializable(with = FlexString::class) val expiresAt: String? = null,
    @SerialName("max_connections") @Serializable(with = FlexString::class) val maxConnections: String? = null,
    @SerialName("active_cons") @Serializable(with = FlexString::class) val activeConnections: String? = null,
    @SerialName("allowed_output_formats") val allowedFormats: JsonElement? = null,
)

@Serializable
private data class CategoryDto(
    @SerialName("category_id") @Serializable(with = FlexString::class) val id: String? = null,
    @SerialName("category_name") @Serializable(with = FlexString::class) val name: String? = null,
)

@Serializable
private data class MovieDto(
    @SerialName("stream_id") @Serializable(with = FlexString::class) val streamId: String? = null,
    @Serializable(with = FlexString::class) val name: String? = null,
    @SerialName("stream_icon") @Serializable(with = FlexString::class) val icon: String? = null,
    @SerialName("category_id") @Serializable(with = FlexString::class) val categoryId: String? = null,
    @SerialName("container_extension") @Serializable(with = FlexString::class) val extension: String? = null,
    @Serializable(with = FlexString::class) val rating: String? = null,
    @Serializable(with = FlexString::class) val added: String? = null,
)

@Serializable
private data class SeriesDto(
    @SerialName("series_id") @Serializable(with = FlexString::class) val seriesId: String? = null,
    @Serializable(with = FlexString::class) val name: String? = null,
    @Serializable(with = FlexString::class) val cover: String? = null,
    @Serializable(with = FlexString::class) val plot: String? = null,
    @SerialName("releaseDate") @Serializable(with = FlexString::class) val releaseDate: String? = null,
    @SerialName("release_date") @Serializable(with = FlexString::class) val releaseDateAlt: String? = null,
    @Serializable(with = FlexString::class) val rating: String? = null,
    @SerialName("last_modified") @Serializable(with = FlexString::class) val lastModified: String? = null,
    @SerialName("category_id") @Serializable(with = FlexString::class) val categoryId: String? = null,
)

@Serializable
private data class ChannelDto(
    @SerialName("stream_id") @Serializable(with = FlexString::class) val streamId: String? = null,
    @SerialName("num") @Serializable(with = FlexString::class) val number: String? = null,
    @Serializable(with = FlexString::class) val name: String? = null,
    @SerialName("stream_type") @Serializable(with = FlexString::class) val type: String? = null,
    @SerialName("stream_icon") @Serializable(with = FlexString::class) val icon: String? = null,
    @SerialName("epg_channel_id") @Serializable(with = FlexString::class) val epgChannelId: String? = null,
    @SerialName("category_id") @Serializable(with = FlexString::class) val categoryId: String? = null,
    @SerialName("tv_archive") @Serializable(with = FlexString::class) val tvArchive: String? = null,
    @SerialName("tv_archive_duration") @Serializable(with = FlexString::class) val archiveDuration: String? = null,
)
