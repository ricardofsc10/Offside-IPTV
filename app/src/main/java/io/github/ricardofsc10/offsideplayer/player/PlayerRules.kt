package io.github.ricardofsc10.offsideplayer.player

import androidx.media3.common.PlaybackException
import io.github.ricardofsc10.offsideplayer.data.settings.FormatPreference
import io.github.ricardofsc10.offsideplayer.data.settings.LatencyMode
import io.github.ricardofsc10.offsideplayer.data.xtream.StreamFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ordem pela qual os formatos são tentados. MPEG-TS arranca mais depressa e por isso vem primeiro. */
object StreamPlan {
    fun formats(allowed: List<String>, preference: FormatPreference): List<StreamFormat> = when (preference) {
        FormatPreference.MPEG_TS -> listOf(StreamFormat.MPEG_TS)
        FormatPreference.HLS -> listOf(StreamFormat.HLS)
        FormatPreference.AUTOMATIC -> StreamFormat.entries
            .filter { allowed.isEmpty() || it.extension in allowed }
            .ifEmpty { listOf(StreamFormat.MPEG_TS) }
    }
}

object RetryPolicy {
    private val DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)

    /** Espera antes da tentativa seguinte, ou `null` se já não houver mais tentativas. */
    fun delayBefore(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt)

    /**
     * Só muda de formato na última tentativa. Uma falha passageira, como o limite de ligações
     * durante o zapping, não pode trocar MPEG-TS por HLS, que dá mais atraso.
     */
    fun formatIndexFor(attempt: Int): Int = if (attempt >= DELAYS_MS.size) 1 else 0

    /**
     * Na primeira tentativa, a imagem só começa numa imagem completa, para não mostrar blocos.
     * Se falhar, aceita outros pontos de entrada, porque alguns canais nunca enviam imagens completas.
     */
    fun allowPartialKeyframes(attempt: Int): Boolean = attempt > 0
}

enum class FailureKind { NO_NETWORK, SERVER_UNREACHABLE, REFUSED, UNAVAILABLE }

object FailureClassifier {
    // Os servidores Xtream usam estes códigos para contas recusadas e para o limite de ligações.
    private val REFUSED_HTTP = setOf(401, 403, 429, 456, 458, 509)

    /**
     * [hasNetwork] vem do estado da rede do aparelho. Os códigos do Media3 não distinguem a falta
     * de internet de um servidor em baixo, e uma queda a meio do stream nem sempre dá erro de ligação.
     */
    fun classify(errorCode: Int, httpCode: Int?, hasNetwork: Boolean): FailureKind = when {
        !hasNetwork -> FailureKind.NO_NETWORK
        httpCode in REFUSED_HTTP -> FailureKind.REFUSED
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> FailureKind.SERVER_UNREACHABLE
        else -> FailureKind.UNAVAILABLE
    }

    /**
     * Para uma tentativa que acabou sem imagem e sem erro do player. Se nenhum dado chegou, o
     * servidor não respondeu a tempo. Se chegaram dados, o problema é o canal.
     */
    fun classifyNoImage(hasNetwork: Boolean, receivedData: Boolean): FailureKind = when {
        !hasNetwork -> FailureKind.NO_NETWORK
        receivedData -> FailureKind.UNAVAILABLE
        else -> FailureKind.SERVER_UNREACHABLE
    }
}

data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

object DisplayModeMatcher {
    private const val MIN_SMOOTH_REFRESH = 45f
    private val COMMON_FRAME_RATES = listOf(23.976f, 25f, 29.97f, 50f, 59.94f)

    /** Aproxima a taxa medida à mais próxima das habituais. `null` se não for de confiança. */
    fun snapFrameRate(measured: Float): Float? =
        COMMON_FRAME_RATES.minBy { abs(it - measured) }.takeIf { abs(it - measured) / it <= 0.06f }

    /**
     * Modo com a mesma resolução cuja frequência é múltipla da taxa do vídeo, ou `null` se o atual
     * já servir ou nenhum servir. Evita frequências baixas, como 25 Hz, que tornam o menu pesado.
     */
    fun bestMode(modes: List<DisplayModeInfo>, current: DisplayModeInfo, frameRate: Float): DisplayModeInfo? {
        if (matches(current.refreshRate, frameRate)) return null
        return modes
            .filter { it.width == current.width && it.height == current.height }
            .filter { matches(it.refreshRate, frameRate) }
            .minByOrNull { it.refreshRate }
    }

    private fun matches(refreshRate: Float, frameRate: Float): Boolean {
        if (refreshRate < MIN_SMOOTH_REFRESH) return false
        val ratio = refreshRate / frameRate
        val multiple = ratio.roundToInt()
        return multiple >= 1 && abs(ratio - multiple) / multiple <= 0.002f
    }
}

/**
 * Num canal em direto o servidor envia ao ritmo do vídeo, por isso o buffer não volta a crescer
 * sozinho depois de uma quebra. Abrandar um pouco a reprodução recupera-o antes de a imagem parar.
 */
object BufferRecovery {
    const val SLOW_SPEED = 0.96f

    /** Abranda abaixo de metade do buffer máximo e volta ao normal a três quartos. */
    fun shouldSlow(bufferedMs: Long, slowing: Boolean, mode: LatencyMode): Boolean =
        if (slowing) bufferedMs < mode.maxBufferMs * 3 / 4 else bufferedMs < mode.maxBufferMs / 2
}

/**
 * Taxa de imagens do canal, contada com as imagens descartadas e corrigida pela velocidade.
 * Só é de confiança quando duas medições seguidas dão o mesmo valor.
 */
class FrameRateDetector {
    private var previous: Float? = null

    fun add(frames: Int, seconds: Float, speed: Float): Float? {
        if (seconds <= 0f || speed <= 0f) return null
        val snapped = DisplayModeMatcher.snapFrameRate(frames / seconds / speed)
        val confirmed = snapped?.takeIf { it == previous }
        previous = snapped
        return confirmed
    }
}
