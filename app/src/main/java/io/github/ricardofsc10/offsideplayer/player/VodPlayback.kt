package io.github.ricardofsc10.offsideplayer.player

import io.github.ricardofsc10.offsideplayer.ui.text.AppLanguage
import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import io.github.ricardofsc10.offsideplayer.diagnostics.Diagnostics
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed interface VodStatus {
    data object Opening : VodStatus
    data object Playing : VodStatus
    data object Paused : VodStatus
    data object Buffering : VodStatus
    data object Reconnecting : VodStatus
    data object Ended : VodStatus
    data class Failed(val message: String) : VodStatus
}

/** Uma faixa de áudio ou de legendas. Nas legendas, [group] nulo quer dizer "Desligadas". */
data class TrackOption(
    val label: String,
    val group: TrackGroup?,
    val trackIndex: Int,
    val selected: Boolean,
    val language: TrackLanguage = TrackLanguage.OTHER,
    val forced: Boolean = false,
)

/**
 * Reprodução de um filme ou episódio. Ao contrário do direto, usa o buffer normal do Media3,
 * permite pausar e saltar, e retoma na posição certa depois de uma falha.
 * Só deve ser usado na thread principal. Nunca regista URLs, porque contêm as credenciais.
 *
 * @param onSaveProgress chamado a cada 10 segundos, ao pausar, ao sair e no fim.
 */
@OptIn(UnstableApi::class)
class VodPlayback(
    context: Context,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val onSaveProgress: (positionMs: Long, durationMs: Long) -> Unit,
) {
    var status by mutableStateOf<VodStatus>(VodStatus.Opening)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf<Long?>(null)
        private set
    var audioOptions by mutableStateOf<List<TrackOption>>(emptyList())
        private set
    /** "Desligadas", inglês e português, por esta ordem. */
    var textOptions by mutableStateOf<List<TrackOption>>(emptyList())
        private set
    /** Legendas noutras línguas, escondidas por defeito. */
    var otherTextOptions by mutableStateOf<List<TrackOption>>(emptyList())
        private set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set
    var detectedFrameRate by mutableStateOf<Float?>(null)
        private set
    /** Largura a dividir por altura, ou `null` antes da primeira imagem. */
    var videoAspect by mutableStateOf<Float?>(null)
        private set
    /** O ficheiro tem áudio, mas nenhuma faixa num formato que o aparelho toque. */
    var audioUnsupported by mutableStateOf(false)
        private set

    // O FFmpeg só entra quando o aparelho não consegue tocar o áudio, como DTS numa TV sem DTS.
    private val renderers = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    private val player: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
        .build()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private var url: String? = null
    private var attempt = 0
    // Muda a cada ficheiro aberto ou fechado, para uma tentativa atrasada não abrir o ficheiro errado.
    private var session = 0
    private var resumeAtMs = 0L
    // Em pausa ao sair da app, continua em pausa ao voltar.
    private var resumePaused = false
    private var lastFailure: VodFailureKind? = null
    // As legendas portuguesas são escolhidas uma vez por ficheiro. Depois manda quem vê.
    private var subtitlesChosen = false
    private var pendingRetry: Job? = null
    private var monitor: Job? = null
    private var lastSaveAt = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                if (status is VodStatus.Failed && lastFailure == VodFailureKind.NO_NETWORK) {
                    attempt = 0
                    reopen()
                }
            }
        }
    }

    init {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            // Áudio original, sem preferir a dobragem. Legendas em português sempre que o ficheiro
            // as tiver. Legendas noutras línguas só aparecem se forem forçadas, mesmo marcadas
            // como predefinidas no ficheiro. Tudo se muda nos controlos.
            .setPreferredTextLanguages("pt", "por")
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = updateStatus()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateStatus()
                if (!isPlaying) saveProgress()
            }

            override fun onRenderedFirstFrame() {
                attempt = 0
                lastFailure = null
                player.videoFormat?.frameRate?.takeIf { it > 0 }?.let { detectedFrameRate = it }
            }

            override fun onPlayerError(error: PlaybackException) = handleFailure(error)

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioOptions = options(tracks, C.TRACK_TYPE_AUDIO)
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val unsupported = audioGroups.isNotEmpty() && audioGroups.none { it.isSupported }
                if (unsupported && !audioUnsupported) {
                    val mimes = audioGroups.flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).sampleMimeType } }
                    Diagnostics.note(LOG_TAG, "vodAudio unsupported mimes=${mimes.distinct().joinToString(",")}")
                }
                audioUnsupported = unsupported
                val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                val text = options(tracks, C.TRACK_TYPE_TEXT).map { if (textDisabled) it.copy(selected = false) else it }
                if (!subtitlesChosen && text.isNotEmpty()) {
                    subtitlesChosen = true
                    val best = preferredSubtitle(text)
                    if (best != null && !best.selected) {
                        selectText(best)
                        return
                    }
                }
                val (main, others) = text.partition { it.language != TrackLanguage.OTHER }
                textOptions = if (text.isEmpty()) {
                    emptyList()
                } else {
                    listOf(TrackOption(tr(R.string.subtitles_off), null, 0, textDisabled || text.none { it.selected })) +
                        main.sortedWith(compareBy({ it.language.ordinal }, { it.forced }))
                }
                otherTextOptions = others
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        })
        // Só o formato e o descodificador, para perceber no logcat porque um filme não tem som.
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                Diagnostics.note(LOG_TAG, "vodAudio mime=${format.sampleMimeType} channels=${format.channelCount}")
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Diagnostics.note(LOG_TAG, "vodAudio decoder=$decoderName")
            }
        })
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    fun attach(surface: SurfaceView) {
        player.setVideoSurfaceView(surface)
    }

    /** Abre um ficheiro em [startMs]. Fecha sempre a ligação anterior, por causa do limite da conta. */
    fun open(url: String, startMs: Long) {
        this.url = url
        session++
        attempt = 0
        lastFailure = null
        resumeAtMs = startMs
        resumePaused = false
        positionMs = startMs
        durationMs = null
        detectedFrameRate = null
        audioUnsupported = false
        videoAspect = null
        subtitlesChosen = false
        cues = emptyList()
        reopen()
        startMonitor()
    }

    fun retry() {
        attempt = 0
        reopen()
    }

    fun togglePause() {
        if (status is VodStatus.Failed || status == VodStatus.Ended) return
        player.playWhenReady = !player.playWhenReady
        updateStatus()
    }

    fun play() {
        player.playWhenReady = true
        updateStatus()
    }

    val isPaused: Boolean get() = !player.playWhenReady

    fun seekTo(targetMs: Long) {
        player.seekTo(targetMs)
        positionMs = targetMs
    }

    fun selectAudio(option: TrackOption) {
        val group = option.group ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
            .build()
    }

    fun selectText(option: TrackOption) {
        val group = option.group
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (group == null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                clearOverridesOfType(C.TRACK_TYPE_TEXT)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
            }
        }.build()
        if (group == null) cues = emptyList()
    }

    /** Fecha a ligação e guarda a posição, por exemplo ao carregar em Home. */
    fun stop() {
        saveProgress()
        resumeAtMs = player.currentPosition.takeIf { player.playbackState != Player.STATE_IDLE } ?: resumeAtMs
        resumePaused = !player.playWhenReady
        session++
        pendingRetry?.cancel()
        monitor?.cancel()
        player.stop()
    }

    /** Volta a abrir depois de [stop], na mesma posição. */
    fun resume() {
        if (url == null) return
        session++
        attempt = 0
        reopen()
        startMonitor()
    }

    fun release() {
        stop()
        connectivity.unregisterNetworkCallback(networkCallback)
        player.release()
    }

    private fun reopen() {
        val current = url ?: return
        pendingRetry?.cancel()
        status = if (attempt == 0) VodStatus.Opening else VodStatus.Reconnecting
        player.stop()
        player.setMediaItem(MediaItem.fromUri(current), resumeAtMs)
        player.playWhenReady = !resumePaused
        player.prepare()
    }

    private fun updateStatus() {
        if (status is VodStatus.Failed) return
        status = when (player.playbackState) {
            Player.STATE_ENDED -> VodStatus.Ended.also { saveProgress() }
            Player.STATE_BUFFERING -> if (status == VodStatus.Opening || status == VodStatus.Reconnecting) status else VodStatus.Buffering
            Player.STATE_READY -> if (player.playWhenReady) VodStatus.Playing else VodStatus.Paused
            else -> status
        }
    }

    private fun handleFailure(error: PlaybackException) {
        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val kind = VodFailurePolicy.classify(error.errorCode, http, hasNetwork())
        lastFailure = kind
        // A posição antes da falha, para a nova tentativa não recomeçar do início.
        if (player.currentPosition > 0) resumeAtMs = player.currentPosition
        val delayMs = if (VodFailurePolicy.shouldRetry(kind)) RetryPolicy.delayBefore(attempt) else null
        Diagnostics.note(LOG_TAG, "vod ${if (delayMs == null) "failed" else "retry"} attempt=$attempt code=${error.errorCodeName} http=$http")
        if (delayMs == null) {
            status = VodStatus.Failed(failureMessage(kind))
            return
        }
        attempt++
        status = VodStatus.Reconnecting
        val retrySession = session
        pendingRetry?.cancel()
        pendingRetry = scope.launch {
            delay(delayMs)
            if (session == retrySession) reopen()
        }
    }

    private fun failureMessage(kind: VodFailureKind): String = when (kind) {
        VodFailureKind.NO_NETWORK -> tr(R.string.play_no_network)
        VodFailureKind.SERVER_UNREACHABLE -> tr(R.string.play_server_down)
        VodFailureKind.REFUSED -> tr(R.string.vod_refused)
        VodFailureKind.UNAVAILABLE -> tr(R.string.vod_unavailable)
        VodFailureKind.UNSUPPORTED_FORMAT -> tr(R.string.vod_unsupported)
    }

    private fun hasNetwork(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startMonitor() {
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                if (player.playbackState == Player.STATE_IDLE) continue
                positionMs = player.currentPosition
                player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { durationMs = it }
                if (player.isPlaying && SystemClock.elapsedRealtime() - lastSaveAt >= SAVE_INTERVAL_MS) saveProgress()
            }
        }
    }

    private fun saveProgress() {
        val duration = durationMs ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val position = if (player.playbackState == Player.STATE_ENDED) duration else player.currentPosition
        lastSaveAt = SystemClock.elapsedRealtime()
        onSaveProgress(position, duration)
    }

    private fun options(tracks: Tracks, type: Int): List<TrackOption> =
        tracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) }
                .map { index ->
                    val format = group.getTrackFormat(index)
                    TrackOption(
                        label = trackLabel(format, type),
                        group = group.mediaTrackGroup,
                        trackIndex = index,
                        selected = group.isTrackSelected(index),
                        language = TrackLanguages.of(format.language, format.label),
                        forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    )
                }
        }

    private companion object {
        const val LOG_TAG = "OffsideStats"
        const val MONITOR_INTERVAL_MS = 500L
        const val SAVE_INTERVAL_MS = 10_000L
    }
}

/**
 * As legendas a ligar ao abrir: as portuguesas completas, e só sem essas as do Brasil.
 * `null` deixa a escolha do Media3, que já liga as forçadas.
 */
internal fun preferredSubtitle(options: List<TrackOption>): TrackOption? =
    options.firstOrNull { it.language == TrackLanguage.PORTUGUESE && !it.forced }
        ?: options.firstOrNull { it.language == TrackLanguage.BRAZILIAN && !it.forced }

/** Nome da faixa: o do ficheiro, ou a língua, mais os canais de áudio e se as legendas são forçadas. */
private fun trackLabel(format: Format, type: Int): String {
    val language = format.language
        ?.takeUnless { it == C.LANGUAGE_UNDETERMINED || it.isBlank() }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(AppLanguage.locale) }
        ?.replaceFirstChar { it.titlecase(AppLanguage.locale) }
    val fileName = format.label ?: language ?: if (type == C.TRACK_TYPE_AUDIO) tr(R.string.track_audio) else tr(R.string.track_subtitles)
    val base = TrackLanguages.displayName(
        TrackLanguages.of(format.language, format.label),
        fileName,
        tr(R.string.lang_portuguese),
        tr(R.string.lang_brazilian),
    )
    val extra = when {
        type == C.TRACK_TYPE_AUDIO && format.channelCount >= 8 -> " 7.1"
        type == C.TRACK_TYPE_AUDIO && format.channelCount >= 6 -> " 5.1"
        type == C.TRACK_TYPE_TEXT && format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 -> " (${tr(R.string.track_forced)})"
        else -> ""
    }
    return base + extra
}
