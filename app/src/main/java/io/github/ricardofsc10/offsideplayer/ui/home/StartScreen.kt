package io.github.ricardofsc10.offsideplayer.ui.home

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.epg.EpgGuide
import io.github.ricardofsc10.offsideplayer.data.epg.EpgProgram
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.data.library.VodItem
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.components.ChannelItem
import io.github.ricardofsc10.offsideplayer.ui.components.rememberNow
import io.github.ricardofsc10.offsideplayer.ui.theme.BroadcastHeader
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.ScoreBug
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.Section
import io.github.ricardofsc10.offsideplayer.ui.theme.SectionLabel
import io.github.ricardofsc10.offsideplayer.ui.vod.VodCard
import kotlinx.coroutines.delay

private const val EXIT_WINDOW_MS = 2_500L
private const val FAVORITES_ON_START = 5

typealias StartEntry = Section

/**
 * Pedido para pôr o foco de volta na fila "Continuar a ver", por exemplo ao fechar o menu.
 * Com [removedKey], espera que esse título saia da fila.
 */
data class StartRefocus(val tick: Int, val removedKey: String? = null)

/** Onde o foco começa no ecrã de início. */
enum class StartFocus { LIVE, SECTION, CONTINUE }

/**
 * Ecrã de arranque. Nada começa a tocar sozinho, mas o jogo que está a dar fica a um OK.
 * Por ordem: os jogos em direto, os canais favoritos e os filmes e séries a meio.
 *
 * @param focused a secção que fica com foco, normalmente a última aberta.
 * @param live os jogos a dar agora, com o canal de cada um, ou `null` enquanto carregam.
 * @param favorites os canais favoritos, ou `null` enquanto carregam.
 * @param guideMissing sem guia, explica onde se liga em vez dos marcadores.
 * @param status uma linha por baixo, como o estado da sincronização.
 * @param continueItems filmes e séries a meio, ou `null` enquanto carregam. OK retoma logo, sem
 *   passar pelo detalhe.
 */
@Composable
fun StartScreen(
    focused: Section,
    startFocus: StartFocus,
    live: List<Pair<ChannelRow, EpgProgram>>?,
    favorites: List<ChannelRow>?,
    guide: EpgGuide,
    guideMissing: Boolean,
    status: String?,
    continueItems: List<VodItem>?,
    refocus: StartRefocus,
    onOpen: (Section) -> Unit,
    onWatch: (ChannelRow) -> Unit,
    onWatchFavorite: (ChannelRow) -> Unit,
    onResume: (VodItem) -> Unit,
    onLongPress: (VodItem) -> Unit,
    onExit: () -> Unit,
) {
    var exitArmed by remember { mutableStateOf(false) }
    BackHandler {
        if (exitArmed) onExit() else exitArmed = true
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(EXIT_WINDOW_MS)
            exitArmed = false
        }
    }
    val now = rememberNow()

    val sectionFocus = remember { FocusRequester() }
    val liveFocus = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }
    // Espera pelas listas, para o foco não ir para um sítio e saltar logo a seguir.
    val target = when {
        startFocus == StartFocus.LIVE && !live.isNullOrEmpty() -> liveFocus
        startFocus == StartFocus.CONTINUE && !continueItems.isNullOrEmpty() -> continueFocus
        else -> sectionFocus
    }
    RequestInitialFocus(target, ready = continueItems != null && live != null && favorites != null)
    var handledTick by remember { mutableIntStateOf(refocus.tick) }
    LaunchedEffect(refocus, continueItems) {
        if (refocus.tick == handledTick) return@LaunchedEffect
        val key = refocus.removedKey
        if (key != null && continueItems.orEmpty().any { "${it.kind}-${it.id}" == key }) return@LaunchedEffect
        handledTick = refocus.tick
        withFrameNanos { }
        runCatching { (if (continueItems.isNullOrEmpty()) sectionFocus else continueFocus).requestFocus() }
    }

    // O cabeçalho fica fixo e só a lista desliza, para o relógio nunca sair do ecrã.
    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding)) {
        BroadcastHeader(current = null, onOpen = onOpen, focusSection = focused, focusRequester = sectionFocus)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
        ) {
            if (!live.isNullOrEmpty()) {
                item { SectionLabel(tr(R.string.live_now)) }
                // Dois marcadores por linha, para cada jogo caber numa linha sem cortes.
                itemsIndexed(live.chunked(2), key = { _, pair -> "live-" + pair.joinToString("-") { it.first.streamId.toString() } }) { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEachIndexed { column, (channel, program) ->
                            ScoreBug(
                                channelName = channel.name,
                                program = program,
                                nowMs = now,
                                onClick = { onWatch(channel) },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(if (row == 0 && column == 0) Modifier.focusRequester(liveFocus) else Modifier),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else if (guideMissing) {
                item {
                    SectionLabel(tr(R.string.live_now))
                    Text(
                        tr(R.string.live_needs_guide),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Pal.Muted,
                    )
                }
            }

            val shownFavorites = favorites.orEmpty().take(FAVORITES_ON_START)
            if (shownFavorites.isNotEmpty()) {
                item { SectionLabel(tr(R.string.favorites)) }
                items(shownFavorites, key = { "favorite-${it.streamId}" }) { channel ->
                    ChannelItem(
                        channel = channel,
                        program = guide.now(channel.streamId, now),
                        nowMs = now,
                        showFavoriteTag = false,
                        onClick = { onWatchFavorite(channel) },
                        onLongClick = {},
                    )
                }
            }

            if (!continueItems.isNullOrEmpty()) {
                item {
                    SectionLabel(tr(R.string.continue_watching))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth().focusRestorer(continueFocus),
                    ) {
                        itemsIndexed(continueItems, key = { _, item -> "${item.kind}-${item.id}" }) { index, item ->
                            VodCard(
                                item = item,
                                onClick = { onResume(item) },
                                onLongClick = { onLongPress(item) },
                                modifier = Modifier
                                    .width(104.dp)
                                    .then(if (index == 0) Modifier.focusRequester(continueFocus) else Modifier),
                            )
                        }
                    }
                }
            }
        }
        val line = if (exitArmed) tr(R.string.press_back_again) else status
        Text(
            line ?: " ",
            style = MaterialTheme.typography.bodyMedium,
            color = if (exitArmed) Pal.Text else Pal.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
