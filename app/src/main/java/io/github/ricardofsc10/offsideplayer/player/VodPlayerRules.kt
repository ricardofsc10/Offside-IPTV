package io.github.ricardofsc10.offsideplayer.player

import androidx.media3.common.PlaybackException
import io.github.ricardofsc10.offsideplayer.data.settings.VideoFit

/** Avançar e recuar com as setas. Com a tecla premida, os saltos aumentam. */
object SeekSteps {
    private const val SHORT_MS = 10_000L
    private const val MEDIUM_MS = 30_000L
    private const val LONG_MS = 60_000L
    private const val MEDIUM_AFTER = 5
    private const val LONG_AFTER = 15

    /** [repeatCount] é o do evento da tecla: 0 no primeiro toque e a subir enquanto está premida. */
    fun stepMs(repeatCount: Int): Long = when {
        repeatCount >= LONG_AFTER -> LONG_MS
        repeatCount >= MEDIUM_AFTER -> MEDIUM_MS
        else -> SHORT_MS
    }

    /** Posição de destino, sem passar do início nem do fim. */
    fun target(fromMs: Long, deltaMs: Long, durationMs: Long?): Long {
        val end = durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        return (fromMs + deltaMs).coerceIn(0L, end)
    }
}

enum class VodFailureKind { NO_NETWORK, SERVER_UNREACHABLE, REFUSED, UNAVAILABLE, UNSUPPORTED_FORMAT }

object VodFailurePolicy {
    /**
     * Um formato que o aparelho não sabe ler falha sempre da mesma maneira, por isso não vale a pena
     * tentar de novo. Um ficheiro "mal formado" fica de fora, porque muitas vezes é uma página de erro
     * do servidor, e um erro a meio da descodificação pode ser passageiro.
     */
    fun classify(errorCode: Int, httpCode: Int?, hasNetwork: Boolean): VodFailureKind {
        if (isFormatError(errorCode)) return VodFailureKind.UNSUPPORTED_FORMAT
        return when (FailureClassifier.classify(errorCode, httpCode, hasNetwork)) {
            FailureKind.NO_NETWORK -> VodFailureKind.NO_NETWORK
            FailureKind.SERVER_UNREACHABLE -> VodFailureKind.SERVER_UNREACHABLE
            FailureKind.REFUSED -> VodFailureKind.REFUSED
            FailureKind.UNAVAILABLE -> VodFailureKind.UNAVAILABLE
        }
    }

    fun shouldRetry(kind: VodFailureKind): Boolean = kind != VodFailureKind.UNSUPPORTED_FORMAT

    private val FORMAT_ERRORS = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )

    private fun isFormatError(errorCode: Int): Boolean = errorCode in FORMAT_ERRORS
}

/** Tamanho da imagem no ecrã, para cada [VideoFit]. Maior do que o ecrã em [VideoFit.FILL]. */
object VideoFrame {
    /** [videoAspect] é largura a dividir por altura, já com a forma do píxel. */
    fun size(fit: VideoFit, screenWidth: Float, screenHeight: Float, videoAspect: Float?): Pair<Float, Float> {
        if (videoAspect == null || videoAspect <= 0f || fit == VideoFit.STRETCH) return screenWidth to screenHeight
        val wider = videoAspect > screenWidth / screenHeight
        val fitWidth = if (fit == VideoFit.FIT) wider else !wider
        return if (fitWidth) {
            screenWidth to screenWidth / videoAspect
        } else {
            screenHeight * videoAspect to screenHeight
        }
    }
}

/** Línguas que interessam nas legendas. As outras ficam escondidas em "Outras línguas". */
enum class TrackLanguage { ENGLISH, PORTUGUESE, BRAZILIAN, OTHER }

object TrackLanguages {
    private val brazilMarkers = listOf("brazil", "brasil", "pt-br", "(br)", "[br]")

    /**
     * A língua de uma faixa, pelo código do ficheiro e pelo nome dela. Muitos ficheiros marcam
     * as duas legendas portuguesas só com "por", e só o nome diz qual é a do Brasil.
     */
    fun of(language: String?, label: String?): TrackLanguage {
        val code = language?.lowercase()?.replace('_', '-').orEmpty()
        val name = label?.lowercase().orEmpty()
        val portuguese = code == "pt" || code.startsWith("pt-") || code == "por" ||
            name.contains("portug") || name.contains("european")
        return when {
            portuguese && (code == "pt-br" || brazilMarkers.any { name.contains(it) }) -> TrackLanguage.BRAZILIAN
            portuguese -> TrackLanguage.PORTUGUESE
            code == "en" || code.startsWith("en-") || code == "eng" || name.contains("english") -> TrackLanguage.ENGLISH
            else -> TrackLanguage.OTHER
        }
    }

    /** O nome a mostrar. As portuguesas têm sempre o mesmo nome, seja qual for o do ficheiro. */
    fun displayName(
        language: TrackLanguage,
        fileName: String,
        portuguese: String = "Português",
        brazilian: String = "Português (Brasil)",
    ): String = when (language) {
        TrackLanguage.PORTUGUESE -> portuguese
        TrackLanguage.BRAZILIAN -> brazilian
        else -> fileName
    }
}
