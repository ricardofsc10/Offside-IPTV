package io.github.ricardofsc10.offsideplayer.ui

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.ricardofsc10.offsideplayer.ui.theme.LocalSectionNavigator
import io.github.ricardofsc10.offsideplayer.ui.theme.Section
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.data.library.EpisodeRow
import io.github.ricardofsc10.offsideplayer.data.library.ProgressKind
import io.github.ricardofsc10.offsideplayer.data.library.ProgressPolicy
import io.github.ricardofsc10.offsideplayer.data.library.VodItem
import io.github.ricardofsc10.offsideplayer.data.library.VodKind
import io.github.ricardofsc10.offsideplayer.data.library.ZapContext
import io.github.ricardofsc10.offsideplayer.data.xtream.MediaKind
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import io.github.ricardofsc10.offsideplayer.ui.channels.ChannelsScreen
import io.github.ricardofsc10.offsideplayer.ui.channels.SearchScreen
import io.github.ricardofsc10.offsideplayer.ui.home.HomeScreen
import io.github.ricardofsc10.offsideplayer.ui.home.StartEntry
import io.github.ricardofsc10.offsideplayer.ui.components.FavoriteMenu
import io.github.ricardofsc10.offsideplayer.ui.home.StartFocus
import io.github.ricardofsc10.offsideplayer.ui.home.StartRefocus
import io.github.ricardofsc10.offsideplayer.ui.components.rememberNow
import io.github.ricardofsc10.offsideplayer.data.epg.EpgProgram
import androidx.compose.runtime.produceState
import io.github.ricardofsc10.offsideplayer.ui.home.StartScreen
import io.github.ricardofsc10.offsideplayer.ui.player.PlayerScreen
import io.github.ricardofsc10.offsideplayer.ui.player.VodPlayRequest
import io.github.ricardofsc10.offsideplayer.ui.player.VodPlayerScreen
import io.github.ricardofsc10.offsideplayer.ui.settings.ManageCategoriesScreen
import io.github.ricardofsc10.offsideplayer.ui.settings.SettingsScreen
import io.github.ricardofsc10.offsideplayer.ui.vod.MovieDetailScreen
import io.github.ricardofsc10.offsideplayer.ui.vod.SeriesDetailScreen
import io.github.ricardofsc10.offsideplayer.ui.vod.VodBrowseScreen
import io.github.ricardofsc10.offsideplayer.ui.vod.episodeTitle
import io.github.ricardofsc10.offsideplayer.ui.vod.seasonLabel
import kotlinx.coroutines.launch

private const val LIVE_LIMIT = 6

private sealed interface Destination {
    data object Start : Destination
    data object Channels : Destination
    data object Settings : Destination
    data object ManageCategories : Destination
    data object Search : Destination
    data class Category(val id: String, val name: String) : Destination
    data class Player(
        val channel: ChannelRow,
        val context: ZapContext,
        val allowedFormats: List<String>,
        val returnTo: Destination,
    ) : Destination
    data class Browse(val kind: VodKind) : Destination
    data class MovieDetail(val streamId: Long) : Destination
    data class SeriesDetail(val seriesId: Long) : Destination
    data class VodPlayer(val request: VodPlayRequest, val returnTo: Destination) : Destination
}

@Composable
fun MainScreen(viewModel: AppViewModel, credentials: XtreamCredentials, openSettings: Boolean, onExit: () -> Unit) {
    var destination by remember { mutableStateOf<Destination>(if (openSettings) Destination.Settings else Destination.Start) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Ficam aqui para cada lista voltar ao mesmo sítio ao regressar.
    var startFocus by remember { mutableStateOf(StartEntry.CHANNELS) }
    val homeListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    var homeFocusKey by remember { mutableStateOf<String?>(null) }
    var categoryId by remember { mutableStateOf<String?>(null) }
    val categoryListState = remember(categoryId) { LazyListState() }
    var focusChannel by remember { mutableStateOf<ChannelRow?>(null) }
    val movieGrid = rememberLazyGridState()
    val seriesGrid = rememberLazyGridState()
    var focusVodItem by remember { mutableStateOf<Long?>(null) }
    var focusEpisode by remember { mutableStateOf<Long?>(null) }
    val continueItems by remember { viewModel.vod.continueWatching() }.collectAsStateWithLifecycle(initialValue = null)
    // Ao abrir a app, o foco começa no primeiro jogo em direto.
    var startFocusMode by remember { mutableStateOf(StartFocus.LIVE) }
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val minute = rememberNow() / 60_000
    val startFavorites by viewModel.library.favoriteChannels.collectAsStateWithLifecycle(initialValue = null)
    val live by produceState<List<Pair<ChannelRow, EpgProgram>>?>(null, guide, minute) {
        value = viewModel.epg.sportNow(guide, System.currentTimeMillis(), LIVE_LIMIT)
    }
    var startMenuFor by remember { mutableStateOf<VodItem?>(null) }
    var startRefocus by remember { mutableStateOf(StartRefocus(0)) }

    fun movieRequest(streamId: Long, title: String, extension: String, startMs: Long?) = VodPlayRequest(
        kind = ProgressKind.MOVIE,
        id = streamId,
        seriesId = null,
        title = title,
        subtitle = null,
        url = viewModel.client.mediaUrl(credentials, MediaKind.MOVIE, streamId, extension),
        startMs = startMs ?: 0L,
    )

    fun openPlayer(channel: ChannelRow, context: ZapContext, returnTo: Destination) {
        scope.launch {
            destination = Destination.Player(channel, context, viewModel.library.allowedFormats(), returnTo)
        }
    }

    fun episodeRequest(seriesName: String, episode: EpisodeRow, startMs: Long?) = VodPlayRequest(
        kind = ProgressKind.EPISODE,
        id = episode.id,
        seriesId = episode.seriesId,
        title = seriesName,
        subtitle = "${seasonLabel(episode.season)} · ${episodeTitle(episode)}",
        url = viewModel.client.mediaUrl(credentials, MediaKind.EPISODE, episode.id, episode.containerExtension),
        startMs = startMs ?: 0L,
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.syncIfDue()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // O menu do topo abre a secção em qualquer ecrã. Ao voltar ao início, o foco fica nela.
    fun openSection(section: Section) {
        startFocusMode = StartFocus.SECTION
        startFocus = section
        destination = when (section) {
            Section.CHANNELS -> Destination.Channels
            Section.MOVIES -> Destination.Browse(VodKind.MOVIE)
            Section.SERIES -> Destination.Browse(VodKind.SERIES)
            Section.SETTINGS -> Destination.Settings
        }
    }

    CompositionLocalProvider(LocalSectionNavigator provides ::openSection) {
    when (val current = destination) {
        Destination.Start -> StartScreen(
            focused = startFocus,
            startFocus = startFocusMode,
            live = live,
            favorites = startFavorites,
            guide = guide,
            guideMissing = guide.isEmpty,
            status = startStatus(viewModel.syncState, viewModel.vodSyncState),
            continueItems = continueItems,
            refocus = startRefocus,
            onWatchFavorite = { channel ->
                startFocusMode = StartFocus.SECTION
                openPlayer(channel, ZapContext.Favorites, Destination.Start)
            },
            onWatch = { channel ->
                startFocusMode = StartFocus.LIVE
                val context = channel.categoryId?.let { ZapContext.Category(it) } ?: ZapContext.Favorites
                openPlayer(channel, context, Destination.Start)
            },
            onResume = { item ->
                // Retoma logo no player. Se faltar alguma coisa, abre o detalhe.
                scope.launch {
                    val request = when (item.kind) {
                        VodKind.MOVIE -> viewModel.vod.movie(item.id)?.let { movie ->
                            val progress = viewModel.vod.progress(ProgressKind.MOVIE, item.id)
                            val start = ProgressPolicy.resumePositionMs(progress?.positionMs, progress?.durationMs, progress?.finished)
                            movieRequest(movie.streamId, movie.name, movie.containerExtension, start)
                        }
                        VodKind.SERIES -> viewModel.vod.continueEpisode(item.id)?.let { episode ->
                            val start = ProgressPolicy.resumePositionMs(episode.positionMs, episode.durationMs, episode.finished)
                            episodeRequest(item.title, episode, start)
                        }
                    }
                    destination = when {
                        request != null -> Destination.VodPlayer(request, Destination.Start)
                        item.kind == VodKind.MOVIE -> Destination.MovieDetail(item.id)
                        else -> Destination.SeriesDetail(item.id)
                    }
                }
            },
            onLongPress = { startMenuFor = it },
            onOpen = ::openSection,
            onExit = onExit,
        )
        Destination.Channels -> {
            BackHandler { destination = Destination.Start }
            HomeScreen(
                viewModel = viewModel,
                listState = homeListState,
                focusKey = homeFocusKey,
                onFocusKeyChange = { homeFocusKey = it },
                onChannelClick = { openPlayer(it, ZapContext.Favorites, Destination.Channels) },
                // O zapping a partir de "Desporto agora" percorre a categoria do canal.
                onSportChannelClick = { channel ->
                    val context = channel.categoryId?.let { ZapContext.Category(it) } ?: ZapContext.Favorites
                    openPlayer(channel, context, Destination.Channels)
                },
                onOpenCategory = { category ->
                    categoryId = category.id
                    focusChannel = null
                    viewModel.openCategory(category.id)
                    destination = Destination.Category(category.id, category.name)
                },
                onOpenSearch = {
                    focusChannel = null
                    viewModel.setSearchQuery("")
                    destination = Destination.Search
                },
            )
        }
        is Destination.Category -> {
            BackHandler { destination = Destination.Channels }
            ChannelsScreen(
                title = current.name,
                viewModel = viewModel,
                listState = categoryListState,
                focusChannel = focusChannel,
                onFocusHandled = { focusChannel = null },
                onChannelClick = { openPlayer(it, ZapContext.Category(current.id), current) },
            )
        }
        Destination.Search -> {
            BackHandler { destination = Destination.Channels }
            SearchScreen(
                viewModel = viewModel,
                listState = searchListState,
                focusChannel = focusChannel,
                onFocusHandled = { focusChannel = null },
                onChannelClick = { channel ->
                    openPlayer(channel, ZapContext.Search(viewModel.searchQuery.value), Destination.Search)
                },
            )
        }
        is Destination.Player -> settings?.let { playerSettings ->
            PlayerScreen(
                viewModel = viewModel,
                credentials = credentials,
                context = current.context,
                initialChannel = current.channel,
                allowedFormats = current.allowedFormats,
                settings = playerSettings,
                onExit = { channel ->
                    // Volta à lista com o foco no canal que estava a ver.
                    focusChannel = channel
                    // Depois do zapping nos favoritos, o foco vai para o canal onde ficou.
                    val fromFavorites = current.context == ZapContext.Favorites && homeFocusKey?.startsWith("favorite-") == true
                    homeFocusKey = if (current.returnTo == Destination.Channels && fromFavorites) "favorite-${channel.streamId}" else homeFocusKey
                    destination = current.returnTo
                },
            )
        }
        is Destination.Browse -> {
            BackHandler { destination = Destination.Start }
            val movies = current.kind == VodKind.MOVIE
            VodBrowseScreen(
                browser = if (movies) viewModel.movieBrowser else viewModel.seriesBrowser,
                title = if (movies) tr(R.string.tab_movies) else tr(R.string.tab_series),
                gridState = if (movies) movieGrid else seriesGrid,
                focusItemId = focusVodItem,
                onFocusHandled = { focusVodItem = null },
                onOpen = { item ->
                    focusVodItem = item.id
                    focusEpisode = null
                    destination = if (movies) Destination.MovieDetail(item.id) else Destination.SeriesDetail(item.id)
                },
                onToggleFavorite = { item -> viewModel.setVodFavorite(item, !item.favorite) },
                onRemoveFromContinue = viewModel::removeFromContinue,
            )
        }
        is Destination.MovieDetail -> {
            BackHandler { destination = Destination.Browse(VodKind.MOVIE) }
            MovieDetailScreen(
                vod = viewModel.vod,
                credentials = credentials,
                streamId = current.streamId,
                onPlay = { movie ->
                    val request = movieRequest(movie.streamId, movie.title, movie.containerExtension, movie.startMs)
                    destination = Destination.VodPlayer(request, current)
                },
            )
        }
        is Destination.SeriesDetail -> {
            BackHandler { destination = Destination.Browse(VodKind.SERIES) }
            SeriesDetailScreen(
                vod = viewModel.vod,
                credentials = credentials,
                seriesId = current.seriesId,
                focusEpisodeId = focusEpisode,
                onPlay = { episode, startMs ->
                    scope.launch {
                        val name = viewModel.vod.series(current.seriesId)?.name.orEmpty()
                        destination = Destination.VodPlayer(episodeRequest(name, episode, startMs), current)
                    }
                },
            )
        }
        is Destination.VodPlayer -> settings?.let { playerSettings ->
            VodPlayerScreen(
                http = viewModel.http,
                request = current.request,
                frameRateMode = playerSettings.frameRate,
                videoFit = playerSettings.videoFit,
                onVideoFit = { viewModel.updateSettings(playerSettings.copy(videoFit = it)) },
                onSaveProgress = { request, position, duration ->
                    viewModel.saveProgress(request.kind, request.id, request.seriesId, position, duration)
                },
                nextEpisode = { playing ->
                    val seriesId = playing.seriesId ?: return@VodPlayerScreen null
                    val next = viewModel.vod.nextEpisode(seriesId, playing.id) ?: return@VodPlayerScreen null
                    val resume = ProgressPolicy.resumePositionMs(next.positionMs, next.durationMs, next.finished)
                    episodeRequest(playing.title, next, resume)
                },
                onExit = { last ->
                    // Na série, o foco volta ao último episódio visto, que pode ser outro depois do seguinte.
                    if (last.kind == ProgressKind.EPISODE) focusEpisode = last.id
                    // Vindo do início, o foco volta à fila "Continuar a ver".
                    if (current.returnTo == Destination.Start) startFocusMode = StartFocus.CONTINUE
                    destination = current.returnTo
                },
            )
        }
        Destination.Settings -> {
            BackHandler { destination = Destination.Start }
            SettingsScreen(viewModel, onManageCategories = { destination = Destination.ManageCategories })
        }
        Destination.ManageCategories -> {
            BackHandler { destination = Destination.Settings }
            ManageCategoriesScreen(viewModel)
        }
    }
    }

    startMenuFor?.let { item ->
        FavoriteMenu(
            title = item.title,
            favorite = item.favorite,
            onToggleFavorite = {
                viewModel.setVodFavorite(item, !item.favorite)
                startMenuFor = null
                startRefocus = StartRefocus(startRefocus.tick + 1)
            },
            onDismiss = {
                startMenuFor = null
                startRefocus = StartRefocus(startRefocus.tick + 1)
            },
            onRemoveFromContinue = {
                viewModel.removeFromContinue(item)
                startMenuFor = null
                startRefocus = StartRefocus(startRefocus.tick + 1, "${item.kind}-${item.id}")
            },
        )
    }
}

/** Estado das sincronizações para o ecrã de Início. Os canais têm prioridade. */
private fun startStatus(channels: SyncState, vod: SyncState): String? = when {
    channels is SyncState.Running -> tr(R.string.sync_channels_running, channels.channels)
    channels is SyncState.Failed -> tr(R.string.sync_channels_failed, channels.message)
    vod is SyncState.Running -> tr(R.string.sync_vod_running, vod.channels)
    vod is SyncState.Failed -> tr(R.string.sync_vod_failed, vod.message)
    else -> null
}

/**
 * Dá foco a um elemento quando [ready] fica verdadeiro ou quando [trigger] muda.
 * As listas só colocam os itens depois de um frame.
 */
@Composable
fun RequestInitialFocus(requester: FocusRequester, ready: Boolean = true, trigger: Any? = null) {
    LaunchedEffect(ready, trigger) {
        if (ready) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
}
