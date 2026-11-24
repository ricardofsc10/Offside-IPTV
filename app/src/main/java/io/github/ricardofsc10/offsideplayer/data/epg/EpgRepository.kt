package io.github.ricardofsc10.offsideplayer.data.epg

import androidx.room.withTransaction
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.data.library.LibraryDatabase
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

/** Só números, para o logcat. */
data class EpgImportReport(
    val channels: Int,
    val programs: Int,
    val link: EpgLinkReport,
    val downloadMs: Long,
    val totalMs: Long,
) {
    fun logLine(): String =
        "epgSync channels=$channels programs=$programs linkedById=${link.byId} linkedByName=${link.byName} " +
            "unlinked=${link.unlinked} parseMs=$downloadMs ms=$totalMs"
}

class EpgException(message: String) : Exception(message)

/**
 * Descarrega o guia XMLTV do endereço das definições, guarda a janela que interessa e liga os
 * canais do guia aos do fornecedor. Nunca regista o endereço, porque pode ter credenciais.
 */
class EpgRepository(
    private val database: LibraryDatabase,
    private val http: OkHttpClient,
    private val newParser: () -> XmlPullParser,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.epgDao()

    suspend fun isRefreshDue(url: String): Boolean {
        if (url.isBlank()) return false
        val info = dao.info() ?: return true
        val now = clock()
        return info.sourceKey != sourceKey(url) || now < info.importedAt || now - info.importedAt >= REFRESH_MS
    }

    suspend fun refresh(url: String): EpgImportReport = withContext(Dispatchers.IO) {
        val startedAt = clock()
        val channels = mutableListOf<XmltvChannel>()
        val programs = mutableListOf<XmltvProgram>()
        val windowStart = startedAt - KEEP_BEFORE_MS
        val windowEnd = startedAt + KEEP_AFTER_MS
        val request = try {
            Request.Builder().url(url.trim()).build()
        } catch (_: IllegalArgumentException) {
            throw EpgException("invalid address")
        }
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw EpgException("http ${response.code}")
                unzipIfNeeded(response.body.byteStream()).use { input ->
                    XmltvParser.parse(
                        newParser(),
                        input,
                        keep = { start, stop -> stop > windowStart && start < windowEnd },
                        onChannel = channels::add,
                        onProgram = programs::add,
                    )
                }
            }
        } catch (e: IOException) {
            throw EpgException("network ${e.javaClass.simpleName}")
        } catch (e: org.xmlpull.v1.XmlPullParserException) {
            throw EpgException("malformed")
        }
        val parsedAt = clock()
        if (channels.isEmpty() && programs.isEmpty()) throw EpgException("empty")

        // Um guia pode repetir o mesmo programa. A chave é o canal e a hora de início.
        val uniquePrograms = programs.distinctBy { it.channelId to it.startMs }
        val uniqueChannels = channels.distinctBy { it.id }
        database.withTransaction {
            dao.clearPrograms()
            dao.clearChannels()
            uniqueChannels.chunked(BATCH).forEach { batch ->
                dao.insertChannels(batch.map { EpgChannelEntity(it.id, it.names.joinToString("\n")) })
            }
            uniquePrograms.chunked(BATCH).forEach { batch ->
                dao.insertPrograms(
                    batch.map { EpgProgramEntity(it.channelId, it.startMs, it.stopMs, it.title, it.description, it.sport) },
                )
            }
            dao.saveInfo(EpgInfoEntity(1, startedAt, sourceKey(url), uniqueChannels.size, uniquePrograms.size, 0))
        }
        val link = relink()
        EpgImportReport(uniqueChannels.size, uniquePrograms.size, link, parsedAt - startedAt, clock() - startedAt)
    }

    /** Volta a ligar os canais. Também é preciso depois de cada sincronização dos canais. */
    suspend fun relink(): EpgLinkReport = withContext(Dispatchers.IO) {
        val guide = dao.channels().map { XmltvChannel(it.id, it.names.split("\n").filter(String::isNotEmpty)) }
        val (links, report) = EpgMatcher.link(dao.libraryChannels(), guide)
        database.withTransaction {
            dao.clearLinks()
            links.entries.chunked(BATCH).forEach { batch ->
                dao.insertLinks(batch.map { EpgLinkEntity(it.key, it.value) })
            }
            dao.setLinked(links.size)
        }
        report
    }

    suspend fun loadGuide(): EpgGuide = withContext(Dispatchers.IO) {
        val links = dao.links().associate { it.streamId to it.channelId }
        if (links.isEmpty()) return@withContext EpgGuide.EMPTY
        EpgGuide(links, dao.programsAfter(clock()).groupBy { it.channelId })
    }

    /**
     * Um canal por cada programa de desporto a dar agora, o primeiro da lista do fornecedor.
     * As versões HD, FHD e 4K do mesmo canal ligam-se ao mesmo canal do guia e só aparecem uma vez.
     */
    suspend fun sportNow(guide: EpgGuide, nowMs: Long, limit: Int): List<Pair<ChannelRow, EpgProgram>> =
        withContext(Dispatchers.IO) {
            val programs = guide.sportNow(nowMs)
            if (programs.isEmpty()) return@withContext emptyList()
            programs.keys.chunked(900)
                .flatMap { dao.channelsLinkedTo(it) }
                .sortedBy { it.position }
                .distinctBy { guide.channelId(it.streamId) }
                .mapNotNull { channel -> guide.channelId(channel.streamId)?.let { programs[it] }?.let { channel to it } }
                .take(limit)
        }

    suspend fun description(program: EpgProgram): String? = dao.description(program.channelId, program.startMs)

    suspend fun info(): EpgInfoEntity? = dao.info()

    /** Quantos favoritos e canais visíveis têm guia. Só números, para o logcat. */
    suspend fun coverageLine(): String = withContext(Dispatchers.IO) {
        "epgCoverage favorites=${dao.linkedFavoriteCount()}/${dao.favoriteCount()} " +
            "visible=${dao.linkedVisibleChannelCount()}/${dao.visibleChannelCount()} " +
            "archiveFavorites=${dao.archivedFavoriteCount()} archiveVisible=${dao.archivedVisibleChannelCount()}"
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearLinks()
            dao.clearPrograms()
            dao.clearChannels()
        }
    }

    private fun unzipIfNeeded(stream: InputStream): InputStream {
        val buffered = BufferedInputStream(stream, 64 * 1024)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
    }

    private fun sourceKey(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        const val REFRESH_MS = 12L * 60 * 60 * 1000
        const val KEEP_BEFORE_MS = 3L * 60 * 60 * 1000
        const val KEEP_AFTER_MS = 36L * 60 * 60 * 1000
        const val BATCH = 500
    }
}
