package io.github.ricardofsc10.offsideplayer.ui.player

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import io.github.ricardofsc10.offsideplayer.ui.theme.OverlayBar
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.BarButton
import io.github.ricardofsc10.offsideplayer.ui.theme.BarListItem
import android.view.SurfaceView
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.library.ProgressKind
import io.github.ricardofsc10.offsideplayer.data.settings.FrameRateMode
import io.github.ricardofsc10.offsideplayer.data.settings.VideoFit
import io.github.ricardofsc10.offsideplayer.player.SeekSteps
import io.github.ricardofsc10.offsideplayer.player.TrackOption
import io.github.ricardofsc10.offsideplayer.player.VideoFrame
import io.github.ricardofsc10.offsideplayer.player.VodPlayback
import io.github.ricardofsc10.offsideplayer.player.VodStatus
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.vod.ProgressBar
import io.github.ricardofsc10.offsideplayer.ui.vod.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private const val CONTROLS_MS = 4_000L
private const val SEEK_APPLY_MS = 600L
private const val NEXT_EPISODE_SECONDS = 10
private const val EXIT_CONFIRM_MS = 3_000L

/** Um filme ou episódio para abrir. O [url] tem as credenciais e nunca vai para os registos. */
data class VodPlayRequest(
    val kind: ProgressKind,
    val id: Long,
    val seriesId: Long?,
    val title: String,
    val subtitle: String?,
    val url: String,
    val startMs: Long,
)

@Composable
fun VodPlayerScreen(
    http: OkHttpClient,
    request: VodPlayRequest,
    frameRateMode: FrameRateMode,
    videoFit: VideoFit,
    onVideoFit: (VideoFit) -> Unit,
    onSaveProgress: (VodPlayRequest, positionMs: Long, durationMs: Long) -> Unit,
    /** O episódio seguinte, ou `null` num filme ou no último episódio. */
    nextEpisode: suspend (VodPlayRequest) -> VodPlayRequest?,
    onExit: (VodPlayRequest) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(request) }
    val playback = remember {
        VodPlayback(context, http, scope) { position, duration -> onSaveProgress(current, position, duration) }
    }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var controlsTick by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    // Abre sempre no último separador usado. O primeiro é o das legendas.
    var panelTab by remember { mutableStateOf(PanelTab.SUBTITLES) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var confirmKeyDown by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf<VodPlayRequest?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var exitArmed by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    fun showControls() {
        controlsVisible = true
        controlsTick++
    }

    fun openNext(target: VodPlayRequest) {
        countdown = null
        next = null
        current = target
        playback.open(target.url, target.startMs)
        showControls()
    }

    fun exit() {
        playback.stop()
        onExit(current)
    }

    val status = playback.status
    val failed = status is VodStatus.Failed
    val ended = status == VodStatus.Ended

    // Os controlos desaparecem sozinhos só enquanto o filme anda.
    LaunchedEffect(controlsTick, status, panelOpen) {
        if (status == VodStatus.Playing && !panelOpen) {
            delay(CONTROLS_MS)
            controlsVisible = false
        } else {
            controlsVisible = true
        }
    }

    // No fim de um episódio, o seguinte começa sozinho ao fim de 10 segundos.
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        val upcoming = nextEpisode(current)
        if (upcoming == null) {
            exit()
            return@LaunchedEffect
        }
        next = upcoming
        for (seconds in NEXT_EPISODE_SECONDS downTo 1) {
            countdown = seconds
            delay(1_000)
        }
        openNext(upcoming)
    }

    // O primeiro Voltar só avisa. Sair a meio do filme pede um segundo Voltar dentro de 3 segundos.
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(EXIT_CONFIRM_MS)
            exitArmed = false
        }
    }

    LaunchedEffect(panelOpen, failed, countdown) {
        if (!panelOpen && !failed && countdown == null) runCatching { rootFocus.requestFocus() }
    }

    val activity = LocalActivity.current
    val frameRate = playback.detectedFrameRate
    LaunchedEffect(frameRate) {
        if (activity != null && frameRate != null && frameRateMode == FrameRateMode.ALWAYS) {
            applyFrameRate(activity, frameRate)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Sair da app fecha logo a ligação e guarda a posição.
                Lifecycle.Event.ON_STOP -> {
                    surface?.keepScreenOn = false
                    playback.stop()
                }
                Lifecycle.Event.ON_START -> if (surface != null) {
                    surface?.keepScreenOn = true
                    playback.resume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playback.release()
        }
    }

    fun seekBy(forward: Boolean, repeatCount: Int) {
        if (failed || ended) return
        val step = SeekSteps.stepMs(repeatCount)
        val from = pendingSeekMs ?: playback.positionMs
        pendingSeekMs = SeekSteps.target(from, if (forward) step else -step, playback.durationMs)
        showControls()
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(SEEK_APPLY_MS)
            pendingSeekMs?.let(playback::seekTo)
            pendingSeekMs = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                // O Back é tratado aqui, como no direto. Com o painel aberto, fecha só o painel.
                // A meio do filme, sair pede dois toques, para um Voltar a mais não fechar o filme.
                // Com uma falha ou na contagem do episódio seguinte, um toque chega.
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) {
                        when {
                            panelOpen -> panelOpen = false
                            failed || countdown != null || exitArmed -> exit()
                            else -> {
                                exitArmed = true
                                showControls()
                            }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (panelOpen && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                    if (event.type == KeyEventType.KeyDown) {
                        val step = if (event.key == Key.DirectionRight) 1 else -1
                        val next = (panelTab.ordinal + step).coerceIn(0, PanelTab.entries.lastIndex)
                        panelTab = PanelTab.entries[next]
                    }
                    return@onPreviewKeyEvent true
                }
                if (panelOpen || failed || countdown != null) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) {
                            seekBy(event.key == Key.DirectionRight, event.nativeKeyEvent.repeatCount)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        // O OK que abriu o ficheiro é largado já aqui, e isso não pode pausar.
                        if (event.type == KeyEventType.KeyDown) {
                            confirmKeyDown = true
                        } else if (confirmKeyDown) {
                            confirmKeyDown = false
                            playback.togglePause()
                            showControls()
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyUp) {
                            panelOpen = true
                            showControls()
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (event.type == KeyEventType.KeyUp) showControls()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // A imagem tem o tamanho certo para o modo escolhido. Em "Preencher" fica maior do que
        // o ecrã e o que sobra é cortado.
        BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().clipToBounds()) {
            val density = LocalDensity.current
            val (width, height) = VideoFrame.size(
                videoFit,
                constraints.maxWidth.toFloat(),
                constraints.maxHeight.toFloat(),
                playback.videoAspect,
            )
            AndroidView(
                factory = { viewContext ->
                    SurfaceView(viewContext).also { view ->
                        view.keepScreenOn = true
                        playback.attach(view)
                        surface = view
                        playback.open(current.url, current.startMs)
                    }
                },
                modifier = with(density) { Modifier.requiredSize(width.toDp(), height.toDp()) },
            )
        }

        Subtitles(playback, Modifier.align(Alignment.BottomCenter))

        val notice = when {
            exitArmed -> tr(R.string.press_back_again)
            playback.audioUnsupported && controlsVisible -> tr(R.string.audio_unsupported)
            else -> null
        }
        if (notice != null) {
            Text(
                notice,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .background(OverlayBar)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        val message = when (status) {
            VodStatus.Opening -> tr(R.string.opening)
            VodStatus.Reconnecting -> tr(R.string.reconnecting)
            VodStatus.Buffering -> tr(R.string.loading)
            else -> null
        }
        if (controlsVisible || message != null || pendingSeekMs != null) {
            Controls(
                request = current,
                positionMs = pendingSeekMs ?: playback.positionMs,
                durationMs = playback.durationMs,
                paused = status == VodStatus.Paused,
                message = message,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        when {
            status is VodStatus.Failed -> FailurePanel(
                message = status.message,
                onRetry = playback::retry,
                modifier = Modifier.align(Alignment.Center),
            )
            countdown != null && next != null -> NextEpisodePanel(
                next = next!!,
                seconds = countdown!!,
                onPlayNow = { openNext(next!!) },
                onCancel = { exit() },
                modifier = Modifier.align(Alignment.Center),
            )
            panelOpen -> OptionsPanel(
                tab = panelTab,
                audio = playback.audioOptions,
                text = playback.textOptions,
                otherText = playback.otherTextOptions,
                onAudio = playback::selectAudio,
                onText = playback::selectText,
                videoFit = videoFit,
                onVideoFit = onVideoFit,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun Controls(
    request: VodPlayRequest,
    positionMs: Long,
    durationMs: Long?,
    paused: Boolean,
    message: String?,
    modifier: Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(OverlayBar)
            .padding(horizontal = 48.dp, vertical = 22.dp),
    ) {
        Text(request.title, color = Pal.Text, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        request.subtitle?.let { Text(it.uppercase(), color = Pal.Green, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        val fraction = if (durationMs != null && durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        ProgressBar(fraction)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                listOfNotNull(
                    if (paused) tr(R.string.paused) else null,
                    message,
                    "${formatClock(positionMs)} / ${durationMs?.let(::formatClock) ?: "--:--"}",
                ).joinToString(" · "),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                tr(R.string.vod_controls_hint),
                color = Pal.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Subtitles(playback: VodPlayback, modifier: Modifier) {
    val cues = playback.cues
    if (cues.isEmpty()) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(start = 96.dp, end = 96.dp, bottom = 56.dp),
    ) {
        cues.forEach { cue ->
            val bitmap = cue.bitmap
            val text = cue.text
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null)
            } else if (!text.isNullOrBlank()) {
                Text(
                    text.toString(),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        shadow = Shadow(color = Color.Black, blurRadius = 6f),
                    ),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Os separadores do painel que abre com Baixo. Mudam com Esquerda e Direita. */
private enum class PanelTab(@StringRes private val labelRes: Int) {
    PICTURE(R.string.panel_picture),
    AUDIO(R.string.track_audio),
    SUBTITLES(R.string.track_subtitles),
    ;

    val label: String get() = tr(labelRes)
}

/** Uma linha do painel. */
private data class PanelChoice(
    val label: String,
    val selected: Boolean,
    /** A primeira das outras línguas, que recebe o foco quando se abrem. */
    val firstOther: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun OptionsPanel(
    tab: PanelTab,
    audio: List<TrackOption>,
    text: List<TrackOption>,
    otherText: List<TrackOption>,
    onAudio: (TrackOption) -> Unit,
    onText: (TrackOption) -> Unit,
    videoFit: VideoFit,
    onVideoFit: (VideoFit) -> Unit,
    modifier: Modifier,
) {
    // As outras línguas só aparecem a pedido, ou se uma delas estiver ligada.
    var showOthers by remember { mutableStateOf(otherText.any { it.selected }) }
    var othersOpenedNow by remember { mutableStateOf(false) }
    val choices: List<PanelChoice> = when (tab) {
        PanelTab.PICTURE -> VideoFit.entries.map { PanelChoice(it.label, it == videoFit) { onVideoFit(it) } }
        PanelTab.AUDIO -> audio.map { PanelChoice(it.label, it.selected) { onAudio(it) } }
        PanelTab.SUBTITLES -> buildList {
            text.forEach { add(PanelChoice(it.label, it.selected) { onText(it) }) }
            if (otherText.isNotEmpty()) {
                if (showOthers) {
                    otherText.forEachIndexed { index, option ->
                        add(PanelChoice(option.label, option.selected, firstOther = index == 0) { onText(option) })
                    }
                } else {
                    add(
                        PanelChoice(tr(R.string.other_languages, otherText.size), false) {
                            showOthers = true
                            othersOpenedNow = true
                        },
                    )
                }
            }
        }
    }
    val empty = when (tab) {
        PanelTab.PICTURE -> ""
        PanelTab.AUDIO -> tr(R.string.no_audio)
        PanelTab.SUBTITLES -> tr(R.string.no_subtitles)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            // Por cima da barra de baixo e dentro da margem segura da TV.
            .padding(end = 48.dp, bottom = 140.dp)
            .width(420.dp)
            .background(Pal.Bar)
            .padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelTab.entries.forEach { entry ->
                Text(
                    entry.label.uppercase(),
                    color = if (entry == tab) Pal.Ink else Pal.Muted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .background(if (entry == tab) Pal.Green else Pal.Bar2)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        // Um bloco por separador, para o foco ir para a escolha ligada sempre que o separador muda.
        key(tab) {
            val focus = remember { FocusRequester() }
            RequestInitialFocus(focus)
            val focusIndex = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
            // O botão "Outras línguas" desaparece ao abri-las, e o foco passa para a primeira.
            val othersFocus = remember { FocusRequester() }
            LaunchedEffect(othersOpenedNow) {
                if (othersOpenedNow) {
                    withFrameNanos { }
                    runCatching { othersFocus.requestFocus() }
                    othersOpenedNow = false
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // Com muitas faixas, a lista passa da altura do ecrã e desliza com o foco.
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            ) {
                if (choices.isEmpty()) {
                    // Um botão sem ação, para o painel ter onde pôr o foco.
                    BarButton(onClick = {}, modifier = Modifier.focusRequester(focus)) { Text(empty) }
                }
                choices.forEachIndexed { index, choice ->
                    BarButton(
                        onClick = choice.onClick,
                        selected = choice.selected,
                        modifier = when {
                            choice.firstOther -> Modifier.focusRequester(othersFocus)
                            index == focusIndex -> Modifier.focusRequester(focus)
                            else -> Modifier
                        },
                    ) { Text(choice.label) }
                }
            }
        }
    }
}

@Composable
private fun NextEpisodePanel(
    next: VodPlayRequest,
    seconds: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    RequestInitialFocus(focus)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .width(560.dp)
            .background(Pal.Bar)
            .padding(24.dp),
    ) {
        Text(tr(R.string.next_in, seconds), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(listOfNotNull(next.subtitle, next.title).first(), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarButton(onClick = onPlayNow, modifier = Modifier.focusRequester(focus)) { Text(tr(R.string.play_now)) }
            BarButton(onClick = onCancel) { Text(tr(R.string.cancel)) }
        }
    }
}

@Composable
private fun FailurePanel(message: String, onRetry: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    RequestInitialFocus(focus)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .width(560.dp)
            .background(Pal.Bar)
            .padding(24.dp),
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium)
        BarButton(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text(tr(R.string.retry)) }
    }
}
