package io.github.ricardofsc10.offsideplayer.ui.vod

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.compose.foundation.layout.widthIn
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.Section
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.BroadcastHeader
import io.github.ricardofsc10.offsideplayer.ui.theme.BarButton
import io.github.ricardofsc10.offsideplayer.ui.theme.BarListItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.library.EpisodeRow
import io.github.ricardofsc10.offsideplayer.data.library.ProgressPolicy
import io.github.ricardofsc10.offsideplayer.data.library.SeriesEntity
import io.github.ricardofsc10.offsideplayer.data.library.VodKind
import io.github.ricardofsc10.offsideplayer.data.library.VodRepository
import io.github.ricardofsc10.offsideplayer.data.library.progressFraction
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamException
import io.github.ricardofsc10.offsideplayer.ui.components.Artwork
import kotlinx.coroutines.launch

/**
 * Temporadas e episódios de uma série.
 *
 * @param focusEpisodeId ao voltar do player, o foco vai para este episódio.
 */
@Composable
fun SeriesDetailScreen(
    vod: VodRepository,
    credentials: XtreamCredentials,
    seriesId: Long,
    focusEpisodeId: Long?,
    onPlay: (EpisodeRow, startMs: Long?) -> Unit,
) {
    val series by produceState<SeriesEntity?>(null, seriesId) { value = vod.series(seriesId) }
    val episodes by remember(seriesId) { vod.episodes(seriesId) }.collectAsStateWithLifecycle(initialValue = null)
    val favorite by remember(seriesId) { vod.isFavorite(VodKind.SERIES, seriesId) }
        .collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(seriesId, reloadTick) {
        loading = true
        loadError = try {
            vod.refreshEpisodesIfStale(credentials, seriesId)
            null
        } catch (_: XtreamException) {
            tr(R.string.episodes_error)
        }
        loading = false
    }

    // O episódio por onde continuar decide a temporada e o foco iniciais.
    val continueId by produceState<Long?>(null, seriesId, episodes) { value = vod.continueEpisodeId(seriesId) }
    val all = episodes.orEmpty()
    val seasons = all.map { it.season }.distinct()
    val startEpisodeId = focusEpisodeId ?: continueId ?: all.firstOrNull()?.id
    var season by remember(seriesId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(seasons, startEpisodeId) {
        if (season == null || season !in seasons) {
            season = all.firstOrNull { it.id == startEpisodeId }?.season ?: seasons.firstOrNull()
        }
    }

    val current = series ?: return
    val episodeFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }
    val episodesList = rememberLazyListState()
    val shown = all.filter { it.season == season }
    var initialFocusDone by remember(seriesId) { mutableStateOf(false) }
    // Ao escolher uma temporada, o foco só passa para os episódios depois de a lista mudar.
    // Antes ia para um episódio da temporada antiga, que desaparecia, e caía no Favorito.
    var focusAfterSeasonChange by remember { mutableStateOf(false) }
    LaunchedEffect(shown, focusAfterSeasonChange) {
        if (!focusAfterSeasonChange || shown.isEmpty()) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == startEpisodeId }.coerceAtLeast(0)
        episodesList.scrollToItem(index)
        withFrameNanos { }
        runCatching { episodeFocus.requestFocus() }
        focusAfterSeasonChange = false
    }
    LaunchedEffect(shown, startEpisodeId) {
        if (initialFocusDone || shown.isEmpty()) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == startEpisodeId }.coerceAtLeast(0)
        episodesList.scrollToItem(index)
        withFrameNanos { }
        runCatching { episodeFocus.requestFocus() }
        initialFocusDone = true
    }
    LaunchedEffect(loadError) {
        if (loadError != null && all.isEmpty()) {
            withFrameNanos { }
            runCatching { retryFocus.requestFocus() }
        }
    }
    val focusTargetId = shown.firstOrNull { it.id == startEpisodeId }?.id ?: shown.firstOrNull()?.id

    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding)) {
        BroadcastHeader(current = Section.SERIES)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 20.dp, bottom = 16.dp)) {
            Artwork(url = current.coverUrl, title = current.name, modifier = Modifier.size(width = 90.dp, height = 135.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 640.dp)) {
                Text(current.name, style = MaterialTheme.typography.headlineLarge, maxLines = 1)
                current.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Pal.Muted, maxLines = 2) }
                BarButton(onClick = { scope.launch { vod.setFavorite(VodKind.SERIES, seriesId, !favorite) } }) {
                    Text(if (favorite) tr(R.string.favorite_remove) else tr(R.string.favorite_add))
                }
            }
        }

        when {
            all.isEmpty() && loading -> Text(tr(R.string.episodes_loading), style = MaterialTheme.typography.bodyLarge)
            all.isEmpty() && loadError != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(loadError!!, style = MaterialTheme.typography.bodyLarge)
                BarButton(onClick = { reloadTick++ }, modifier = Modifier.focusRequester(retryFocus)) { Text(tr(R.string.retry)) }
            }
            all.isEmpty() -> Text(tr(R.string.episodes_none), style = MaterialTheme.typography.bodyLarge)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // Ao voltar dos episódios com ◀, o foco vai para a temporada escolhida e não para a
                // que estiver à mesma altura.
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.width(200.dp).focusRestorer(seasonFocus),
                ) {
                    items(seasons, key = { it }) { number ->
                        BarListItem(
                            modifier = if (number == season) Modifier.focusRequester(seasonFocus) else Modifier,
                            selected = number == season,
                            onClick = {
                                if (number == season) {
                                    focusManager.moveFocus(FocusDirection.Right)
                                } else {
                                    season = number
                                    focusAfterSeasonChange = true
                                }
                            },
                            headlineContent = { Text(seasonLabel(number)) },
                        )
                    }
                }
                LazyColumn(
                    state = episodesList,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(shown, key = { it.id }) { episode ->
                        EpisodeItem(
                            episode = episode,
                            onClick = {
                                onPlay(
                                    episode,
                                    ProgressPolicy.resumePositionMs(episode.positionMs, episode.durationMs, episode.finished),
                                )
                            },
                            modifier = if (episode.id == focusTargetId) Modifier.focusRequester(episodeFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeItem(episode: EpisodeRow, onClick: () -> Unit, modifier: Modifier) {
    BarListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(episodeTitle(episode), maxLines = 1) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val details = listOfNotNull(
                    episode.durationSeconds?.let(::formatDuration),
                    if (episode.finished == true) tr(R.string.watched) else null,
                ).joinToString(" · ")
                if (details.isNotEmpty()) Text(details)
                if (episode.finished != true) {
                    progressFraction(episode.positionMs, episode.durationMs)?.let { ProgressBar(it) }
                }
            }
        },
        modifier = modifier,
    )
}

fun seasonLabel(
    season: Int,
    numbered: (Int) -> String = { tr(R.string.season_n, it) },
    extras: () -> String = { tr(R.string.extras) },
): String = if (season > 0) numbered(season) else extras()

/** Por exemplo "3. O regresso", ou "Episódio 3" sem título. */
fun episodeTitle(
    episode: EpisodeRow,
    numbered: (String) -> String = { tr(R.string.episode_n, it) },
    plain: () -> String = { tr(R.string.episode) },
): String {
    val number = episode.number
    val title = episode.title?.takeUnless { it.isBlank() }
    return when {
        number != null && title != null -> "$number. $title"
        title != null -> title
        number != null -> numbered(number.toString())
        else -> plain()
    }
}
