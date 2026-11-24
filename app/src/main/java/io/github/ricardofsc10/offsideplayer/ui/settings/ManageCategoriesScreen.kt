package io.github.ricardofsc10.offsideplayer.ui.settings

import io.github.ricardofsc10.offsideplayer.ui.text.trPlural
import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import io.github.ricardofsc10.offsideplayer.ui.theme.Section
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.BroadcastHeader
import io.github.ricardofsc10.offsideplayer.ui.theme.BarButton
import io.github.ricardofsc10.offsideplayer.ui.theme.BarListItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.library.VodKind
import io.github.ricardofsc10.offsideplayer.ui.AppViewModel
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus

private enum class CategoryTab(@StringRes private val labelRes: Int, @PluralsRes private val countRes: Int) {
    CHANNELS(R.string.tab_channels, R.plurals.channels_count),
    MOVIES(R.string.tab_movies, R.plurals.movies_count),
    SERIES(R.string.tab_series, R.plurals.series_count),
    ;

    val label: String get() = tr(labelRes)

    fun count(n: Int): String = trPlural(countRes, n)
}

/** Uma linha da lista, igual para os três tipos. */
private data class CategoryLine(val id: String, val name: String, val count: Int, val hidden: Boolean)

@Composable
fun ManageCategoriesScreen(viewModel: AppViewModel) {
    var tab by remember { mutableStateOf(CategoryTab.CHANNELS) }
    val channels by viewModel.library.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val movies by viewModel.vod.movieCategories.collectAsStateWithLifecycle(initialValue = emptyList())
    val series by viewModel.vod.seriesCategories.collectAsStateWithLifecycle(initialValue = emptyList())

    val lines = when (tab) {
        CategoryTab.CHANNELS -> channels.map { CategoryLine(it.id, it.name, it.channelCount, it.hidden) }
        CategoryTab.MOVIES -> movies.map { CategoryLine(it.id, it.name, it.itemCount, it.hidden) }
        CategoryTab.SERIES -> series.map { CategoryLine(it.id, it.name, it.itemCount, it.hidden) }
    }

    fun toggle(line: CategoryLine) = when (tab) {
        CategoryTab.CHANNELS -> viewModel.setCategoryHidden(line.id, !line.hidden)
        CategoryTab.MOVIES -> viewModel.setVodCategoryHidden(VodKind.MOVIE, line.id, !line.hidden)
        CategoryTab.SERIES -> viewModel.setVodCategoryHidden(VodKind.SERIES, line.id, !line.hidden)
    }

    val tabFocus = remember { FocusRequester() }
    RequestInitialFocus(tabFocus)

    // O cabeçalho fica fixo e só a lista desliza, para o relógio nunca sair do ecrã.
    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding)) {
        BroadcastHeader(current = Section.SETTINGS)
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
    ) {
        item {
            Text(
                tr(R.string.manage_categories),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 10.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                CategoryTab.entries.forEach { entry ->
                    BarButton(
                        onClick = { tab = entry },
                        selected = entry == tab,
                        modifier = if (entry == tab) Modifier.focusRequester(tabFocus) else Modifier,
                    ) { Text(entry.label) }
                }
            }
        }
        if (lines.isEmpty()) {
            item { Text(tr(R.string.categories_none), style = MaterialTheme.typography.bodyLarge) }
        }
        items(lines, key = { "${tab.name}-${it.id}" }) { line ->
            BarListItem(
                selected = false,
                onClick = { toggle(line) },
                headlineContent = { Text(line.name, maxLines = 1) },
                supportingContent = { Text(tab.count(line.count)) },
                trailingContent = { Text(if (line.hidden) tr(R.string.hidden) else tr(R.string.visible), style = MaterialTheme.typography.titleMedium) },
            )
        }
    }
    }
}
