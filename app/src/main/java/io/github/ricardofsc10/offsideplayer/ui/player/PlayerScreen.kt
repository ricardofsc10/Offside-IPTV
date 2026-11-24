package io.github.ricardofsc10.offsideplayer.ui.player

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.produceState
import io.github.ricardofsc10.offsideplayer.ui.theme.ClockBlock
import io.github.ricardofsc10.offsideplayer.ui.theme.LiveBlock
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import io.github.ricardofsc10.offsideplayer.ui.theme.programTitle
import io.github.ricardofsc10.offsideplayer.ui.theme.Tag
import io.github.ricardofsc10.offsideplayer.ui.theme.ProgressRail
import io.github.ricardofsc10.offsideplayer.ui.theme.ChannelTagBlock
import io.github.ricardofsc10.offsideplayer.ui.theme.OverlayBar
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.BarButton
import io.github.ricardofsc10.offsideplayer.ui.theme.BarListItem
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.epg.EpgGuide
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.data.library.ZapContext
import io.github.ricardofsc10.offsideplayer.data.settings.FrameRateMode
import io.github.ricardofsc10.offsideplayer.data.settings.PlayerSettings
import io.github.ricardofsc10.offsideplayer.data.xtream.StreamFormat
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import io.github.ricardofsc10.offsideplayer.player.LivePlayback
import io.github.ricardofsc10.offsideplayer.player.PlaybackStats
import io.github.ricardofsc10.offsideplayer.player.PlaybackStatus
import io.github.ricardofsc10.offsideplayer.ui.AppViewModel
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.components.formatTime
import io.github.ricardofsc10.offsideplayer.ui.components.rememberNow
import io.github.ricardofsc10.offsideplayer.ui.components.timeRange
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val ZAP_DEBOUNCE_MS = 400L
private const val BANNER_MS = 3_000L

@Composable
fun PlayerScreen(
    viewModel: AppViewModel,
    credentials: XtreamCredentials,
    context: ZapContext,
    initialChannel: ChannelRow,
    allowedFormats: List<String>,
    settings: PlayerSettings,
    onExit: (ChannelRow) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val playback = remember {
        LivePlayback(
            appContext, viewModel.http, viewModel.client, credentials, allowedFormats, settings, scope,
            sessions = viewModel.sessions,
        )
    }
    var channel by remember { mutableStateOf(initialChannel) }
    var panelOpen by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    var confirmKeyDown by remember { mutableStateOf(false) }
    var zapJob by remember { mutableStateOf<Job?>(null) }
    val zapLock = remember { Mutex() }
    val rootFocus = remember { FocusRequester() }

    fun zap(forward: Boolean) {
        bannerTick++
        scope.launch {
            zapLock.withLock {
                viewModel.library.neighbor(context, channel, forward)?.let { channel = it }
            }
            // O nome muda logo, mas o stream só abre quando as teclas param.
            zapJob?.cancel()
            zapJob = scope.launch {
                delay(ZAP_DEBOUNCE_MS)
                playback.play(channel.streamId)
            }
        }
    }

    LaunchedEffect(bannerTick) {
        bannerVisible = true
        delay(BANNER_MS)
        bannerVisible = false
    }

    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val now = rememberNow()
    // O canal anterior e o seguinte, anunciados no banner durante o zapping.
    val neighbors by produceState<Pair<ChannelRow?, ChannelRow?>>(null to null, channel.streamId) {
        value = viewModel.library.neighbor(context, channel, forward = false) to
            viewModel.library.neighbor(context, channel, forward = true)
    }

    val status = playback.status
    val failed = status is PlaybackStatus.Failed
    LaunchedEffect(panelOpen, failed) {
        if (!panelOpen && !failed) runCatching { rootFocus.requestFocus() }
    }

    val activity = LocalActivity.current
    val detectedFrameRate = playback.detectedFrameRate
    LaunchedEffect(detectedFrameRate) {
        if (activity != null && detectedFrameRate != null && settings.frameRate == FrameRateMode.ALWAYS) {
            applyFrameRate(activity, detectedFrameRate)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Sair da app fecha logo a ligação, para não gastar o limite da conta.
                Lifecycle.Event.ON_STOP -> {
                    zapJob?.cancel()
                    surface?.keepScreenOn = false
                    playback.stop()
                }
                Lifecycle.Event.ON_START -> if (surface != null) {
                    surface?.keepScreenOn = true
                    playback.play(channel.streamId)
                    bannerTick++
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            zapJob?.cancel()
            playback.release()
        }
    }

    BackHandler {
        if (panelOpen) panelOpen = false else onExit(channel)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                // Com o foco num botão do painel ou do erro, o Compose gastava o primeiro Back a
                // passar o foco para esta caixa, e era preciso carregar duas vezes.
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) {
                        if (panelOpen) panelOpen = false else onExit(channel)
                    }
                    return@onPreviewKeyEvent true
                }
                if (panelOpen) return@onPreviewKeyEvent false
                when (event.key) {
                    // Também funciona num canal com erro, para nunca ficar preso nele.
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) zap(forward = event.key == Key.DirectionDown)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (failed) return@onPreviewKeyEvent false
                        // O OK que abriu o canal é largado já aqui, e isso não pode abrir o painel.
                        if (event.type == KeyEventType.KeyDown) {
                            confirmKeyDown = true
                        } else if (confirmKeyDown) {
                            confirmKeyDown = false
                            panelOpen = true
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { viewContext ->
                SurfaceView(viewContext).also { view ->
                    view.keepScreenOn = true
                    playback.attach(view)
                    surface = view
                    playback.play(channel.streamId)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val message = when (status) {
            PlaybackStatus.Opening -> tr(R.string.opening)
            PlaybackStatus.Reconnecting -> tr(R.string.reconnecting)
            else -> null
        }
        // O oráculo em baixo, como numa transmissão. Com o painel aberto, o painel fica por cima dele.
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            if (panelOpen && status !is PlaybackStatus.Failed) {
                ChannelPanel(
                    playback = playback,
                    channel = channel,
                    onToggleFavorite = {
                        viewModel.setFavorite(channel.streamId, !channel.favorite)
                        channel = channel.copy(favorite = !channel.favorite)
                    },
                )
            }
            val zapping = bannerVisible && !panelOpen
            if (zapping) neighbors.first?.takeIf { it.streamId != channel.streamId }?.let { NeighborBar(tr(R.string.channel_previous), it, guide, now) }
            if (bannerVisible || message != null || panelOpen) {
                LowerThird(channel, message, guide, now)
            }
            if (zapping) neighbors.second?.takeIf { it.streamId != channel.streamId }?.let { NeighborBar(tr(R.string.channel_next), it, guide, now) }
        }
        if (settings.stats) {
            Text(
                statsText(playback.stats),
                color = Pal.Text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 48.dp, vertical = 27.dp)
                    .background(OverlayBar)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (status is PlaybackStatus.Failed) {
            FailurePanel(status.message, onRetry = playback::retry, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * O oráculo do player: o bloco do canal, o programa atual com a hora e o progresso, o bloco do
 * direto e, por baixo, o programa seguinte. Sem guia, fica só o canal.
 */
@Composable
private fun LowerThird(channel: ChannelRow, message: String?, guide: EpgGuide, nowMs: Long) {
    val upcoming = guide.upcoming(channel.streamId, nowMs, 2)
    val current = upcoming.firstOrNull()?.takeIf { it.startMs <= nowMs }
    val next = upcoming.firstOrNull { it.startMs > nowMs }
    Column(modifier = Modifier.widthIn(max = 760.dp)) {
        Row(modifier = Modifier.height(72.dp)) {
            ChannelTagBlock(channel.name, modifier = Modifier.width(104.dp).fillMaxHeight())
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxHeight()
                    .background(OverlayBar)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (current != null) {
                    val match = programTitle(current)
                    Text(
                        if (current.sport) match.title.uppercase() else current.title,
                        color = Pal.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            listOfNotNull(match.competition, timeRange(current)).joinToString(" · "),
                            color = Pal.Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ProgressRail(current.progress(nowMs), Modifier.width(150.dp), track = Pal.Line, height = 3.dp)
                        message?.let { Tag(it, color = Pal.Line) }
                    }
                } else {
                    Text(channel.name, color = Pal.Text, maxLines = 1, style = MaterialTheme.typography.headlineSmall)
                    message?.let { Tag(it, color = Pal.Line) }
                }
            }
            if (current?.sport == true) LiveBlock(Modifier.fillMaxHeight())
            ClockBlock(nowMs, Modifier.fillMaxHeight())
        }
        next?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(30.dp).background(Pal.Bar2)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.width(104.dp).fillMaxHeight().background(Pal.Line),
                ) {
                    Text(tr(R.string.up_next_caps), style = MaterialTheme.typography.labelMedium, color = Pal.Text)
                }
                Text(
                    "${formatTime(it.startMs)}  ${it.title}",
                    color = Pal.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }
}

/** Uma barra fina com o canal anterior ou o seguinte e o que está a dar nele. */
@Composable
private fun NeighborBar(label: String, channel: ChannelRow, guide: EpgGuide, nowMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp).background(OverlayBar)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.width(104.dp).fillMaxHeight().background(Pal.Bar2)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
        Text(
            channel.name,
            style = MaterialTheme.typography.titleSmall,
            color = Pal.Text,
            maxLines = 1,
            modifier = Modifier.padding(start = 14.dp),
        )
        guide.now(channel.streamId, nowMs)?.let {
            Text(
                it.title,
                style = MaterialTheme.typography.bodySmall,
                color = Pal.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 420.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
    }
}

@Composable
private fun FailurePanel(message: String, onRetry: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    RequestInitialFocus(focus)
    Column(modifier = modifier.width(460.dp).background(Pal.Bar)) {
        Box(Modifier.fillMaxWidth().background(Pal.Red).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(tr(R.string.no_picture_caps), style = MaterialTheme.typography.labelLarge, color = Pal.Text)
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(16.dp)) {
            Text(message, color = Pal.Text, style = MaterialTheme.typography.titleLarge)
            BarButton(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text(tr(R.string.retry)) }
        }
    }
}

@Composable
private fun ChannelPanel(
    playback: LivePlayback,
    channel: ChannelRow,
    onToggleFavorite: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    RequestInitialFocus(focus)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(OverlayBar).padding(8.dp),
    ) {
        BarButton(onClick = onToggleFavorite, modifier = Modifier.focusRequester(focus)) {
            Text(if (channel.favorite) tr(R.string.favorite_remove) else tr(R.string.favorite_mark))
        }
        val options = playback.audioOptions
        if (options.size > 1) {
            Text(tr(R.string.audio_caps), color = Pal.Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            options.forEach { option ->
                BarButton(onClick = { playback.selectAudio(option) }, selected = option.selected) {
                    Text(option.label)
                }
            }
        }
    }
}

private fun statsText(stats: PlaybackStats): String = buildString {
    appendLine(
        when (stats.format) {
            StreamFormat.MPEG_TS -> "MPEG-TS"
            StreamFormat.HLS -> "HLS"
            null -> "—"
        },
    )
    appendLine("${stats.width ?: "?"}x${stats.height ?: "?"}, ${stats.fps?.let { "%.1f".format(it) } ?: "?"} fps")
    appendLine(stats.codec ?: "—")
    appendLine(tr(R.string.stats_startup, stats.firstFrameMs?.let { "$it ms" } ?: "—"))
    appendLine("Buffer: ${"%.1f".format(stats.bufferedMs / 1000f)} s")
    appendLine(tr(R.string.stats_stalls, stats.rebuffers, stats.reconnects))
    append(tr(R.string.stats_dropped, stats.droppedFrames))
}
