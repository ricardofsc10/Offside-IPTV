package io.github.ricardofsc10.offsideplayer.player

import androidx.media3.common.PlaybackException
import io.github.ricardofsc10.offsideplayer.data.settings.VideoFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlayerRulesTest {

    @Test
    fun `saltos aumentam com a tecla premida`() {
        assertEquals(10_000L, SeekSteps.stepMs(0))
        assertEquals(10_000L, SeekSteps.stepMs(4))
        assertEquals(30_000L, SeekSteps.stepMs(5))
        assertEquals(60_000L, SeekSteps.stepMs(15))
    }

    @Test
    fun `salto não passa do início nem do fim`() {
        assertEquals(0L, SeekSteps.target(fromMs = 5_000, deltaMs = -10_000, durationMs = 60_000))
        assertEquals(60_000L, SeekSteps.target(fromMs = 55_000, deltaMs = 10_000, durationMs = 60_000))
        assertEquals(25_000L, SeekSteps.target(fromMs = 15_000, deltaMs = 10_000, durationMs = 60_000))
        // Sem duração conhecida, só há limite no início.
        assertEquals(70_000L, SeekSteps.target(fromMs = 60_000, deltaMs = 10_000, durationMs = null))
    }

    @Test
    fun `formato não suportado não volta a tentar`() {
        val kind = VodFailurePolicy.classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null, hasNetwork = true)
        assertEquals(VodFailureKind.UNSUPPORTED_FORMAT, kind)
        assertFalse(VodFailurePolicy.shouldRetry(kind))
    }

    @Test
    fun `ficheiro mal formado conta como indisponível e volta a tentar`() {
        val kind = VodFailurePolicy.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null, hasNetwork = true)
        assertEquals(VodFailureKind.UNAVAILABLE, kind)
        assertTrue(VodFailurePolicy.shouldRetry(kind))
    }

    @Test
    fun `erros de rede e recusas seguem a regra do direto`() {
        val http = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(VodFailureKind.REFUSED, VodFailurePolicy.classify(http, 403, hasNetwork = true))
        assertEquals(VodFailureKind.UNAVAILABLE, VodFailurePolicy.classify(http, 404, hasNetwork = true))
        assertEquals(VodFailureKind.NO_NETWORK, VodFailurePolicy.classify(http, 404, hasNetwork = false))
        assertEquals(
            VodFailureKind.SERVER_UNREACHABLE,
            VodFailurePolicy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null, hasNetwork = true),
        )
    }

    @Test
    fun `filme em formato cinema numa TV 16 por 9`() {
        val aspect = 3840f / 1600f
        assertSize(1920f, 800f, VideoFrame.size(VideoFit.FIT, 1920f, 1080f, aspect))
        assertSize(2592f, 1080f, VideoFrame.size(VideoFit.FILL, 1920f, 1080f, aspect))
        assertSize(1920f, 1080f, VideoFrame.size(VideoFit.STRETCH, 1920f, 1080f, aspect))
    }

    @Test
    fun `imagem 4 por 3 e formato desconhecido`() {
        assertSize(1440f, 1080f, VideoFrame.size(VideoFit.FIT, 1920f, 1080f, 4f / 3f))
        assertSize(1920f, 1440f, VideoFrame.size(VideoFit.FILL, 1920f, 1080f, 4f / 3f))
        assertSize(1920f, 1080f, VideoFrame.size(VideoFit.FIT, 1920f, 1080f, null))
    }

    private fun assertSize(width: Float, height: Float, actual: Pair<Float, Float>) {
        assertEquals(width, actual.first, 0.5f)
        assertEquals(height, actual.second, 0.5f)
    }

    @Test
    fun `língua das legendas pelo código e pelo nome`() {
        assertEquals(TrackLanguage.PORTUGUESE, TrackLanguages.of("pt", "Portuguese (European)"))
        assertEquals(TrackLanguage.PORTUGUESE, TrackLanguages.of("pt", null))
        assertEquals(TrackLanguage.PORTUGUESE, TrackLanguages.of(null, "European"))
        assertEquals(TrackLanguage.BRAZILIAN, TrackLanguages.of("pt", "Portuguese (Brazilian)"))
        assertEquals(TrackLanguage.BRAZILIAN, TrackLanguages.of("pt-BR", null))
        assertEquals(TrackLanguage.ENGLISH, TrackLanguages.of("en", "English SDH"))
        assertEquals(TrackLanguage.OTHER, TrackLanguages.of("es", "Spanish"))
        assertEquals("Português", TrackLanguages.displayName(TrackLanguage.PORTUGUESE, "European"))
        assertEquals("English SDH", TrackLanguages.displayName(TrackLanguage.ENGLISH, "English SDH"))
    }

    @Test
    fun `liga as legendas portuguesas completas antes das do Brasil`() {
        val forcedPt = option("Portuguese (forçadas)", TrackLanguage.PORTUGUESE, forced = true)
        val brazil = option("Portuguese (Brazil)", TrackLanguage.BRAZILIAN)
        val portugal = option("Portuguese", TrackLanguage.PORTUGUESE)
        val english = option("English", TrackLanguage.ENGLISH)
        assertEquals(portugal, preferredSubtitle(listOf(english, forcedPt, brazil, portugal)))
        assertEquals(brazil, preferredSubtitle(listOf(english, forcedPt, brazil)))
        assertEquals(null, preferredSubtitle(listOf(english)))
    }

    private fun option(label: String, language: TrackLanguage, forced: Boolean = false) =
        TrackOption(label, null, 0, selected = false, language = language, forced = forced)
}
