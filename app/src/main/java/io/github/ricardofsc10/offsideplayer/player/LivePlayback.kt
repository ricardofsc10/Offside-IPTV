package io.github.ricardofsc10.offsideplayer.player

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import io.github.ricardofsc10.offsideplayer.diagnostics.Diagnostics
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.extractor.DefaultExtractorsFactory
import io.github.ricardofsc10.offsideplayer.data.settings.PlayerSettings
import io.github.ricardofsc10.offsideplayer.data.xtream.StreamFormat
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamClient
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed interface PlaybackStatus {
    data object Opening : PlaybackStatus
    data object Playing : PlaybackStatus
    data object Reconnecting : PlaybackStatus
    data class Failed(val message: String) : PlaybackStatus
}

data class PlaybackStats(
    val format: StreamFormat? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Float? = null,
    val codec: String? = null,
    val firstFrameMs: Long? = null,
    val bufferedMs: Long = 0,
    val rebuffers: Int = 0,
    val reconnects: Int = 0,
    val droppedFrames: Int = 0,
    val slowdowns: Int = 0,
) {
    /** Só tem números, para poder ir para o logcat sem expor a conta nem o canal. */
    fun logLine(): String =
        "format=$format firstFrameMs=$firstFrameMs video=${width}x$height fps=$fps codec=$codec " +
            "bufferedMs=$bufferedMs rebuffers=$rebuffers reconnects=$reconnects dropped=$droppedFrames slowdowns=$slowdowns"
}

data class AudioOption(val label: String, val group: TrackGroup, val selected: Boolean)

/**
 * Controla a reprodução de um canal: formato, tentativas, recuperação de paragens, redução do
 * atraso e estatísticas. Usa um único ExoPlayer, para o zapping reaproveitar os descodificadores.
 * Só deve ser usado na thread principal. Nunca regista URLs, porque contêm as credenciais.
 */
@OptIn(UnstableApi::class)
class LivePlayback(
    context: Context,
    http: OkHttpClient,
    private val client: XtreamClient,
    private val credentials: XtreamCredentials,
    allowedFormats: List<String>,
    private val settings: PlayerSettings,
    private val scope: CoroutineScope,
    /** Onde fica um resumo de cada canal visto mais de um minuto. Sem ele, não se guarda nada. */
    private val sessions: SessionStore? = null,
) {
    var status by mutableStateOf<PlaybackStatus>(PlaybackStatus.Opening)
        private set
    var stats by mutableStateOf(PlaybackStats())
        private set
    var audioOptions by mutableStateOf<List<AudioOption>>(emptyList())
        private set

    /** Taxa de imagens do canal atual, quando já se conhece. */
    var detectedFrameRate by mutableStateOf<Float?>(null)
        private set

    // Lido na thread de carregamento em cada ligação.
    @Volatile
    private var keepBytes = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorder: SessionRecorder? = null
    private var recorderStartedAt = 0L

    private val extractors = DefaultExtractorsFactory()

    private val player: ExoPlayer = LivePlayerFactory.create(
        context,
        http,
        settings,
        extractors,
        keepBytes = { keepBytes },
        onSkip = { report ->
            Diagnostics.note(LOG_TAG, "skip readBytes=${report.readBytes} keptBytes=${report.keptBytes} elapsedMs=${report.elapsedMs}")
        },
        onFlow = { report ->
            Log.i(
                LOG_TAG,
                "flow bytes=${report.bytes} waitedMs=${report.waitedMs} elapsedMs=${report.elapsedMs} maxReadMs=${report.maxReadMs}",
            )
            // Chega na thread de carregamento.
            mainHandler.post { recorder?.onFlow(report) }
        },
    )
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val formats = StreamPlan.formats(allowedFormats, settings.format)

    private var streamId: Long? = null
    // Muda a cada canal aberto ou fechado. Uma mensagem de erro que chegue tarde não pode cobrir outro canal.
    private var session = 0
    private var attempt = 0
    private var openedAt = 0L
    private var hasFirstFrame = false
    // Se a última tentativa sem imagem chegou a receber dados do servidor.
    private var receivedData = false
    private var pendingRetry: Job? = null
    private var stallWatch: Job? = null
    private var firstFrameWatch: Job? = null
    private var monitor: Job? = null
    private var framesAtSample = 0
    private var outputAtSample = 0
    private var frameRates = FrameRateDetector()
    // A reprodução está mais lenta para o buffer recuperar.
    private var slowing = false
    private var sampleAt = 0L
    private var lastLogAt = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                if (status is PlaybackStatus.Failed) restartCurrent()
            }
        }
    }

    init {
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (hasFirstFrame) return
                hasFirstFrame = true
                firstFrameWatch?.cancel()
                attempt = 0
                status = PlaybackStatus.Playing
                stats = stats.copy(firstFrameMs = SystemClock.elapsedRealtime() - openedAt)
                recorder?.onFirstFrame()
                framesAtSample = renderedFrames()
                outputAtSample = outputFrames()
                sampleAt = SystemClock.elapsedRealtime()
                player.videoFormat?.frameRate?.takeIf { it > 0 }?.let { detectedFrameRate = it }
            }

            override fun onPlayerError(error: PlaybackException) = handleFailure(error)

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> if (hasFirstFrame) onStall()
                    Player.STATE_READY -> {
                        stallWatch?.cancel()
                        recorder?.onStallEnded()
                        if (hasFirstFrame) status = PlaybackStatus.Playing
                    }
                    Player.STATE_ENDED -> if (streamId != null) reconnect()
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioOptions = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_AUDIO }
                    .mapIndexed { index, group ->
                        val format = group.getTrackFormat(0)
                        AudioOption(
                            label = format.label ?: format.language ?: tr(R.string.track_n, index + 1),
                            group = group.mediaTrackGroup,
                            selected = group.isSelected,
                        )
                    }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                stats = stats.copy(droppedFrames = stats.droppedFrames + droppedFrames)
            }
        })
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    fun attach(surface: SurfaceView) {
        player.setVideoSurfaceView(surface)
    }

    /** Fecha a ligação anterior antes de abrir a nova, por causa do limite de ligações da conta. */
    fun play(streamId: Long) {
        finishSession()
        recorderStartedAt = System.currentTimeMillis()
        recorder = SessionRecorder(
            SessionPolicy.channelKey(streamId),
            settings.latency.name,
            recorderStartedAt,
            SystemClock::elapsedRealtime,
        )
        this.streamId = streamId
        session++
        attempt = 0
        detectedFrameRate = null
        frameRates = FrameRateDetector()
        stats = PlaybackStats()
        status = PlaybackStatus.Opening
        open()
        startMonitor()
    }

    fun retry() {
        streamId?.let(::play)
    }

    fun stop() {
        finishSession()
        streamId = null
        session++
        pendingRetry?.cancel()
        stallWatch?.cancel()
        firstFrameWatch?.cancel()
        monitor?.cancel()
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        stop()
        connectivity.unregisterNetworkCallback(networkCallback)
        player.release()
    }

    fun selectAudio(option: AudioOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group, 0))
            .build()
    }

    private fun restartCurrent() {
        if (streamId == null) return
        attempt = 0
        status = PlaybackStatus.Reconnecting
        open()
    }

    private fun open() {
        val id = streamId ?: return
        pendingRetry?.cancel()
        stallWatch?.cancel()
        firstFrameWatch?.cancel()
        hasFirstFrame = false
        setSlowing(false)
        val format = formats[RetryPolicy.formatIndexFor(attempt) % formats.size]
        keepBytes = if (format == StreamFormat.MPEG_TS) settings.latency.keepBytes else 0
        LivePlayerFactory.setPartialKeyframes(extractors, RetryPolicy.allowPartialKeyframes(attempt))
        stats = stats.copy(format = format)
        recorder?.onOpen(format.name)
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(LivePlayerFactory.mediaItem(client.streamUrl(credentials, id, format), format))
        openedAt = SystemClock.elapsedRealtime()
        player.prepare()
        // Um canal pode enviar dados sem nunca mostrar imagem, e sem isto ficava preso em "A abrir".
        firstFrameWatch = scope.launch {
            delay(FIRST_FRAME_TIMEOUT_MS)
            if (!hasFirstFrame) {
                receivedData = player.totalBufferedDuration > 0 || !player.currentTracks.isEmpty
                handleFailure(null)
            }
        }
    }

    private fun onStall() {
        stats = stats.copy(rebuffers = stats.rebuffers + 1)
        recorder?.onStallStarted(player.totalBufferedDuration)
        stallWatch?.cancel()
        stallWatch = scope.launch {
            delay(settings.latency.stallReconnectMs)
            // Continuar atrasa o direto. Voltar a ligar regressa ao ponto mais recente.
            reconnect()
        }
    }

    private fun reconnect() {
        stats = stats.copy(reconnects = stats.reconnects + 1)
        recorder?.onReconnect()
        status = PlaybackStatus.Reconnecting
        open()
    }

    private fun handleFailure(error: PlaybackException?) {
        // Esta tentativa acabou. Os vigias dela não podem disparar depois e gastar outra tentativa
        // nem trocar a mensagem de erro.
        firstFrameWatch?.cancel()
        stallWatch?.cancel()
        pendingRetry?.cancel()
        val delayMs = RetryPolicy.delayBefore(attempt)
        if (delayMs == null) {
            fail(error)
            return
        }
        val http = (error?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        Diagnostics.note(LOG_TAG, "retry attempt=$attempt format=${stats.format} code=${error?.errorCodeName ?: "NO_FIRST_FRAME"} http=$http")
        recorder?.onRetry(failureCode(error, http))
        attempt++
        status = PlaybackStatus.Reconnecting
        pendingRetry = scope.launch {
            delay(delayMs)
            open()
        }
    }

    private fun fail(error: PlaybackException?) {
        val http = (error?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        Diagnostics.note(LOG_TAG, "failed code=${error?.errorCodeName ?: "NO_FIRST_FRAME"} http=$http")
        recorder?.onFailed(failureCode(error, http))
        player.stop()
        val kind = if (error != null) {
            FailureClassifier.classify(error.errorCode, http, hasNetwork())
        } else {
            FailureClassifier.classifyNoImage(hasNetwork(), receivedData)
        }
        val failedSession = session
        scope.launch {
            val message = failureMessage(kind)
            if (session == failedSession) status = PlaybackStatus.Failed(message)
        }
    }

    /** O código do erro do Media3 e o código HTTP. Nunca o URL, que tem as credenciais. */
    private fun failureCode(error: PlaybackException?, http: Int?): String =
        (error?.errorCodeName ?: "NO_FIRST_FRAME") + (http?.let { " http=$it" } ?: "")

    private fun hasNetwork(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun failureMessage(kind: FailureKind): String = when (kind) {
        FailureKind.NO_NETWORK -> tr(R.string.play_no_network)
        FailureKind.SERVER_UNREACHABLE -> tr(R.string.play_server_down)
        FailureKind.UNAVAILABLE -> tr(R.string.live_unavailable)
        FailureKind.REFUSED -> try {
            val account = client.login(credentials)
            val active = account.activeConnections
            val max = account.maxConnections
            if (active != null && max != null && active >= max) {
                tr(R.string.live_limit, active, max)
            } else {
                tr(R.string.live_refused)
            }
        } catch (_: XtreamException.InvalidCredentials) {
            tr(R.string.err_credentials)
        } catch (e: XtreamException.AccountInactive) {
            tr(R.string.err_inactive, e.status)
        } catch (_: XtreamException) {
            tr(R.string.live_refused)
        }
    }

    private fun startMonitor() {
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                sample()
            }
        }
    }

    private fun sample() {
        val buffered = player.totalBufferedDuration
        val video = player.videoFormat
        var next = stats.copy(
            width = video?.width,
            height = video?.height,
            codec = video?.codecs ?: video?.sampleMimeType,
            bufferedMs = buffered,
        )
        val now = SystemClock.elapsedRealtime()
        if (hasFirstFrame && now - sampleAt >= FPS_WINDOW_MS) {
            val frames = renderedFrames()
            val output = outputFrames()
            val seconds = (now - sampleAt) / 1000f
            next = next.copy(fps = (frames - framesAtSample) / seconds)
            // Conta também as imagens descartadas. Só as mostradas podiam dar 30 num canal a 50
            // e a TV ficava a 59,94 Hz.
            if (detectedFrameRate == null && player.isPlaying) {
                frameRates.add(output - outputAtSample, seconds, player.playbackParameters.speed)?.let {
                    detectedFrameRate = it
                    Diagnostics.note(LOG_TAG, "frameRate detected=$it")
                }
            }
            framesAtSample = frames
            outputAtSample = output
            sampleAt = now
        }
        if (hasFirstFrame && player.isPlaying) {
            val slow = BufferRecovery.shouldSlow(buffered, slowing, settings.latency)
            if (slow != slowing) {
                if (slow) next = next.copy(slowdowns = next.slowdowns + 1)
                setSlowing(slow)
                Diagnostics.note(LOG_TAG, "speed slow=$slow bufferedMs=$buffered")
                recorder?.onSlow(slow, buffered)
            }
        }
        stats = next
        recorder?.onSample(buffered, playing = hasFirstFrame && player.isPlaying, slow = slowing)
        if (hasFirstFrame && now - lastLogAt >= LOG_INTERVAL_MS) {
            lastLogAt = now
            Log.i(LOG_TAG, next.logLine())
        }
    }

    /** Guarda o resumo do canal que estava a dar, se foi visto tempo suficiente. */
    private fun finishSession() {
        val current = recorder ?: return
        recorder = null
        val summary = current.finish(stats) ?: return
        val store = sessions?.takeIf { Diagnostics.enabled } ?: return
        val startedAt = recorderStartedAt
        // Ao sair do player, o scope do ecrã já está cancelado.
        fileScope.launch {
            runCatching { store.save(summary, startedAt) }
                .onSuccess {
                    Log.i(LOG_TAG, "session saved outcome=${summary.outcome} openS=${summary.openS} watchedS=${summary.watchedS} rebuffers=${summary.rebuffers} retries=${summary.retries}")
                }
                .onFailure { Log.i(LOG_TAG, "session not saved error=${it.javaClass.simpleName}") }
        }
    }

    private fun setSlowing(slow: Boolean) {
        slowing = slow
        player.setPlaybackSpeed(if (slow) BufferRecovery.SLOW_SPEED else 1f)
    }

    /** Todas as imagens que saíram do descodificador, mostradas ou não. */
    private fun outputFrames(): Int {
        val counters = player.videoDecoderCounters ?: return 0
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount + counters.droppedBufferCount + counters.skippedOutputBufferCount
    }

    private fun renderedFrames(): Int {
        val counters = player.videoDecoderCounters ?: return 0
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount
    }

    private companion object {
        val fileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val LOG_TAG = "OffsideStats"
        const val MONITOR_INTERVAL_MS = 500L
        const val FIRST_FRAME_TIMEOUT_MS = 8_000L
        const val FPS_WINDOW_MS = 2_000L
        const val LOG_INTERVAL_MS = 10_000L
    }
}
