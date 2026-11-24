package io.github.ricardofsc10.offsideplayer.player

import androidx.media3.common.PlaybackException
import io.github.ricardofsc10.offsideplayer.data.settings.FormatPreference
import io.github.ricardofsc10.offsideplayer.data.settings.LatencyMode
import io.github.ricardofsc10.offsideplayer.data.xtream.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRulesTest {
    private val modes = listOf(
        DisplayModeInfo(13, 1920, 1080, 29.97f),
        DisplayModeInfo(14, 1920, 1080, 23.976f),
        DisplayModeInfo(17, 1920, 1080, 25f),
        DisplayModeInfo(18, 1920, 1080, 24f),
        DisplayModeInfo(20, 1920, 1080, 50f),
        DisplayModeInfo(21, 1920, 1080, 60f),
        DisplayModeInfo(22, 1280, 720, 50f),
        DisplayModeInfo(23, 1920, 1080, 59.94f),
    )
    private val tv5994 = modes.first { it.id == 23 }
    private val tv50 = modes.first { it.id == 20 }

    @Test
    fun `automático tenta MPEG-TS primeiro`() {
        assertEquals(
            listOf(StreamFormat.MPEG_TS, StreamFormat.HLS),
            StreamPlan.formats(listOf("m3u8", "ts"), FormatPreference.AUTOMATIC),
        )
    }

    @Test
    fun `automático respeita os formatos da conta`() {
        assertEquals(listOf(StreamFormat.HLS), StreamPlan.formats(listOf("m3u8"), FormatPreference.AUTOMATIC))
    }

    @Test
    fun `sem informação da conta tenta os dois`() {
        assertEquals(2, StreamPlan.formats(emptyList(), FormatPreference.AUTOMATIC).size)
    }

    @Test
    fun `formatos desconhecidos da conta não deixam a lista vazia`() {
        assertEquals(listOf(StreamFormat.MPEG_TS), StreamPlan.formats(listOf("rtmp"), FormatPreference.AUTOMATIC))
    }

    @Test
    fun `preferência manual usa só esse formato`() {
        assertEquals(listOf(StreamFormat.HLS), StreamPlan.formats(listOf("ts"), FormatPreference.HLS))
    }

    @Test
    fun `três tentativas com esperas crescentes`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, null), (0..3).map(RetryPolicy::delayBefore))
    }

    @Test
    fun `classifica recusas, rede e canal indisponível`() {
        val http = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(FailureKind.REFUSED, FailureClassifier.classify(http, 403, hasNetwork = true))
        assertEquals(FailureKind.REFUSED, FailureClassifier.classify(http, 458, hasNetwork = true))
        assertEquals(FailureKind.UNAVAILABLE, FailureClassifier.classify(http, 404, hasNetwork = true))
        assertEquals(
            FailureKind.SERVER_UNREACHABLE,
            FailureClassifier.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null, hasNetwork = true),
        )
        assertEquals(
            FailureKind.SERVER_UNREACHABLE,
            FailureClassifier.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null, hasNetwork = true),
        )
        assertEquals(
            FailureKind.UNAVAILABLE,
            FailureClassifier.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null, hasNetwork = true),
        )
    }

    @Test
    fun `sem imagem nem erro, distingue servidor parado de canal sem imagem`() {
        assertEquals(FailureKind.SERVER_UNREACHABLE, FailureClassifier.classifyNoImage(hasNetwork = true, receivedData = false))
        assertEquals(FailureKind.UNAVAILABLE, FailureClassifier.classifyNoImage(hasNetwork = true, receivedData = true))
        assertEquals(FailureKind.NO_NETWORK, FailureClassifier.classifyNoImage(hasNetwork = false, receivedData = true))
    }

    @Test
    fun `sem rede no aparelho, qualquer falha é falta de internet`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
        codes.forEach { code ->
            assertEquals(FailureKind.NO_NETWORK, FailureClassifier.classify(code, null, hasNetwork = false))
        }
    }

    @Test
    fun `primeira tentativa só começa numa imagem completa`() {
        assertEquals(listOf(false, true, true), (0..2).map(RetryPolicy::allowPartialKeyframes))
    }

    @Test
    fun `só muda de formato na última tentativa`() {
        assertEquals(listOf(0, 0, 0, 1), (0..3).map(RetryPolicy::formatIndexFor))
    }

    @Test
    fun `aproxima a taxa medida`() {
        assertEquals(50f, DisplayModeMatcher.snapFrameRate(48.5f))
        assertEquals(50f, DisplayModeMatcher.snapFrameRate(49.9f))
        assertEquals(25f, DisplayModeMatcher.snapFrameRate(25f))
        assertEquals(59.94f, DisplayModeMatcher.snapFrameRate(60f))
    }

    @Test
    fun `taxa sem correspondência não é de confiança`() {
        assertNull(DisplayModeMatcher.snapFrameRate(12f))
        assertNull(DisplayModeMatcher.snapFrameRate(38f))
    }

    @Test
    fun `50 imagens numa TV a 59,94 passa para 50 Hz`() {
        assertEquals(20, DisplayModeMatcher.bestMode(modes, tv5994, 50f)?.id)
    }

    @Test
    fun `25 imagens passa para 50 Hz e não para 25 Hz`() {
        assertEquals(20, DisplayModeMatcher.bestMode(modes, tv5994, 25f)?.id)
    }

    @Test
    fun `sem mudança quando a frequência atual já serve`() {
        assertNull(DisplayModeMatcher.bestMode(modes, tv50, 50f))
        assertNull(DisplayModeMatcher.bestMode(modes, tv50, 25f))
        assertNull(DisplayModeMatcher.bestMode(modes, tv5994, 59.94f))
        assertNull(DisplayModeMatcher.bestMode(modes, tv5994, 29.97f))
    }

    @Test
    fun `59,94 imagens numa TV a 50 Hz volta a 59,94`() {
        assertEquals(23, DisplayModeMatcher.bestMode(modes, tv50, 59.94f)?.id)
    }

    @Test
    fun `sem modo compatível não muda`() {
        val only60 = listOf(DisplayModeInfo(1, 1920, 1080, 60f))
        assertNull(DisplayModeMatcher.bestMode(only60, only60[0], 50f))
    }

    @Test
    fun `não muda de resolução`() {
        val tv720 = DisplayModeInfo(11, 1280, 720, 60f)
        assertEquals(22, DisplayModeMatcher.bestMode(modes + tv720, tv720, 50f)?.id)
    }

    @Test
    fun `abranda com pouco buffer e só volta ao normal quando recupera`() {
        val mode = LatencyMode.MINIMUM
        assertFalse(BufferRecovery.shouldSlow(4_000, slowing = false, mode))
        assertTrue(BufferRecovery.shouldSlow(2_900, slowing = false, mode))
        assertTrue(BufferRecovery.shouldSlow(4_000, slowing = true, mode))
        assertFalse(BufferRecovery.shouldSlow(4_600, slowing = true, mode))
    }

    @Test
    fun `taxa de imagens precisa de duas medições iguais`() {
        val detector = FrameRateDetector()
        assertNull(detector.add(frames = 100, seconds = 2f, speed = 1f))
        assertEquals(50f, detector.add(frames = 99, seconds = 2f, speed = 1f))
    }

    @Test
    fun `medição instável não confirma a taxa`() {
        val detector = FrameRateDetector()
        assertNull(detector.add(frames = 60, seconds = 2f, speed = 1f))
        assertNull(detector.add(frames = 100, seconds = 2f, speed = 1f))
    }

    @Test
    fun `taxa medida a abrandar é corrigida pela velocidade`() {
        val detector = FrameRateDetector()
        detector.add(frames = 48, seconds = 2f, speed = 0.96f)
        assertEquals(25f, detector.add(frames = 48, seconds = 2f, speed = 0.96f))
    }
}
