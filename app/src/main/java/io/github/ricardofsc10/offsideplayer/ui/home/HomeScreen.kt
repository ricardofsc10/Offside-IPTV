package io.github.ricardofsc10.offsideplayer.ui.home

import io.github.ricardofsc10.offsideplayer.ui.text.trPlural
import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.epg.EpgProgram
import io.github.ricardofsc10.offsideplayer.data.library.CategorySummary
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.ui.AppViewModel
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.SyncState
import io.github.ricardofsc10.offsideplayer.ui.components.ChannelItem
import io.github.ricardofsc10.offsideplayer.ui.components.ChannelMenu
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import io.github.ricardofsc10.offsideplayer.ui.theme.SectionLabel
import io.github.ricardofsc10.offsideplayer.ui.theme.Section
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.ScoreBug
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.BroadcastHeader
import io.github.ricardofsc10.offsideplayer.ui.theme.BarListItem
import io.github.ricardofsc10.offsideplayer.ui.theme.BarButton
import io.github.ricardofsc10.offsideplayer.ui.components.rememberNow

private const val SEARCH_KEY = "search"

private const val SPORT_LIMIT = 6

private fun favoriteKey(channel: ChannelRow) = "favorite-${channel.streamId}"
private fun sportKey(channel: ChannelRow) = "sport-${channel.streamId}"
private fun categoryKey(category: CategorySummary) = "category-${category.id}"

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    listState: LazyListState,
    focusKey: String?,
    onFocusKeyChange: (String) -> Unit,
    onChannelClick: (ChannelRow) -> Unit,
    onSportChannelClick: (ChannelRow) -> Unit,
    onOpenCategory: (CategorySummary) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val categories by viewModel.library.categories.collectAsStateWithLifecycle(initialValue = null)
    val favorites by viewModel.library.favoriteChannels.collectAsStateWithLifecycle(initialValue = null)
    val favoriteList = favorites.orEmpty()
    val visible = categories?.filterNot { it.hidden }
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val now = rememberNow()
    // Muda de minuto a minuto e quando o guia ou as categorias escondidas mudam.
    val sport by produceState(emptyList<Pair<ChannelRow, EpgProgram>>(), guide, now / 60_000, visible) {
        value = viewModel.epg.sportNow(guide, now, SPORT_LIMIT)
    }

    var menuFor by remember { mutableStateOf<ChannelRow?>(null) }
    // Depois de remover um favorito, o item com foco deixa de existir e o foco tem de ir para outro.
    var refocus by remember { mutableIntStateOf(0) }

    // O foco volta ao que abriu o ecrã anterior. Sem isso, vai para o primeiro canal ou categoria.
    val itemKeys = sport.map { sportKey(it.first) } + favoriteList.map(::favoriteKey) + visible.orEmpty().map(::categoryKey)
    val focusTarget = focusKey?.takeIf { it in itemKeys || it == SEARCH_KEY }
        ?: itemKeys.firstOrNull()
        ?: SEARCH_KEY
    val itemFocus = remember { FocusRequester() }
    val loaded = visible != null && favorites != null
    RequestInitialFocus(itemFocus, ready = loaded, trigger = refocus)

    fun Modifier.focusTargetFor(key: String) = if (key == focusTarget) focusRequester(itemFocus) else this

    // O cabeçalho fica fixo e só a lista desliza, para o relógio nunca sair do ecrã.
    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding)) {
        BroadcastHeader(current = Section.CHANNELS) {
            BarButton(
                onClick = {
                    onFocusKeyChange(SEARCH_KEY)
                    onOpenSearch()
                },
                modifier = Modifier.fillMaxHeight().focusTargetFor(SEARCH_KEY),
            ) { Text(tr(R.string.search_caps), style = MaterialTheme.typography.labelLarge) }
        }
        val status = syncMessage(viewModel.syncState)
        if (status != null) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp))
        }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
    ) {
        if (sport.isNotEmpty()) {
            item { SectionLabel(tr(R.string.live_now)) }
            // Dois marcadores por linha, como no início.
            items(sport.chunked(2), key = { pair -> pair.joinToString("-") { sportKey(it.first) } }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { (channel, program) ->
                        ScoreBug(
                            channelName = channel.name,
                            program = program,
                            nowMs = now,
                            onClick = {
                                onFocusKeyChange(sportKey(channel))
                                onSportChannelClick(channel)
                            },
                            onLongClick = {
                                onFocusKeyChange(sportKey(channel))
                                menuFor = channel
                            },
                            modifier = Modifier.weight(1f).focusTargetFor(sportKey(channel)),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (favoriteList.isNotEmpty()) {
            item { SectionLabel(tr(R.string.favorites)) }
            items(favoriteList, key = ::favoriteKey) { channel ->
                ChannelItem(
                    channel = channel,
                    program = guide.now(channel.streamId, now),
                    nowMs = now,
                    showFavoriteTag = false,
                    onClick = {
                        onFocusKeyChange(favoriteKey(channel))
                        onChannelClick(channel)
                    },
                    onLongClick = {
                        onFocusKeyChange(favoriteKey(channel))
                        menuFor = channel
                    },
                    modifier = Modifier.focusTargetFor(favoriteKey(channel)),
                )
            }
        }

        if (!visible.isNullOrEmpty()) {
            item { SectionLabel(tr(R.string.categories)) }
            items(visible, key = ::categoryKey) { category ->
                BarListItem(
                    selected = false,
                    onClick = {
                        onFocusKeyChange(categoryKey(category))
                        onOpenCategory(category)
                    },
                    headlineContent = { Text(category.name, maxLines = 1) },
                    trailingContent = { Text(trPlural(R.plurals.channels_count, category.channelCount), style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.focusTargetFor(categoryKey(category)),
                )
            }
        } else if (visible != null) {
            item {
                Text(
                    tr(R.string.no_visible_categories),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Pal.Muted,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
    }

    menuFor?.let { channel ->
        ChannelMenu(
            channel = channel,
            onToggleFavorite = {
                viewModel.setFavorite(channel.streamId, !channel.favorite)
                menuFor = null
                refocus++
            },
            onDismiss = {
                menuFor = null
                refocus++
            },
        )
    }
}

private fun syncMessage(state: SyncState): String? = when (state) {
    SyncState.Idle -> null
    is SyncState.Running -> tr(R.string.list_updating, state.channels)
    is SyncState.Failed -> tr(R.string.update_failed, state.message)
}
