package io.github.ricardofsc10.offsideplayer.ui.vod

import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.ricardofsc10.offsideplayer.data.library.VodCategorySummary
import io.github.ricardofsc10.offsideplayer.data.library.VodItem
import io.github.ricardofsc10.offsideplayer.data.library.VodKind
import io.github.ricardofsc10.offsideplayer.data.library.VodRepository
import io.github.ricardofsc10.offsideplayer.data.library.VodShelf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * Estado da grelha de filmes ou de séries. Vive no ViewModel, para a grelha voltar ao mesmo
 * sítio ao regressar do detalhe ou do player.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class VodBrowser(private val vod: VodRepository, val kind: VodKind, scope: CoroutineScope) {
    private val _shelf = MutableStateFlow<VodShelf?>(null)
    val shelf: StateFlow<VodShelf?> = _shelf

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val categories: Flow<List<VodCategorySummary>> = when (kind) {
        VodKind.MOVIE -> vod.movieCategories
        VodKind.SERIES -> vod.seriesCategories
    }.map { all -> all.filterNot { it.hidden } }

    // O texto só conta para a pesquisa. Noutras prateleiras, mudar o texto não recarrega a grelha.
    private val searchQuery = _query.debounce(SEARCH_DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged()

    val items: Flow<PagingData<VodItem>> = combine(_shelf, searchQuery) { shelf, query ->
        shelf to (if (shelf == VodShelf.Search) query else "")
    }
        .distinctUntilChanged()
        .flatMapLatest { (shelf, query) ->
            flow {
                // A grelha vazia primeiro, para a anterior não aparecer por instantes.
                emit(PagingData.empty())
                if (shelf != null) emitAll(vod.items(kind, shelf, query))
            }
        }
        .cachedIn(scope)

    fun select(shelf: VodShelf) {
        _shelf.value = shelf
    }

    fun setQuery(query: String) {
        _query.value = query
    }
}
