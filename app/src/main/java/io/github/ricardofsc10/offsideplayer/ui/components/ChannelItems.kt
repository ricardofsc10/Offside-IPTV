package io.github.ricardofsc10.offsideplayer.ui.components

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.epg.EpgProgram
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.theme.BarButton
import io.github.ricardofsc10.offsideplayer.ui.theme.BarShape
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.ProgressRail
import io.github.ricardofsc10.offsideplayer.ui.theme.Tag

/**
 * Uma barra por canal: o bloco do número, o logótipo, o nome, o programa atual e o progresso.
 * Com foco, a barra fica verde e o bloco do número passa a tinta.
 */
@Composable
fun ChannelItem(
    channel: ChannelRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** O programa a dar agora, se o canal estiver no guia. */
    program: EpgProgram? = null,
    nowMs: Long = 0L,
    /** Dentro da secção dos favoritos, a etiqueta só repetiria o título da secção. */
    showFavoriteTag: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(BarShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Pal.Bar,
            contentColor = Pal.Text,
            focusedContainerColor = Pal.Green,
            focusedContentColor = Pal.Ink,
            pressedContainerColor = Pal.Green,
            pressedContentColor = Pal.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.fillMaxWidth().height(54.dp).onFocusChanged { focused = it.isFocused },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(54.dp).fillMaxHeight().background(if (focused) Pal.Ink else Pal.Bar2),
            ) {
                Text(
                    (channel.number ?: channel.position + 1).toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (focused) Pal.Text else Pal.Muted,
                )
            }
            if (channel.iconUrl != null) {
                Artwork(
                    url = channel.iconUrl,
                    title = channel.name,
                    contentScale = ContentScale.Fit,
                    opaque = false,
                    initialStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp).size(width = 52.dp, height = 30.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    program?.let {
                        Text(
                            it.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (focused) Pal.Ink else Pal.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (channel.favorite && showFavoriteTag) {
                    Tag(tr(R.string.tag_favorite), color = if (focused) Pal.Ink else Pal.Bar2, contentColor = if (focused) Pal.Text else Pal.Muted)
                }
                program?.let {
                    ProgressRail(
                        it.progress(nowMs),
                        Modifier.width(120.dp),
                        color = if (focused) Pal.Ink else Pal.Green,
                        track = if (focused) Pal.Ink.copy(alpha = 0.25f) else Pal.Line,
                    )
                }
            }
        }
    }
}

/** Abre com uma pressão longa num canal. */
@Composable
fun ChannelMenu(
    channel: ChannelRow,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
) = FavoriteMenu(channel.name, channel.favorite, onToggleFavorite, onDismiss)

/** Abre com uma pressão longa num canal, filme ou série. */
@Composable
fun FavoriteMenu(
    title: String,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    /** Só em "Continuar a ver". */
    onRemoveFromContinue: (() -> Unit)? = null,
) {
    val firstButton = remember { FocusRequester() }
    RequestInitialFocus(firstButton)
    // A tecla da pressão longa continua premida quando o menu abre. As repetições e o largar
    // dessa tecla não podem carregar no botão. Um toque novo tem repeatCount 0 e conta logo.
    var openingPressActive by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .width(360.dp)
                .background(Pal.Bar)
                .onPreviewKeyEvent { event ->
                    val isConfirmKey = event.key == Key.DirectionCenter || event.key == Key.Enter
                    if (!openingPressActive || !isConfirmKey) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        openingPressActive = false
                        return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyUp) openingPressActive = false
                    true
                },
        ) {
            Box(Modifier.fillMaxWidth().background(Pal.Text).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge, color = Pal.Ink)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                BarButton(onClick = onToggleFavorite, modifier = Modifier.fillMaxWidth().focusRequester(firstButton)) {
                    Text(if (favorite) tr(R.string.favorite_remove) else tr(R.string.favorite_mark))
                }
                onRemoveFromContinue?.let {
                    BarButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.continue_remove)) }
                }
                BarButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.cancel)) }
            }
        }
    }
}
