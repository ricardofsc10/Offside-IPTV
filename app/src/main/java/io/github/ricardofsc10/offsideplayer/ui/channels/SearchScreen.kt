package io.github.ricardofsc10.offsideplayer.ui.channels

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import io.github.ricardofsc10.offsideplayer.ui.theme.Section
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.BroadcastHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.ui.AppViewModel
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.components.TvTextField

@Composable
fun SearchScreen(
    viewModel: AppViewModel,
    listState: LazyListState,
    focusChannel: ChannelRow?,
    onFocusHandled: () -> Unit,
    onChannelClick: (ChannelRow) -> Unit,
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val items = viewModel.searchResults.collectAsLazyPagingItems()
    val field = remember { FocusRequester() }
    val startsAtField = remember { focusChannel == null }
    RequestInitialFocus(field, ready = startsAtField)

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().padding(ScreenPadding),
    ) {
        BroadcastHeader(current = Section.CHANNELS)
        TvTextField(
            label = tr(R.string.search_channel),
            value = query,
            onValueChange = viewModel::setSearchQuery,
            imeAction = ImeAction.Search,
            modifier = Modifier.focusRequester(field),
        )
        val hint = when {
            query.isBlank() -> tr(R.string.search_channel_hint)
            items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading -> tr(R.string.no_results)
            else -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Pal.Muted) }
        ChannelList(items, listState, viewModel, onChannelClick, focusChannel, onFocusHandled)
    }
}
