package io.github.ricardofsc10.offsideplayer.player

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.github.ricardofsc10.offsideplayer.data.settings.PlayerSettings
import io.github.ricardofsc10.offsideplayer.data.xtream.StreamFormat
import okhttp3.OkHttpClient

/** Configuração do player para direto: arranque rápido e sempre a melhor qualidade disponível. */
@OptIn(UnstableApi::class)
object LivePlayerFactory {
    // Mínimo perto do máximo para continuar a ler o stream. Parar muito tempo pode levar o servidor a desligar.
    private const val MIN_TO_MAX_GAP_MS = 1_000
    private const val LOADER_RETRIES = 1

    /** [keepBytes] é consultado em cada ligação, para saltar o atraso só em MPEG-TS. */
    fun create(
        context: Context,
        http: OkHttpClient,
        settings: PlayerSettings,
        extractors: DefaultExtractorsFactory,
        keepBytes: () -> Int,
        onSkip: (SkipReport) -> Unit,
        onFlow: (FlowReport) -> Unit,
    ): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                settings.latency.maxBufferMs - MIN_TO_MAX_GAP_MS,
                settings.latency.maxBufferMs,
                settings.latency.startBufferMs,
                settings.latency.rebufferMs,
            )
            .build()
        val network = OkHttpDataSource.Factory(http)
        val dataSources = DataSource.Factory {
            LiveEdgeDataSource(network.createDataSource(), keepBytes(), SystemClock::elapsedRealtime, onSkip, onFlow)
        }
        val mediaSources = DefaultMediaSourceFactory(dataSources, extractors)
            // As tentativas ficam a cargo do LivePlayback, que também alterna o formato.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOADER_RETRIES))
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(settings.tunneling)
                    .setForceHighestSupportedBitrate(true),
            )
        }

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSources)
            .setTrackSelector(trackSelector)
            .build()
    }

    fun setPartialKeyframes(extractors: DefaultExtractorsFactory, allowed: Boolean) {
        val base = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        extractors.setTsExtractorFlags(
            if (allowed) base or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES else base,
        )
    }

    fun mediaItem(url: String, format: StreamFormat): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMimeType(
                when (format) {
                    StreamFormat.MPEG_TS -> MimeTypes.VIDEO_MP2T
                    StreamFormat.HLS -> MimeTypes.APPLICATION_M3U8
                },
            )
            .build()
}
