package io.github.ricardofsc10.offsideplayer.ui.vod

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import io.github.ricardofsc10.offsideplayer.ui.theme.Tag
import io.github.ricardofsc10.offsideplayer.ui.theme.Section
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.ProgressRail
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.BroadcastHeader
import io.github.ricardofsc10.offsideplayer.ui.theme.BarShape
import io.github.ricardofsc10.offsideplayer.ui.theme.BarListItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.library.VodItem
import io.github.ricardofsc10.offsideplayer.data.library.VodKind
import io.github.ricardofsc10.offsideplayer.data.library.VodShelf
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.components.Artwork
import io.github.ricardofsc10.offsideplayer.ui.components.FavoriteMenu
import io.github.ricardofsc10.offsideplayer.ui.components.TvTextField
import kotlinx.coroutines.delay

private const val SHELF_FOCUS_DELAY_MS = 300L
private const val GRID_COLUMNS = 5
private const val TOP_SHELVES = 6

/**
 * Filmes ou séries: prateleiras à esquerda e capas à direita.
 *
 * @param focusItemId ao voltar do detalhe, o foco vai para esta capa.
 */
@Composable
fun VodBrowseScreen(
    browser: VodBrowser,
    title: String,
    gridState: LazyGridState,
    focusItemId: Long?,
    onFocusHandled: () -> Unit,
    onOpen: (VodItem) -> Unit,
    onToggleFavorite: (VodItem) -> Unit,
    onRemoveFromContinue: (VodItem) -> Unit,
) {
    val shelf by browser.shelf.collectAsStateWithLifecycle()
    val categories by browser.categories.collectAsStateWithLifecycle(initialValue = null)
    val query by browser.query.collectAsStateWithLifecycle()
    val items = browser.items.collectAsLazyPagingItems()
    val focusManager = LocalFocusManager.current

    val shelves = remember(categories) {
        listOf(VodShelf.Search, VodShelf.Continue, VodShelf.Favorites) +
            categories.orEmpty().map { VodShelf.Category(it.id, it.name) }
    }
    // Na primeira visita, abre a primeira categoria. Depois, fica a última escolha.
    LaunchedEffect(categories) {
        val loaded = categories ?: return@LaunchedEffect
        if (browser.shelf.value == null) {
            browser.select(loaded.firstOrNull()?.let { VodShelf.Category(it.id, it.name) } ?: VodShelf.Search)
        }
    }

    // A grelha só muda quando o foco para numa prateleira, para não recarregar a cada tecla.
    var focusedShelf by remember { mutableStateOf<VodShelf?>(null) }
    val shelfList = rememberLazyListState()
    // Nas prateleiras de cima, a lista fica no início. Sem isto, o foco deslizava-a um pouco e
    // "Pesquisar" ficava meio cortado debaixo do cabeçalho.
    LaunchedEffect(focusedShelf) {
        val index = shelves.indexOf(focusedShelf)
        if (index in 0 until TOP_SHELVES) {
            withFrameNanos { }
            shelfList.scrollToItem(0)
        }
    }
    LaunchedEffect(focusedShelf) {
        val target = focusedShelf ?: return@LaunchedEffect
        delay(SHELF_FOCUS_DELAY_MS)
        browser.select(target)
    }

    val shelfFocus = remember { FocusRequester() }
    val returning = remember { focusItemId != null }
    RequestInitialFocus(shelfFocus, ready = !returning && shelf != null && categories != null)

    var menuFor by remember { mutableStateOf<VodItem?>(null) }
    // Ao fechar o menu, o foco volta à capa. Sem isto, perdia-se e a tecla seguinte só o recuperava.
    var refocusId by remember { mutableStateOf<Long?>(null) }
    // Uma capa tirada da lista aberta, ao remover um favorito em "Favoritos" ou em "Continuar a ver".
    // Quando sai, o foco vai para a primeira capa, ou para a prateleira se a lista ficou vazia.
    // Sem isto, o foco caía em "Pesquisar" e a grelha mudava de prateleira.
    var removedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(removedId, items.itemCount) {
        val gone = removedId ?: return@LaunchedEffect
        if (items.itemSnapshotList.any { it?.id == gone }) return@LaunchedEffect
        if (items.itemCount == 0) {
            withFrameNanos { }
            runCatching { shelfFocus.requestFocus() }
        } else {
            refocusId = gone
        }
        // Só no fim: limpar antes reiniciava este efeito e cancelava o pedido de foco.
        removedId = null
    }

    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding)) {
    val headerFocus = remember { FocusRequester() }
    BroadcastHeader(
        current = if (browser.kind == VodKind.MOVIE) Section.MOVIES else Section.SERIES,
        groupRequester = headerFocus,
    )
    Row(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        Column(modifier = Modifier.width(210.dp).fillMaxHeight()) {
            // Ao voltar da grelha com ◀, o foco vai para a prateleira escolhida e não para a que
            // estiver à mesma altura da capa.
            LazyColumn(
                state = shelfList,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(top = 4.dp),
                modifier = Modifier.focusRestorer(shelfFocus),
            ) {
                items(shelves, key = ::shelfKey) { entry ->
                    BarListItem(
                        selected = entry == shelf,
                        onClick = {
                            browser.select(entry)
                            focusManager.moveFocus(FocusDirection.Right)
                        },
                        headlineContent = { Text(shelfLabel(entry), maxLines = 1) },
                        modifier = Modifier
                            .then(if (entry == shelf) Modifier.focusRequester(shelfFocus) else Modifier)
                            // Por cima da primeira prateleira está o menu. Sem isto, o ▲ ia para a
                            // caixa de pesquisa, que fica um pouco mais acima, à direita.
                            .then(if (entry == VodShelf.Search) Modifier.focusProperties { up = headerFocus } else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedShelf = entry },
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(start = 24.dp)) {
            if (shelf == VodShelf.Search) {
                TvTextField(
                    label = if (browser.kind == VodKind.MOVIE) tr(R.string.search_movie) else tr(R.string.search_series),
                    value = query,
                    onValueChange = browser::setQuery,
                    imeAction = ImeAction.Search,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            val empty = items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading
            if (empty && shelf != null) {
                Text(
                    emptyMessage(shelf!!, browser.kind, query),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Pal.Muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            VodGrid(
                items = items,
                gridState = gridState,
                focusItemId = focusItemId ?: refocusId,
                onFocusHandled = {
                    refocusId = null
                    onFocusHandled()
                },
                onOpen = onOpen,
                onLongPress = { menuFor = it },
            )
        }
    }
    }

    menuFor?.let { item ->
        FavoriteMenu(
            title = item.title,
            favorite = item.favorite,
            onToggleFavorite = {
                onToggleFavorite(item)
                menuFor = null
                refocusId = item.id
                if (shelf == VodShelf.Favorites && item.favorite) removedId = item.id
            },
            onDismiss = {
                menuFor = null
                refocusId = item.id
            },
            onRemoveFromContinue = if (shelf == VodShelf.Continue) {
                {
                    onRemoveFromContinue(item)
                    menuFor = null
                    refocusId = item.id
                    removedId = item.id
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun VodGrid(
    items: androidx.paging.compose.LazyPagingItems<VodItem>,
    gridState: LazyGridState,
    focusItemId: Long?,
    onFocusHandled: () -> Unit,
    onOpen: (VodItem) -> Unit,
    onLongPress: (VodItem) -> Unit,
) {
    val itemFocus = remember { FocusRequester() }
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(focusItemId, items.itemCount) {
        val target = focusItemId ?: return@LaunchedEffect
        if (items.itemCount == 0) return@LaunchedEffect
        val index = items.itemSnapshotList.indexOfFirst { it?.id == target }
        if (index >= 0) {
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index)
            withFrameNanos { }
            runCatching { itemFocus.requestFocus() }
        } else {
            // A capa saiu da lista, por exemplo ao remover um favorito em "Favoritos".
            withFrameNanos { }
            runCatching { firstItem.requestFocus() }
        }
        onFocusHandled()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(4.dp),
        // Ao entrar pela esquerda, o foco vai para a primeira capa, ou para a última que teve foco.
        // Sem isto, ia para a capa mais próxima na vertical, a meio da grelha.
        modifier = Modifier.fillMaxSize().focusRestorer(firstItem),
    ) {
        items(count = items.itemCount, key = items.itemKey { "${it.kind}-${it.id}" }) { index ->
            val item = items[index] ?: return@items
            VodCard(
                item = item,
                onClick = { onOpen(item) },
                onLongClick = { onLongPress(item) },
                modifier = when {
                    item.id == focusItemId -> Modifier.focusRequester(itemFocus)
                    index == 0 -> Modifier.focusRequester(firstItem)
                    else -> Modifier
                },
            )
        }
    }
}

@Composable
fun VodCard(item: VodItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        var focused by remember { mutableStateOf(false) }
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = CardDefaults.shape(BarShape),
            scale = CardDefaults.scale(focusedScale = 1f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Pal.Green), inset = 0.dp, shape = BarShape),
            ),
            colors = CardDefaults.colors(containerColor = Pal.Bar2, focusedContainerColor = Pal.Bar2),
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Artwork(url = item.artworkUrl, title = item.title, modifier = Modifier.fillMaxSize())
                if (item.favorite) Tag(tr(R.string.tag_favorite), color = Pal.Bar2, modifier = Modifier.align(Alignment.TopStart))
                item.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter)) }
            }
        }
        Text(
            item.title,
            maxLines = 2,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) Pal.Green else Pal.Text,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    ProgressRail(fraction, modifier.fillMaxWidth(), track = Pal.Ink.copy(alpha = 0.7f), height = 4.dp)
}

private fun shelfKey(shelf: VodShelf): String = when (shelf) {
    VodShelf.Search -> "search"
    VodShelf.Continue -> "continue"
    VodShelf.Favorites -> "favorites"
    is VodShelf.Category -> "category-${shelf.id}"
}

private fun shelfLabel(shelf: VodShelf): String = when (shelf) {
    VodShelf.Search -> tr(R.string.search)
    VodShelf.Continue -> tr(R.string.continue_watching)
    VodShelf.Favorites -> tr(R.string.favorites)
    is VodShelf.Category -> shelf.name
}

private fun emptyMessage(shelf: VodShelf, kind: VodKind, query: String): String {
    val movies = kind == VodKind.MOVIE
    return when (shelf) {
        VodShelf.Search -> if (query.isBlank()) tr(R.string.search_name_hint) else tr(R.string.no_results)
        VodShelf.Continue -> if (movies) tr(R.string.movies_in_progress_none) else tr(R.string.series_in_progress_none)
        VodShelf.Favorites -> if (movies) {
            tr(R.string.favorite_movies_none)
        } else {
            tr(R.string.favorite_series_none)
        }
        is VodShelf.Category -> tr(R.string.category_empty)
    }
}
