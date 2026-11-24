package io.github.ricardofsc10.offsideplayer.ui.channels

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import io.github.ricardofsc10.offsideplayer.ui.theme.Section
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.BroadcastHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.ui.AppViewModel
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.components.ChannelItem
import io.github.ricardofsc10.offsideplayer.ui.components.ChannelMenu
import io.github.ricardofsc10.offsideplayer.ui.components.ChannelGuidePane
import io.github.ricardofsc10.offsideplayer.ui.components.rememberNow

/**
 * Lista de canais com pressão longa para o menu. Se [focusChannel] estiver definido, dá-lhe foco.
 * Se não estiver nas páginas carregadas, chama [onChannelNotLoaded] uma vez.
 */
@Composable
fun ChannelList(
    items: LazyPagingItems<ChannelRow>,
    listState: LazyListState,
    viewModel: AppViewModel,
    onChannelClick: (ChannelRow) -> Unit,
    focusChannel: ChannelRow?,
    onFocusHandled: () -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocus: FocusRequester? = null,
    onChannelNotLoaded: (ChannelRow) -> Unit = { },
    onChannelFocused: (ChannelRow) -> Unit = { },
) {
    var menuFor by remember { mutableStateOf<ChannelRow?>(null) }
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val now = rememberNow()
    val channelFocus = remember { FocusRequester() }
    var reloadRequested by remember(focusChannel) { mutableStateOf(false) }

    LaunchedEffect(focusChannel, items.itemCount) {
        val target = focusChannel ?: return@LaunchedEffect
        if (items.itemCount == 0) return@LaunchedEffect
        val index = items.itemSnapshotList.indexOfFirst { it?.streamId == target.streamId }
        if (index >= 0) {
            listState.scrollToItem((index - 2).coerceAtLeast(0))
            withFrameNanos { }
            runCatching { channelFocus.requestFocus() }
            onFocusHandled()
        } else if (!reloadRequested) {
            reloadRequested = true
            onChannelNotLoaded(target)
        } else {
            onFocusHandled()
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
            val channel = items[index] ?: return@items
            ChannelItem(
                channel = channel,
                onClick = { onChannelClick(channel) },
                onLongClick = { menuFor = channel },
                program = guide.now(channel.streamId, now),
                nowMs = now,
                modifier = when {
                    channel.streamId == focusChannel?.streamId -> Modifier.focusRequester(channelFocus)
                    index == 0 && firstItemFocus != null -> Modifier.focusRequester(firstItemFocus)
                    else -> Modifier
                }.onFocusChanged { if (it.isFocused) onChannelFocused(channel) },
            )
        }
    }

    menuFor?.let { channel ->
        ChannelMenu(
            channel = channel,
            onToggleFavorite = {
                viewModel.setFavorite(channel.streamId, !channel.favorite)
                menuFor = null
            },
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
fun ChannelsScreen(
    title: String,
    viewModel: AppViewModel,
    listState: LazyListState,
    focusChannel: ChannelRow?,
    onFocusHandled: () -> Unit,
    onChannelClick: (ChannelRow) -> Unit,
) {
    val items = viewModel.categoryChannels.collectAsLazyPagingItems()
    val firstItem = remember { FocusRequester() }
    // Ao voltar do player, o foco vai para o canal atual e não pode saltar para o primeiro depois.
    val startsAtTop = remember { focusChannel == null }
    RequestInitialFocus(firstItem, ready = items.itemCount > 0 && startsAtTop)

    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val now = rememberNow()
    var focused by remember { mutableStateOf(focusChannel) }

    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding)) {
        BroadcastHeader(current = Section.CHANNELS)
        Text(title, maxLines = 1, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
        if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
            Text(tr(R.string.category_no_channels), style = MaterialTheme.typography.bodyLarge, color = Pal.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            ChannelList(
                items = items,
                listState = listState,
                viewModel = viewModel,
                onChannelClick = onChannelClick,
                focusChannel = focusChannel,
                onFocusHandled = onFocusHandled,
                firstItemFocus = firstItem,
                onChannelNotLoaded = viewModel::reloadCategoryAround,
                onChannelFocused = { focused = it },
                modifier = Modifier.weight(0.58f),
            )
            // Sem guia, a lista fica com o ecrã todo.
            if (!guide.isEmpty) {
                ChannelGuidePane(
                    channel = focused,
                    guide = guide,
                    nowMs = now,
                    loadDescription = viewModel.epg::description,
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                )
            }
        }
    }
}
