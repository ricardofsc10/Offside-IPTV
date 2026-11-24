package io.github.ricardofsc10.offsideplayer.ui

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import io.github.ricardofsc10.offsideplayer.diagnostics.Diagnostics
import android.app.Application
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.ricardofsc10.offsideplayer.data.accounts.Account
import io.github.ricardofsc10.offsideplayer.data.epg.EpgException
import io.github.ricardofsc10.offsideplayer.data.epg.EpgGuide
import io.github.ricardofsc10.offsideplayer.data.epg.EpgRepository
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.data.library.LibraryDatabase
import io.github.ricardofsc10.offsideplayer.data.library.LibraryRepository
import io.github.ricardofsc10.offsideplayer.data.library.ProgressKind
import io.github.ricardofsc10.offsideplayer.data.library.VodItem
import io.github.ricardofsc10.offsideplayer.data.library.VodKind
import io.github.ricardofsc10.offsideplayer.data.library.VodRepository
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamClient
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamException
import io.github.ricardofsc10.offsideplayer.ui.vod.VodBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed interface EpgState {
    data object Off : EpgState
    data object Running : EpgState
    data class Ready(val linked: Int, val importedAt: Long) : EpgState
    data class Failed(val message: String) : EpgState
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val channels: Int) : SyncState
    /** [retryOnNetwork] indica uma falha de rede, que se repete sozinha quando a rede volta. */
    data class Failed(val message: String, val retryOnNetwork: Boolean = false) : SyncState
}

private const val SEARCH_DEBOUNCE_MS = 250L
internal const val STATS_TAG = "OffsideStats"
private const val CATEGORY_RELOAD_MARGIN = 10
private const val EPG_URL_DEBOUNCE_MS = 3_000L

/**
 * Tudo o que pertence a uma conta: a base de dados, a sincronização, o guia e as listas. Trocar de
 * conta fecha esta sessão e abre outra, sem misturar dados.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AccountSession(
    application: Application,
    account: Account,
    parentScope: CoroutineScope,
    http: OkHttpClient,
    val client: XtreamClient,
) {
    var account: Account = account
        private set
    val credentials get() = account.credentials

    /** Um filho do scope do ViewModel, que se cancela quando a sessão fecha. */
    val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private val database = LibraryDatabase.create(application, account.databaseName)
    val library = LibraryRepository(database, client)
    val vod = VodRepository(database, client)
    val movieBrowser = VodBrowser(vod, VodKind.MOVIE, scope)
    val seriesBrowser = VodBrowser(vod, VodKind.SERIES, scope)

    val epg = EpgRepository(database, http, { Xml.newPullParser() })
    private val _guide = MutableStateFlow(EpgGuide.EMPTY)
    /** O guia em memória, que as listas de canais e o player leem. */
    val guide: StateFlow<EpgGuide> = _guide
    var epgState by mutableStateOf<EpgState>(EpgState.Off)
        private set
    private var epgJob: Job? = null
    private val epgUrl = MutableStateFlow(account.epgUrl)

    var syncState by mutableStateOf<SyncState>(SyncState.Idle)
        private set

    /** Filmes e séries sincronizam à parte, depois dos canais, e uma falha não toca nos canais. */
    var vodSyncState by mutableStateOf<SyncState>(SyncState.Idle)
        private set
    private var vodSyncJob: Job? = null

    private data class CategoryRequest(val id: String, val initialIndex: Int?)

    private val categoryRequest = MutableStateFlow<CategoryRequest?>(null)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // O PagingData vazio limpa a lista anterior, para não aparecer por instantes na nova.
    val categoryChannels: Flow<PagingData<ChannelRow>> = categoryRequest
        .flatMapLatest { request ->
            flow {
                emit(PagingData.empty())
                if (request != null) emitAll(library.categoryChannels(request.id, request.initialIndex))
            }
        }
        .cachedIn(scope)

    val searchResults: Flow<PagingData<ChannelRow>> = _searchQuery
        .debounce(SEARCH_DEBOUNCE_MS)
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isEmpty()) flowOf(PagingData.empty()) else library.searchChannels(query)
        }
        .cachedIn(scope)

    init {
        scope.launch {
            reloadGuide()
            if (epg.info() != null) Diagnostics.note(STATS_TAG, epg.coverageLine())
        }
        // Cada tecla no campo do endereço grava a conta. Só se descarrega quando se para de escrever.
        scope.launch {
            epgUrl
                .drop(1)
                .debounce(EPG_URL_DEBOUNCE_MS)
                .collect { refreshEpg(force = false) }
        }
    }

    /** Chamado quando a conta muda sem trocar de base de dados, por exemplo uma password nova. */
    fun updateAccount(updated: Account) {
        account = updated
        epgUrl.value = updated.epgUrl
    }

    fun close() {
        scope.cancel()
        database.close()
    }

    fun openCategory(id: String) {
        categoryRequest.value = CategoryRequest(id, null)
    }

    /** Depois do zapping, o canal atual pode estar fora das páginas carregadas. */
    fun reloadCategoryAround(channel: ChannelRow) {
        val id = channel.categoryId ?: return
        scope.launch {
            val index = library.indexInCategory(id, channel.position)
            categoryRequest.value = CategoryRequest(id, (index - CATEGORY_RELOAD_MARGIN).coerceAtLeast(0))
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFavorite(streamId: Long, favorite: Boolean) {
        scope.launch {
            if (favorite) library.addFavorite(streamId) else library.removeFavorite(streamId)
        }
    }

    fun retryAfterNetwork() {
        scope.launch {
            if ((syncState as? SyncState.Failed)?.retryOnNetwork == true) sync()
            if ((vodSyncState as? SyncState.Failed)?.retryOnNetwork == true) startVodSync()
        }
    }

    private suspend fun reloadGuide() {
        _guide.value = epg.loadGuide()
        val info = epg.info()
        if (epgState !is EpgState.Running && info != null && epgUrl.value.isNotBlank()) {
            epgState = EpgState.Ready(info.linked, info.importedAt)
        }
    }

    /** Descarrega o guia se o endereço mudou ou se passaram 12 horas. Com [force], sempre. */
    fun refreshEpg(force: Boolean) {
        if (epgJob?.isActive == true) return
        epgJob = scope.launch {
            val url = epgUrl.value
            if (url.isBlank()) {
                epg.clear()
                _guide.value = EpgGuide.EMPTY
                epgState = EpgState.Off
                return@launch
            }
            if (!force && !epg.isRefreshDue(url)) return@launch
            epgState = EpgState.Running
            epgState = try {
                val report = epg.refresh(url)
                Diagnostics.note(STATS_TAG, report.logLine())
                Diagnostics.note(STATS_TAG, epg.coverageLine())
                _guide.value = epg.loadGuide()
                val info = epg.info()
                EpgState.Ready(info?.linked ?: 0, info?.importedAt ?: 0L)
            } catch (e: EpgException) {
                // A mensagem nunca tem o endereço.
                Diagnostics.note(STATS_TAG, "epgSync failed reason=${e.message}")
                EpgState.Failed(epgMessage(e.message))
            }
        }
    }

    /** Chamado sempre que a app volta a primeiro plano: a lista aparece logo e atualiza por trás. */
    fun syncIfDue() {
        scope.launch {
            if (library.isSyncDue()) sync()
            if (vod.isSyncDue()) startVodSync()
            refreshEpg(force = false)
        }
    }

    fun syncNow() {
        scope.launch {
            sync()
            startVodSync()
            refreshEpg(force = true)
        }
    }

    fun startVodSync() {
        if (vodSyncJob?.isActive == true) return
        vodSyncJob = scope.launch {
            vodSyncState = SyncState.Running(0)
            vodSyncState = try {
                val report = vod.sync(credentials) { items -> vodSyncState = SyncState.Running(items) }
                // Só números, para medir com a conta real sem expor títulos.
                Diagnostics.note(STATS_TAG, report.logLine())
                SyncState.Idle
            } catch (e: XtreamException) {
                Diagnostics.note(STATS_TAG, "vodSync failed reason=${e.javaClass.simpleName}")
                SyncState.Failed(
                    e.userMessage(),
                    retryOnNetwork = e is XtreamException.Network || e is XtreamException.Timeout,
                )
            }
        }
    }

    /**
     * A primeira sincronização de uma conta nova. Ao contrário de [sync], deixa passar o erro, para
     * o formulário o mostrar e a conta não ficar guardada.
     */
    suspend fun firstSync(onProgress: (Int) -> Unit) {
        val report = library.sync(credentials, onProgress)
        Diagnostics.note(STATS_TAG, report.logLine())
    }

    private suspend fun sync() {
        if (syncState is SyncState.Running) return
        syncState = SyncState.Running(0)
        syncState = try {
            val report = library.sync(credentials) { channels -> syncState = SyncState.Running(channels) }
            Diagnostics.note(STATS_TAG, report.logLine())
            // Os canais podem ter mudado de nome ou de ID do guia.
            if (epg.info() != null) {
                val link = epg.relink()
                Diagnostics.note(STATS_TAG, "epgLink byId=${link.byId} byName=${link.byName} unlinked=${link.unlinked}")
                Diagnostics.note(STATS_TAG, epg.coverageLine())
                reloadGuide()
            }
            SyncState.Idle
        } catch (e: XtreamException) {
            Diagnostics.note(STATS_TAG, "sync failed reason=${e.javaClass.simpleName}")
            SyncState.Failed(
                e.userMessage(),
                retryOnNetwork = e is XtreamException.Network || e is XtreamException.Timeout,
            )
        }
    }

    fun setVodFavorite(item: VodItem, favorite: Boolean) {
        scope.launch { vod.setFavorite(item.kind, item.id, favorite) }
    }

    fun removeFromContinue(item: VodItem) {
        scope.launch { vod.removeFromContinue(item.kind, item.id) }
    }

    fun setVodCategoryHidden(kind: VodKind, id: String, hidden: Boolean) {
        scope.launch { vod.setCategoryHidden(kind, id, hidden) }
    }

    /** No scope da sessão, para a última gravação não se perder quando o player fecha. */
    fun saveProgress(kind: ProgressKind, id: Long, seriesId: Long?, positionMs: Long, durationMs: Long) {
        scope.launch { vod.saveProgress(kind, id, seriesId, positionMs, durationMs) }
    }

    fun setCategoryHidden(id: String, hidden: Boolean) {
        scope.launch { library.setCategoryHidden(id, hidden) }
    }

    private fun epgMessage(reason: String?): String = when {
        reason == null -> tr(R.string.epg_err_unknown)
        reason.startsWith("network") -> tr(R.string.epg_err_network)
        reason.startsWith("http") -> tr(R.string.epg_err_http, reason.removePrefix("http "))
        reason == "invalid address" -> tr(R.string.epg_err_invalid)
        else -> tr(R.string.epg_err_format)
    }
}

fun XtreamException.userMessage(): String = when (this) {
    is XtreamException.Network -> tr(R.string.err_network)
    is XtreamException.Timeout -> tr(R.string.err_timeout)
    is XtreamException.InvalidCredentials -> tr(R.string.err_credentials)
    is XtreamException.AccountInactive -> tr(R.string.err_inactive, status)
    is XtreamException.ServerError -> tr(R.string.err_server, code)
    is XtreamException.MalformedResponse -> tr(R.string.err_malformed)
    is XtreamException.EmptyLibrary -> tr(R.string.err_empty)
}
