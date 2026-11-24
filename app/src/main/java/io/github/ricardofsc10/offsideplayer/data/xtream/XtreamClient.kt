package io.github.ricardofsc10.offsideplayer.data.xtream

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class XtreamClient(private val http: OkHttpClient) : XtreamSource, VodSource {

    override suspend fun login(credentials: XtreamCredentials): AccountInfo {
        val text = fetchText(apiUrl(credentials, action = null))
        return XtreamParser.parseAccount(text)
    }

    override suspend fun categories(credentials: XtreamCredentials): List<XtreamCategory> {
        val text = fetchText(apiUrl(credentials, action = "get_live_categories"))
        return XtreamParser.parseCategories(text)
    }

    /** Lê todos os canais por lotes, sem manter a resposta em memória, e devolve o total. */
    override suspend fun forEachChannelBatch(
        credentials: XtreamCredentials,
        batchSize: Int,
        onBatch: suspend (List<LiveChannel>) -> Unit,
    ): Int = forEachBatch(apiUrl(credentials, action = "get_live_streams"), batchSize, XtreamParser::channels, onBatch)

    override suspend fun movieCategories(credentials: XtreamCredentials): List<XtreamCategory> =
        XtreamParser.parseCategories(fetchText(apiUrl(credentials, action = "get_vod_categories")))

    override suspend fun seriesCategories(credentials: XtreamCredentials): List<XtreamCategory> =
        XtreamParser.parseCategories(fetchText(apiUrl(credentials, action = "get_series_categories")))

    override suspend fun forEachMovieBatch(
        credentials: XtreamCredentials,
        batchSize: Int,
        onBatch: suspend (List<Movie>) -> Unit,
    ): Int = forEachBatch(apiUrl(credentials, action = "get_vod_streams"), batchSize, XtreamParser::movies, onBatch)

    override suspend fun forEachSeriesBatch(
        credentials: XtreamCredentials,
        batchSize: Int,
        onBatch: suspend (List<Series>) -> Unit,
    ): Int = forEachBatch(apiUrl(credentials, action = "get_series"), batchSize, XtreamParser::series, onBatch)

    override suspend fun seriesEpisodes(credentials: XtreamCredentials, seriesId: Long): List<Episode> {
        val url = apiUrl(credentials, action = "get_series_info").newBuilder()
            .addQueryParameter("series_id", seriesId.toString())
            .build()
        return XtreamParser.parseSeriesEpisodes(fetchText(url))
    }

    override suspend fun movieInfo(credentials: XtreamCredentials, streamId: Long): MovieInfo {
        val url = apiUrl(credentials, action = "get_vod_info").newBuilder()
            .addQueryParameter("vod_id", streamId.toString())
            .build()
        return XtreamParser.parseMovieInfo(fetchText(url))
    }

    /** URL de um filme ou episódio. Contém as credenciais, por isso nunca vai para os registos. */
    fun mediaUrl(credentials: XtreamCredentials, kind: MediaKind, id: Long, extension: String): String =
        base(credentials).newBuilder()
            .addPathSegment(kind.path)
            .addPathSegment(credentials.username)
            .addPathSegment(credentials.password)
            .addPathSegment("$id.$extension")
            .build()
            .toString()

    private suspend fun <T> forEachBatch(
        url: HttpUrl,
        batchSize: Int,
        parse: (InputStream) -> Sequence<T>,
        onBatch: suspend (List<T>) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        open(url).use { response ->
            var total = 0
            guarded {
                for (batch in parse(response.body.byteStream()).chunked(batchSize)) {
                    onBatch(batch)
                    total += batch.size
                }
            }
            total
        }
    }

    fun streamUrl(credentials: XtreamCredentials, streamId: Long, format: StreamFormat): String =
        base(credentials).newBuilder()
            .addPathSegment("live")
            .addPathSegment(credentials.username)
            .addPathSegment(credentials.password)
            .addPathSegment("$streamId.${format.extension}")
            .build()
            .toString()

    private fun apiUrl(credentials: XtreamCredentials, action: String?): HttpUrl =
        base(credentials).newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .build()

    private fun base(credentials: XtreamCredentials): HttpUrl =
        credentials.server.toHttpUrlOrNull() ?: throw XtreamException.Network()

    private suspend fun fetchText(url: HttpUrl): String = withContext(Dispatchers.IO) {
        open(url).use { response -> guarded { response.body.string() } }
    }

    private suspend fun open(url: HttpUrl): Response {
        val request = Request.Builder().url(url).build()
        val response = guarded { runInterruptible { http.newCall(request).execute() } }
        if (response.isSuccessful) return response
        val code = response.code
        response.close()
        throw if (code == 401 || code == 403) {
            XtreamException.InvalidCredentials()
        } else {
            XtreamException.ServerError(code)
        }
    }

    /** Traduz os erros sem manter a causa original, que pode citar o URL. */
    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: XtreamException) {
            throw e
        } catch (_: InterruptedIOException) {
            throw XtreamException.Timeout()
        } catch (_: IOException) {
            throw XtreamException.Network()
        } catch (e: Exception) {
            if (isMalformed(e)) throw XtreamException.MalformedResponse()
            throw e
        }
}
