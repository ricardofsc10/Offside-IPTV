package io.github.ricardofsc10.offsideplayer.data.epg

import java.io.InputStream
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

data class XmltvChannel(val id: String, val names: List<String>)

data class XmltvProgram(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
    val sport: Boolean,
)

/**
 * Lê um ficheiro XMLTV sem o carregar todo em memória. Os programas que [keep] recusa nem chegam
 * a ser lidos por dentro, porque um guia de vários dias tem dezenas de milhares.
 */
object XmltvParser {
    private const val MAX_DESCRIPTION = 600
    private val sportWords = listOf("desporto", "sport", "futebol", "football", "soccer")

    fun parse(
        parser: XmlPullParser,
        input: InputStream,
        keep: (startMs: Long, stopMs: Long) -> Boolean,
        onChannel: (XmltvChannel) -> Unit,
        onProgram: (XmltvProgram) -> Unit,
    ) {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser)?.let(onChannel)
                    "programme" -> readProgram(parser, keep)?.let(onProgram)
                }
            }
            event = parser.next()
        }
    }

    private fun readChannel(parser: XmlPullParser): XmltvChannel? {
        val id = parser.getAttributeValue(null, "id")?.trim()
        val names = mutableListOf<String>()
        forEachChild(parser) {
            if (parser.name == "display-name") {
                parser.nextText().trim().takeIf { it.isNotEmpty() }?.let(names::add)
            } else {
                skip(parser)
            }
        }
        return if (id.isNullOrEmpty()) null else XmltvChannel(id, names)
    }

    private fun readProgram(parser: XmlPullParser, keep: (Long, Long) -> Boolean): XmltvProgram? {
        val channel = parser.getAttributeValue(null, "channel")?.trim()
        val start = parseTime(parser.getAttributeValue(null, "start"))
        val stop = parseTime(parser.getAttributeValue(null, "stop"))
        if (channel.isNullOrEmpty() || start == null || stop == null || stop <= start || !keep(start, stop)) {
            skip(parser)
            return null
        }
        var title: String? = null
        var titleIsPortuguese = false
        var description: String? = null
        var sport = false
        forEachChild(parser) {
            when (parser.name) {
                "title" -> {
                    val portuguese = parser.getAttributeValue(null, "lang")?.startsWith("pt") == true
                    val text = parser.nextText().trim()
                    if (text.isNotEmpty() && (title == null || (portuguese && !titleIsPortuguese))) {
                        title = text
                        titleIsPortuguese = portuguese
                    }
                }
                "desc" -> {
                    val text = parser.nextText().trim()
                    if (description == null && text.isNotEmpty()) description = text.take(MAX_DESCRIPTION)
                }
                "category" -> {
                    val text = parser.nextText().lowercase(Locale.ROOT)
                    if (sportWords.any { it in text }) sport = true
                }
                else -> skip(parser)
            }
        }
        return XmltvProgram(channel, start, stop, title ?: return null, description, sport)
    }

    /** Percorre os filhos diretos do elemento atual. [read] tem de consumir o filho todo. */
    private inline fun forEachChild(parser: XmlPullParser, read: () -> Unit) {
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
            if (event == XmlPullParser.START_TAG && parser.depth == depth + 1) read()
        }
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** `20261107000600 +0000`. Sem fuso, conta como UTC. */
    fun parseTime(value: String?): Long? {
        val text = value?.trim() ?: return null
        if (text.length < 12 || !text.substring(0, 12).all(Char::isDigit)) return null
        return try {
            val seconds = if (text.length >= 14 && text[12].isDigit()) text.substring(12, 14).toInt() else 0
            val local = LocalDateTime.of(
                text.substring(0, 4).toInt(),
                text.substring(4, 6).toInt(),
                text.substring(6, 8).toInt(),
                text.substring(8, 10).toInt(),
                text.substring(10, 12).toInt(),
                seconds,
            )
            val zone = Regex("([+-])(\\d{2}):?(\\d{2})").find(text.substring(12))
            val offset = if (zone == null) {
                ZoneOffset.UTC
            } else {
                val sign = if (zone.groupValues[1] == "-") -1 else 1
                ZoneOffset.ofHoursMinutes(sign * zone.groupValues[2].toInt(), sign * zone.groupValues[3].toInt())
            }
            local.toInstant(offset).toEpochMilli()
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** O que a ligação precisa de cada canal do fornecedor. */
data class ChannelEpgKey(val streamId: Long, val epgChannelId: String?, val name: String)

data class EpgLinkReport(val byId: Int, val byName: Int, val unlinked: Int)

/**
 * Liga cada canal do fornecedor a um canal do guia: primeiro pelo `epg_channel_id`, igual ou só
 * com maiúsculas diferentes, e depois pelo nome, contra todos os nomes que o guia dá ao canal.
 */
object EpgMatcher {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")

    /** Também converte letras como ᴴᴰ em HD, que aparecem nos nomes dos canais. */
    fun normalizeName(name: String): String =
        spaces.replace(marks.replace(Normalizer.normalize(name, Normalizer.Form.NFKD), ""), " ")
            .lowercase(Locale.ROOT)
            .trim()

    fun link(channels: List<ChannelEpgKey>, guide: List<XmltvChannel>): Pair<Map<Long, String>, EpgLinkReport> {
        val ids = guide.associateBy({ it.id }, { it.id })
        val lowerIds = HashMap<String, String>()
        val names = HashMap<String, String>()
        guide.forEach { channel ->
            lowerIds.putIfAbsent(channel.id.lowercase(Locale.ROOT), channel.id)
            channel.names.forEach { names.putIfAbsent(normalizeName(it), channel.id) }
        }
        val links = HashMap<Long, String>()
        var byId = 0
        var byName = 0
        channels.forEach { channel ->
            val epgId = channel.epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
            val idMatch = epgId?.let { ids[it] ?: lowerIds[it.lowercase(Locale.ROOT)] }
            val match = idMatch ?: names[normalizeName(channel.name)]
            if (match != null) {
                links[channel.streamId] = match
                if (idMatch != null) byId++ else byName++
            }
        }
        return links to EpgLinkReport(byId, byName, channels.size - byId - byName)
    }
}

data class EpgProgram(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val sport: Boolean,
) {
    fun progress(nowMs: Long): Float =
        ((nowMs - startMs).toFloat() / (stopMs - startMs)).coerceIn(0f, 1f)
}

/** O guia em memória, para as listas de canais não fazerem uma consulta por linha. */
class EpgGuide(
    private val links: Map<Long, String>,
    private val programs: Map<String, List<EpgProgram>>,
) {
    val isEmpty: Boolean get() = links.isEmpty()

    fun channelId(streamId: Long): String? = links[streamId]

    fun now(streamId: Long, nowMs: Long): EpgProgram? =
        programsFor(streamId).firstOrNull { it.startMs <= nowMs && nowMs < it.stopMs }

    /** O programa atual, se houver, e os seguintes. */
    fun upcoming(streamId: Long, nowMs: Long, count: Int): List<EpgProgram> =
        programsFor(streamId).asSequence().filter { it.stopMs > nowMs }.take(count).toList()

    /** Canais do guia com desporto a dar agora. */
    fun sportNow(nowMs: Long): Map<String, EpgProgram> =
        programs.mapNotNull { (id, list) ->
            list.firstOrNull { it.startMs <= nowMs && nowMs < it.stopMs && it.sport }?.let { id to it }
        }.toMap()

    private fun programsFor(streamId: Long): List<EpgProgram> =
        links[streamId]?.let { programs[it] }.orEmpty()

    companion object {
        val EMPTY = EpgGuide(emptyMap(), emptyMap())
    }
}
