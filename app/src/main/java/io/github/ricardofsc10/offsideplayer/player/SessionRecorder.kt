package io.github.ricardofsc10.offsideplayer.player

import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Uma amostra do estado do player. Só números. */
@Serializable
data class SessionSample(
    /** Segundos desde que o canal abriu. */
    val t: Int,
    val bufferMs: Long,
    /** Débito da última janela de leitura, em kbit/s. */
    val kbps: Int?,
    /** Espera mais longa de uma só leitura nessa janela. */
    val maxReadMs: Long?,
    val slow: Boolean,
)

/**
 * Um acontecimento da sessão, com o tempo exato desde que o canal abriu. Os códigos são os do
 * Media3 e do HTTP, nunca URLs.
 */
@Serializable
data class SessionEvent(
    /** Milissegundos desde que o canal abriu. */
    val t: Long,
    val type: String,
    val detail: String? = null,
)

/**
 * Resumo de um canal aberto. Só tem números, códigos e um identificador curto do canal, que não é
 * o nome nem o ID do fornecedor, para o ficheiro poder ser partilhado sem expor a conta.
 */
@Serializable
data class SessionSummary(
    val version: Int = 2,
    val startedAt: String,
    val channel: String,
    val latencyMode: String,
    /** "watched" para um canal visto mais de um minuto sem problemas, "problem" para os outros. */
    val outcome: String,
    /** Tempo desde que o canal abriu até fechar. */
    val openS: Long,
    /** Tempo com imagem, desde a primeira imagem. Zero se nunca chegou. */
    val watchedS: Long,
    val format: String?,
    val width: Int?,
    val height: Int?,
    val fps: Float?,
    val codec: String?,
    val firstFrameMs: Long?,
    val rebuffers: Int,
    val stalledMs: Long,
    val longestStallMs: Long,
    val reconnects: Int,
    /** Tentativas de abrir de novo depois de um erro ou de não chegar imagem. */
    val retries: Int,
    /** O código do erro final, quando o canal ficou sem imagem. */
    val failure: String?,
    val droppedFrames: Int,
    val slowdowns: Int,
    val slowedMs: Long,
    val bufferMinMs: Long?,
    val bufferAvgMs: Long?,
    val kbpsAvg: Int?,
    /** Leituras que ficaram pelo menos [SessionRecorder.GAP_MS] à espera do servidor. */
    val deliveryGaps: Int,
    val longestReadMs: Long,
    val events: List<SessionEvent>,
    val samples: List<SessionSample>,
)

object SessionPolicy {
    /** Um canal visto menos do que isto, sem problemas, é zapping e não fica guardado. */
    const val MIN_WATCHED_MS = 60_000L

    /**
     * Guarda os canais vistos pelo menos um minuto e todos os que tiveram algum problema, mesmo
     * nos primeiros segundos: são esses que explicam as paragens no arranque.
     */
    fun shouldKeep(watchedMs: Long?, hadProblem: Boolean): Boolean =
        hadProblem || (watchedMs != null && watchedMs >= MIN_WATCHED_MS)

    /** Identificador curto e estável do canal, sem ser o ID do fornecedor. */
    fun channelKey(streamId: Long): String =
        MessageDigest.getInstance("SHA-256")
            .digest("offside-$streamId".toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Junta as medições de um canal desde que abre até fechar ou trocar. Os tempos vêm de [clock],
 * em milissegundos monotónicos. Só deve ser usado na thread principal.
 */
class SessionRecorder(
    private val channel: String,
    private val latencyMode: String,
    private val startedAtWallMs: Long,
    private val clock: () -> Long,
) {
    private val openedAt = clock()
    private var firstFrameAt: Long? = null
    private var stallStartedAt: Long? = null
    private var stalledMs = 0L
    private var longestStallMs = 0L
    private var slowedMs = 0L
    private var lastSampleAt: Long? = null
    private var bufferMin: Long? = null
    private var bufferSum = 0L
    private var bufferCount = 0
    private var flowBytes = 0L
    private var flowMs = 0L
    private var lastKbps: Int? = null
    private var lastMaxReadMs: Long? = null
    private var deliveryGaps = 0
    private var longestReadMs = 0L
    private var retries = 0
    private var failure: String? = null
    private var stalls = 0
    private var reconnects = 0
    private val events = mutableListOf<SessionEvent>()
    private val samples = mutableListOf<SessionSample>()
    private var nextSampleAt = openedAt + SAMPLE_EARLY_MS

    private fun event(type: String, detail: String? = null) {
        if (events.size < MAX_EVENTS) events += SessionEvent(clock() - openedAt, type, detail)
    }

    /** Uma tentativa de abrir o canal, no formato indicado. */
    fun onOpen(format: String) = event("open", format)

    fun onFirstFrame() {
        val now = clock()
        if (firstFrameAt == null) {
            firstFrameAt = now
            event("first_frame")
        }
        endStall(now)
    }

    fun onStallStarted(bufferedMs: Long) {
        if (firstFrameAt != null && stallStartedAt == null) {
            stallStartedAt = clock()
            stalls++
            event("stall", "bufferMs=$bufferedMs")
        }
    }

    fun onStallEnded() = endStall(clock())

    /** Nova ligação depois de uma paragem longa ou do fim do stream. */
    fun onReconnect() {
        reconnects++
        event("reconnect")
    }

    /** Nova tentativa depois de um erro ou de não chegar imagem. [code] nunca tem URLs. */
    fun onRetry(code: String) {
        retries++
        event("retry", code)
    }

    /** O canal ficou sem imagem depois de esgotar as tentativas. */
    fun onFailed(code: String) {
        failure = code
        event("failed", code)
    }

    fun onSlow(slow: Boolean, bufferedMs: Long) = event(if (slow) "slow_on" else "slow_off", "bufferMs=$bufferedMs")

    fun onFlow(report: FlowReport) {
        if (report.elapsedMs <= 0) return
        flowBytes += report.bytes
        flowMs += report.elapsedMs
        lastKbps = (report.bytes * 8 / report.elapsedMs).toInt()
        lastMaxReadMs = report.maxReadMs
        if (report.maxReadMs >= GAP_MS) {
            deliveryGaps++
            event("gap", "maxReadMs=${report.maxReadMs}")
        }
        if (report.maxReadMs > longestReadMs) longestReadMs = report.maxReadMs
    }

    /** Chamado pelo monitor do player, a cada meio segundo, desde que o canal abre. */
    fun onSample(bufferedMs: Long, playing: Boolean, slow: Boolean) {
        val now = clock()
        val previous = lastSampleAt
        lastSampleAt = now
        if (playing && stallStartedAt == null && firstFrameAt != null) {
            bufferMin = minOf(bufferMin ?: bufferedMs, bufferedMs)
            bufferSum += bufferedMs
            bufferCount++
            if (slow && previous != null) slowedMs += now - previous
        }
        if (now >= nextSampleAt) {
            // No primeiro minuto, de 5 em 5 segundos, para se ver bem o arranque. Depois, de 10 em 10.
            val interval = if (now - openedAt < EARLY_WINDOW_MS) SAMPLE_EARLY_MS else SAMPLE_INTERVAL_MS
            nextSampleAt = now + interval
            if (samples.size < MAX_SAMPLES) {
                samples += SessionSample(((now - openedAt) / 1000).toInt(), bufferedMs, lastKbps, lastMaxReadMs, slow)
            }
        }
    }

    /** Devolve o resumo, ou null se o canal foi visto pouco tempo e sem problemas. */
    fun finish(stats: PlaybackStats): SessionSummary? {
        val now = clock()
        endStall(now)
        val watched = firstFrameAt?.let { now - it }
        val hadProblem = stalls > 0 || reconnects > 0 || retries > 0 || failure != null
        if (!SessionPolicy.shouldKeep(watched, hadProblem)) return null
        event("close")
        return SessionSummary(
            startedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(Date(startedAtWallMs)),
            channel = channel,
            latencyMode = latencyMode,
            outcome = if (hadProblem) "problem" else "watched",
            openS = (now - openedAt) / 1000,
            watchedS = (watched ?: 0L) / 1000,
            format = stats.format?.name,
            width = stats.width,
            height = stats.height,
            fps = stats.fps,
            codec = stats.codec,
            firstFrameMs = stats.firstFrameMs,
            rebuffers = stats.rebuffers,
            stalledMs = stalledMs,
            longestStallMs = longestStallMs,
            reconnects = stats.reconnects,
            retries = retries,
            failure = failure,
            droppedFrames = stats.droppedFrames,
            slowdowns = stats.slowdowns,
            slowedMs = slowedMs,
            bufferMinMs = bufferMin,
            bufferAvgMs = if (bufferCount > 0) bufferSum / bufferCount else null,
            kbpsAvg = if (flowMs > 0) (flowBytes * 8 / flowMs).toInt() else null,
            deliveryGaps = deliveryGaps,
            longestReadMs = longestReadMs,
            events = events.toList(),
            samples = samples.toList(),
        )
    }

    private fun endStall(now: Long) {
        val started = stallStartedAt ?: return
        stallStartedAt = null
        val length = now - started
        stalledMs += length
        if (length > longestStallMs) longestStallMs = length
        event("stall_end", "ms=$length")
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 10_000L
        const val SAMPLE_EARLY_MS = 5_000L
        const val EARLY_WINDOW_MS = 60_000L
        const val GAP_MS = 1_000L
        // Um jogo de três horas cabe nisto. Mais do que isso fica de fora, para o ficheiro não crescer sem fim.
        const val MAX_SAMPLES = 1_200
        const val MAX_EVENTS = 500
    }
}

/** Um ficheiro JSON por canal guardado, com os mais antigos apagados a partir de [maxFiles]. */
class SessionStore(private val dir: File, private val maxFiles: Int = 300) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(summary: SessionSummary, startedAtWallMs: Long): File {
        dir.mkdirs()
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date(startedAtWallMs))
        // Com zapping, dois canais podem abrir no mesmo segundo.
        var file = File(dir, "$name.json")
        var suffix = 2
        while (file.exists()) file = File(dir, "${name}_${suffix++}.json")
        file.writeText(json.encodeToString(SessionSummary.serializer(), summary))
        prune()
        return file
    }

    /** Do mais recente para o mais antigo. Os ficheiros que não se conseguem ler ficam de fora. */
    fun recent(limit: Int): List<SessionSummary> {
        val files = jsonFiles().sortedByDescending { it.name }
        return files.asSequence()
            .mapNotNull { runCatching { json.decodeFromString(SessionSummary.serializer(), it.readText()) }.getOrNull() }
            .take(limit)
            .toList()
    }

    fun count(): Int = jsonFiles().size

    fun clear() = jsonFiles().forEach { it.delete() }

    private fun jsonFiles(): List<File> = dir.listFiles { file -> file.extension == "json" }?.toList() ?: emptyList()

    private fun prune() {
        val files = dir.listFiles { file -> file.extension == "json" }?.sortedBy { it.name } ?: return
        files.dropLast(maxFiles).forEach { it.delete() }
    }
}
