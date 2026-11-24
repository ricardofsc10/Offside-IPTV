package io.github.ricardofsc10.offsideplayer.ui.theme

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.epg.EpgProgram
import io.github.ricardofsc10.offsideplayer.ui.components.formatTime
import io.github.ricardofsc10.offsideplayer.ui.components.rememberNow

enum class Section(@StringRes private val labelRes: Int) {
    CHANNELS(R.string.tab_channels),
    MOVIES(R.string.tab_movies),
    SERIES(R.string.tab_series),
    SETTINGS(R.string.tab_settings),
    ;

    val label: String get() = tr(labelRes)
}

private val HeaderHeight = 44.dp

/**
 * A barra do topo de todos os ecrãs: o bloco OFFSIDE, as secções com a atual a verde e o relógio.
 * Com [onOpen], as secções recebem foco e abrem-se, como no ecrã de início. Sem ele, só mostram
 * onde se está.
 */
@Composable
fun BroadcastHeader(
    current: Section?,
    modifier: Modifier = Modifier,
    onOpen: ((Section) -> Unit)? = null,
    focusSection: Section? = null,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val now = rememberNow()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = BarGap,
        modifier = modifier.fillMaxWidth().height(HeaderHeight),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxHeight().background(Pal.Text).padding(horizontal = 16.dp),
        ) {
            Text("OFFSIDE", style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = MaterialTheme.typography.labelLarge.letterSpacing), color = Pal.Ink)
        }
        Section.entries.forEach { section ->
            val active = section == current
            if (onOpen != null) {
                Surface(
                    onClick = { onOpen(section) },
                    shape = ClickableSurfaceDefaults.shape(BarShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Pal.Bar2,
                        contentColor = Pal.Muted,
                        focusedContainerColor = Pal.Green,
                        focusedContentColor = Pal.Ink,
                        pressedContainerColor = Pal.Green,
                        pressedContentColor = Pal.Ink,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (section == focusSection && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                ) {
                    TabLabel(section.label)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(if (active) Pal.Line else Pal.Bar2)
                        .then(if (active) Modifier.selectedMark() else Modifier),
                ) {
                    androidx.tv.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                        Text(
                            section.label.uppercase(),
                            color = if (active) Pal.Text else Pal.Muted,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke(this)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxHeight().background(Pal.Bar2).padding(horizontal = 16.dp),
        ) {
            Text(formatTime(now), style = MaterialTheme.typography.headlineSmall, color = Pal.Text)
        }
    }
}

@Composable
private fun TabLabel(text: String) {
    Box(Modifier.fillMaxHeight()) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
        )
    }
}

/** Competição e jogo, quando o título vem como "Liga: Equipa A - Equipa B". */
data class MatchTitle(val competition: String?, val title: String)

/** Só os programas de desporto têm a competição à frente. Os outros ficam como vêm do guia. */
fun programTitle(program: EpgProgram): MatchTitle =
    if (program.sport) matchTitle(program.title) else MatchTitle(null, program.title)

fun matchTitle(title: String): MatchTitle {
    val split = title.indexOf(": ")
    if (split in 1..28) {
        return MatchTitle(title.substring(0, split).trim(), title.substring(split + 2).trim().replace(" - ", " – "))
    }
    return MatchTitle(null, title.replace(" - ", " – "))
}

/**
 * O marcador: o bloco branco do canal, o jogo em caixa alta com a competição e a hora de fim, uma
 * barra de progresso e o bloco vermelho do direto. É o movimento característico da app.
 */
@Composable
fun ScoreBug(
    channelName: String,
    program: EpgProgram,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val match = remember(program.title) { matchTitle(program.title) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(BarShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Pal.Bar2,
            contentColor = Pal.Text,
            focusedContainerColor = Pal.Green,
            focusedContentColor = Pal.Ink,
            pressedContainerColor = Pal.Green,
            pressedContentColor = Pal.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.height(58.dp).onFocusChanged { focused = it.isFocused },
    ) {
        Row(Modifier.fillMaxHeight()) {
            ChannelTagBlock(channelName, modifier = Modifier.width(84.dp).fillMaxHeight())
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp),
            ) {
                Text(
                    match.title.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(match.competition, tr(R.string.until_time, formatTime(program.stopMs))).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) Pal.Ink else Pal.Muted,
                    maxLines = 1,
                )
                ProgressRail(
                    program.progress(nowMs),
                    Modifier.fillMaxWidth(),
                    color = if (focused) Pal.Ink else Pal.Green,
                    track = if (focused) Pal.Ink.copy(alpha = 0.25f) else Pal.Line,
                    height = 3.dp,
                )
            }
            LiveBlock(Modifier.fillMaxHeight())
        }
    }
}

/** O bloco vermelho do direto, sempre numa linha e da mesma largura. */
@Composable
fun LiveBlock(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.width(70.dp).background(Pal.Red)) {
        Text(tr(R.string.live_badge), style = MaterialTheme.typography.labelSmall, color = Pal.Text, maxLines = 1)
    }
}

/** O relógio num bloco, como no canto de uma transmissão. */
@Composable
fun ClockBlock(nowMs: Long, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.background(Pal.Bar2).padding(horizontal = 14.dp)) {
        Text(formatTime(nowMs), style = MaterialTheme.typography.headlineSmall, color = Pal.Text)
    }
}

/** Fundo semitransparente dos oráculos sobre o vídeo. */
val OverlayBar = Color(0xE60B1220)
