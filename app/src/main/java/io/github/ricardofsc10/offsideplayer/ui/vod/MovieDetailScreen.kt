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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.library.MovieEntity
import io.github.ricardofsc10.offsideplayer.data.library.PlaybackProgressEntity
import io.github.ricardofsc10.offsideplayer.data.library.ProgressKind
import io.github.ricardofsc10.offsideplayer.data.library.ProgressPolicy
import io.github.ricardofsc10.offsideplayer.data.library.VodKind
import io.github.ricardofsc10.offsideplayer.data.library.VodRepository
import io.github.ricardofsc10.offsideplayer.data.xtream.MovieInfo
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamException
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.components.Artwork
import kotlinx.coroutines.launch

/** O que é preciso para abrir um filme no player. */
data class MoviePlayRequest(
    val streamId: Long,
    val title: String,
    val containerExtension: String,
    /** Onde começar, ou `null` para o início. */
    val startMs: Long?,
)

@Composable
fun MovieDetailScreen(
    vod: VodRepository,
    credentials: XtreamCredentials,
    streamId: Long,
    onPlay: (MoviePlayRequest) -> Unit,
) {
    val movie by produceState<MovieEntity?>(null, streamId) { value = vod.movie(streamId) }
    // Volta a ler ao regressar do player, para o botão "Continuar" mostrar a posição nova.
    val progress by produceState<PlaybackProgressEntity?>(null, streamId) {
        value = vod.progress(ProgressKind.MOVIE, streamId)
    }
    val info by produceState<MovieInfo?>(null, streamId) {
        value = try {
            vod.movieInfo(credentials, streamId)
        } catch (_: XtreamException) {
            null
        }
    }
    val favorite by remember(streamId) { vod.isFavorite(VodKind.MOVIE, streamId) }
        .collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()

    val current = movie ?: return
    val resumeMs = ProgressPolicy.resumePositionMs(progress?.positionMs, progress?.durationMs, progress?.finished)
    val playFocus = remember { FocusRequester() }
    RequestInitialFocus(playFocus)

    fun play(startMs: Long?) =
        onPlay(MoviePlayRequest(current.streamId, current.name, current.containerExtension, startMs))

    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding)) {
    BroadcastHeader(current = Section.MOVIES)
    Row(
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        modifier = Modifier.fillMaxSize().padding(top = 28.dp),
    ) {
        Artwork(
            url = info?.coverUrl ?: current.posterUrl,
            title = current.name,
            modifier = Modifier.size(width = 220.dp, height = 330.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 600.dp)) {
            Text(current.name, style = MaterialTheme.typography.displaySmall, maxLines = 2)
            metaLine(info)?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelLarge, color = Pal.Green) }
            info?.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Pal.Muted, maxLines = 7) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (resumeMs != null) {
                    BarButton(onClick = { play(resumeMs) }, modifier = Modifier.focusRequester(playFocus)) {
                        Text(tr(R.string.resume_at, formatClock(resumeMs)))
                    }
                    BarButton(onClick = { play(null) }) { Text(tr(R.string.play_from_start)) }
                } else {
                    BarButton(onClick = { play(null) }, modifier = Modifier.focusRequester(playFocus)) { Text(tr(R.string.play)) }
                }
                BarButton(onClick = { scope.launch { vod.setFavorite(VodKind.MOVIE, streamId, !favorite) } }) {
                    Text(if (favorite) tr(R.string.favorite_remove) else tr(R.string.favorite_add))
                }
            }
        }
    }
    }
}

/** Ano, duração e género, os que existirem, separados por pontos. */
private fun metaLine(info: MovieInfo?): String? {
    if (info == null) return null
    val parts = listOfNotNull(
        info.releaseDate?.take(4)?.takeIf { year -> year.all { it.isDigit() } },
        info.durationSeconds?.let(::formatDuration),
        info.genre,
    )
    return parts.joinToString(" · ").ifEmpty { null }
}
