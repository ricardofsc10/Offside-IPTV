package io.github.ricardofsc10.offsideplayer.ui.components

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.epg.EpgGuide
import io.github.ricardofsc10.offsideplayer.data.epg.EpgProgram
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.ui.theme.ChannelTagBlock
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.ProgressRail
import io.github.ricardofsc10.offsideplayer.ui.theme.SectionLabel
import io.github.ricardofsc10.offsideplayer.ui.theme.Tag
import io.github.ricardofsc10.offsideplayer.ui.theme.programTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** A hora atual, que avança a cada [intervalMs] para as barras de progresso andarem. */
@Composable
fun rememberNow(intervalMs: Long = 30_000L): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs - System.currentTimeMillis() % intervalMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm", Locale.ROOT)
}

fun formatTime(ms: Long): String = timeFormat.get()!!.format(Date(ms))

fun timeRange(program: EpgProgram): String = "${formatTime(program.startMs)}–${formatTime(program.stopMs)}"

/** Barra fina do tempo já passado de um programa. Usa a cor do texto à volta. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    ProgressRail(fraction, modifier, color = color, track = color.copy(alpha = 0.25f), height = 3.dp)
}

/**
 * Painel ao lado da lista: o canal com foco, o que está a dar, a descrição e o que vem a seguir.
 * A descrição só se lê da base de dados quando o foco para no canal.
 */
@Composable
fun ChannelGuidePane(
    channel: ChannelRow?,
    guide: EpgGuide,
    nowMs: Long,
    loadDescription: suspend (EpgProgram) -> String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(Pal.Bar)) {
        if (channel == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(60.dp)) {
            ChannelTagBlock(channel.name, modifier = Modifier.width(92.dp).fillMaxHeight())
            if (channel.iconUrl != null) {
                Artwork(
                    url = channel.iconUrl,
                    title = channel.name,
                    contentScale = ContentScale.Fit,
                    opaque = false,
                    initialStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 14.dp).size(width = 64.dp, height = 36.dp),
                )
            }
            Text(
                channel.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        val programs = remember(channel.streamId, guide, nowMs / 60_000) {
            guide.upcoming(channel.streamId, nowMs, 5)
        }
        val current = programs.firstOrNull()?.takeIf { it.startMs <= nowMs }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(20.dp)) {
            if (programs.isEmpty()) {
                Text(
                    if (guide.isEmpty) tr(R.string.guide_none) else tr(R.string.guide_channel_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Pal.Muted,
                )
                return@Column
            }
            if (current != null) {
                val match = remember(current) { programTitle(current) }
                if (current.sport) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tag(tr(R.string.tag_live))
                        match.competition?.let { Tag(it, color = Pal.Line) }
                    }
                }
                Text(
                    if (current.sport) match.title.uppercase() else current.title,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(timeRange(current), style = MaterialTheme.typography.titleMedium)
                    ProgressRail(current.progress(nowMs), Modifier.weight(1f))
                    Text(
                        tr(R.string.minutes_left, ((current.stopMs - nowMs) / 60_000).coerceAtLeast(0).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Pal.Muted,
                        maxLines = 1,
                    )
                }
                var description by remember(current) { mutableStateOf<String?>(null) }
                LaunchedEffect(current) {
                    // Espera o foco parar, para o zapping na lista não fazer uma consulta por canal.
                    delay(250)
                    description = loadDescription(current)
                }
                description?.let {
                    Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Pal.Muted)
                }
            }
            val next = programs.filter { it.startMs > nowMs }.take(3)
            if (next.isNotEmpty()) {
                SectionLabel(tr(R.string.up_next), Modifier.padding(top = 4.dp))
                next.forEach { program ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(34.dp).background(Pal.Bar2)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.width(60.dp).fillMaxHeight().background(Pal.Line),
                        ) {
                            Text(formatTime(program.startMs), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            program.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
