package io.github.ricardofsc10.offsideplayer.ui.settings

import io.github.ricardofsc10.offsideplayer.ui.text.AppLanguage
import io.github.ricardofsc10.offsideplayer.ui.text.trPlural
import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.ricardofsc10.offsideplayer.ui.theme.Section
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.BroadcastHeader
import io.github.ricardofsc10.offsideplayer.ui.theme.BarButton
import io.github.ricardofsc10.offsideplayer.ui.theme.BarListItem
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.data.accounts.Account
import io.github.ricardofsc10.offsideplayer.data.library.SyncInfoEntity
import io.github.ricardofsc10.offsideplayer.data.library.VodSyncInfoEntity
import io.github.ricardofsc10.offsideplayer.data.settings.FormatPreference
import io.github.ricardofsc10.offsideplayer.data.settings.FrameRateMode
import io.github.ricardofsc10.offsideplayer.data.settings.LatencyMode
import io.github.ricardofsc10.offsideplayer.ui.AppViewModel
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.EpgState
import io.github.ricardofsc10.offsideplayer.ui.SyncState
import io.github.ricardofsc10.offsideplayer.ui.components.TvTextField
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(viewModel: AppViewModel, onManageCategories: () -> Unit) {
    val info by viewModel.library.syncInfo.collectAsStateWithLifecycle(initialValue = null)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val syncState = viewModel.syncState
    val vodInfo by viewModel.vod.syncInfo.collectAsStateWithLifecycle(initialValue = null)
    val vodSyncState = viewModel.vodSyncState
    val accounts = viewModel.accounts
    val activeAccount = accounts.active
    // A conta escolhida na lista, com as ações dela. Ao fechar, o foco volta a essa conta.
    var managing by remember { mutableStateOf<Account?>(null) }
    var confirmingRemove by remember { mutableStateOf(false) }
    var focusAccountId by remember { mutableStateOf<String?>(null) }
    var showingReport by remember { mutableStateOf(false) }
    val reportItem = remember { FocusRequester() }
    var focusReport by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val firstItem = remember { FocusRequester() }
    val accountItems = remember { mutableMapOf<String, FocusRequester>() }
    val firstAction = remember { FocusRequester() }
    if (showingReport) {
        DiagnosticScreen(viewModel, onBack = {
            showingReport = false
            focusReport = true
        })
        return
    }
    RequestInitialFocus(firstItem, ready = managing == null && focusAccountId == null && !focusReport)
    if (focusReport) RequestInitialFocus(reportItem)
    focusAccountId?.let { id ->
        RequestInitialFocus(accountItems.getOrPut(id) { FocusRequester() }, ready = managing == null)
    }
    RequestInitialFocus(firstAction, ready = managing != null, trigger = confirmingRemove)

    fun closeAccount() {
        focusAccountId = managing?.id
        managing = null
        confirmingRemove = false
    }
    BackHandler(enabled = managing != null) {
        if (confirmingRemove) confirmingRemove = false else closeAccount()
    }

    managing?.let { account ->
        val isActive = account.id == activeAccount?.id
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize().padding(ScreenPadding),
        ) {
            BroadcastHeader(current = Section.SETTINGS)
            Text(account.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Text(
                if (confirmingRemove) {
                    tr(R.string.remove_account_warning)
                } else {
                    withoutQuery(account.server) + if (isActive) tr(R.string.account_in_use_suffix) else ""
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (confirmingRemove) {
                    BarButton(
                        onClick = { confirmingRemove = false },
                        modifier = Modifier.focusRequester(firstAction),
                    ) { Text(tr(R.string.cancel)) }
                    BarButton(onClick = {
                        managing = null
                        confirmingRemove = false
                        // O foco passa para a conta em uso. Se for esta, o ecrã abre de novo com a seguinte.
                        focusAccountId = activeAccount?.id
                        viewModel.removeAccount(account.id)
                    }) { Text(tr(R.string.remove)) }
                } else {
                    if (!isActive) {
                        BarButton(
                            onClick = { viewModel.switchAccount(account.id) },
                            modifier = Modifier.focusRequester(firstAction),
                        ) { Text(tr(R.string.account_use)) }
                    }
                    BarButton(
                        onClick = { viewModel.startEditAccount(account.id) },
                        modifier = if (isActive) Modifier.focusRequester(firstAction) else Modifier,
                    ) { Text(tr(R.string.edit)) }
                    BarButton(onClick = { confirmingRemove = true }) { Text(tr(R.string.remove)) }
                    BarButton(onClick = ::closeAccount) { Text(tr(R.string.back)) }
                }
            }
        }
        return
    }

    // O cabeçalho fica fixo e só a lista desliza, para o relógio nunca sair do ecrã.
    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding)) {
        BroadcastHeader(current = Section.SETTINGS)
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
    ) {
        item {
            BarListItem(
                selected = false,
                onClick = onManageCategories,
                headlineContent = { Text(tr(R.string.manage_categories)) },
                modifier = Modifier.focusRequester(firstItem),
            )
        }
        item {
            BarListItem(
                selected = false,
                onClick = viewModel::syncNow,
                headlineContent = { Text(tr(R.string.sync_now)) },
                supportingContent = {
                    Column {
                        Text(syncDescription(syncState, info))
                        Text(vodDescription(vodSyncState, vodInfo))
                    }
                },
            )
        }
        item {
            Text(
                tr(R.string.accounts),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, start = 16.dp),
            )
        }
        items(accounts.accounts, key = { it.id }) { account ->
            val isActive = account.id == activeAccount?.id
            BarListItem(
                selected = isActive,
                onClick = { managing = account },
                headlineContent = { Text(account.name) },
                supportingContent = { Text(if (isActive) tr(R.string.in_use) else tr(R.string.account_actions)) },
                modifier = Modifier.focusRequester(accountItems.getOrPut(account.id) { FocusRequester() }),
            )
        }
        item {
            BarListItem(
                selected = false,
                onClick = viewModel::startAddAccount,
                headlineContent = { Text(tr(R.string.account_add)) },
            )
        }
        item {
            // A troca reinicia a app, que é a única forma de todos os textos mudarem.
            val activity = LocalContext.current as Activity
            val language = remember { AppLanguage.read(activity) }
            SettingItem(tr(R.string.language), tr(R.string.language_desc), languageLabel(language)) {
                val next = AppLanguage.Choice.entries[(language.ordinal + 1) % AppLanguage.Choice.entries.size]
                AppLanguage.change(activity, next)
            }
        }
        item {
            Text(
                tr(R.string.player),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, start = 16.dp),
            )
        }
        settings?.let { current ->
            item {
                SettingItem(tr(R.string.latency), tr(R.string.latency_desc), current.latency.label) {
                    viewModel.updateSettings(current.copy(latency = current.latency.next()))
                }
            }
            item {
                SettingItem(tr(R.string.frame_rate), tr(R.string.frame_rate_desc), current.frameRate.label) {
                    viewModel.updateSettings(current.copy(frameRate = current.frameRate.next()))
                }
            }
            item {
                SettingItem(tr(R.string.format), tr(R.string.format_desc), current.format.label) {
                    viewModel.updateSettings(current.copy(format = current.format.next()))
                }
            }
            item {
                SettingItem(tr(R.string.tunneling), tr(R.string.tunneling_desc), onOff(current.tunneling)) {
                    viewModel.updateSettings(current.copy(tunneling = !current.tunneling))
                }
            }
            item {
                SettingItem(tr(R.string.player_stats), tr(R.string.player_stats_desc), onOff(current.stats)) {
                    viewModel.updateSettings(current.copy(stats = !current.stats))
                }
            }
            item {
                SettingItem(
                    tr(R.string.diagnostics_keep),
                    tr(R.string.diagnostics_keep_desc),
                    onOff(current.diagnostics),
                ) {
                    viewModel.updateSettings(current.copy(diagnostics = !current.diagnostics))
                }
            }
            if (current.diagnostics) {
                item {
                    BarListItem(
                        selected = false,
                        onClick = { showingReport = true },
                        headlineContent = { Text(tr(R.string.diagnostics_send)) },
                        supportingContent = { Text(tr(R.string.diagnostics_send_desc)) },
                        modifier = Modifier.focusRequester(reportItem),
                    )
                }
            }
            item {
                // O texto fica local enquanto se escreve, para cada tecla não esperar pela gravação.
                var epg by remember { mutableStateOf(activeAccount?.epgUrl.orEmpty()) }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TvTextField(
                        label = tr(R.string.epg_field),
                        value = epg,
                        onValueChange = {
                            epg = it
                            viewModel.updateEpgUrl(it)
                        },
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                        display = ::withoutQuery,
                    )
                    Text(
                        epgDescription(viewModel.epgState),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item {
            Text(
                accountDescription(info),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp, start = 16.dp),
            )
        }
        item {
            // A versão, para quem abre uma issue à mão saber qual tem.
            val context = LocalContext.current
            val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            Text(
                tr(R.string.app_version, version.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, start = 16.dp),
            )
        }
    }
    }
}

@Composable
private fun SettingItem(title: String, description: String, value: String, onClick: () -> Unit) {
    BarListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium) },
    )
}

/**
 * O endereço sem a parte depois do "?". Num servidor Xtream é aí que vão o utilizador e a
 * password, e a TV pode estar à vista de outras pessoas.
 */
private fun withoutQuery(url: String): String =
    if ('?' in url) url.substringBefore('?') + "?…" else url


private fun languageLabel(choice: AppLanguage.Choice): String = when (choice) {
    AppLanguage.Choice.AUTOMATIC -> tr(R.string.automatic)
    AppLanguage.Choice.PORTUGUESE -> tr(R.string.language_portuguese)
    AppLanguage.Choice.ENGLISH -> tr(R.string.language_english)
}

private fun onOff(value: Boolean) = if (value) tr(R.string.on) else tr(R.string.off)

private fun LatencyMode.next() = LatencyMode.entries[(ordinal + 1) % LatencyMode.entries.size]
private fun FrameRateMode.next() = FrameRateMode.entries[(ordinal + 1) % FrameRateMode.entries.size]
private fun FormatPreference.next() = FormatPreference.entries[(ordinal + 1) % FormatPreference.entries.size]

private fun syncDescription(state: SyncState, info: SyncInfoEntity?): String = when (state) {
    is SyncState.Running -> tr(R.string.sync_running, state.channels)
    is SyncState.Failed -> tr(R.string.failed, state.message)
    SyncState.Idle -> info?.let {
        tr(
            R.string.last_sync,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, AppLanguage.locale).format(Date(it.lastSyncAt)),
        )
    } ?: tr(R.string.not_synced)
}

private fun epgDescription(state: EpgState): String = when (state) {
    EpgState.Off -> tr(R.string.epg_off)
    EpgState.Running -> tr(R.string.epg_running)
    is EpgState.Failed -> tr(R.string.failed, state.message)
    is EpgState.Ready -> tr(
        R.string.epg_ready,
        state.linked,
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, AppLanguage.locale).format(Date(state.importedAt)),
    )
}

private fun vodDescription(state: SyncState, info: VodSyncInfoEntity?): String = when (state) {
    is SyncState.Running -> tr(R.string.vod_sync_running, state.channels)
    is SyncState.Failed -> tr(R.string.vod_sync_failed, state.message)
    SyncState.Idle -> info?.let { tr(R.string.vod_sync_counts, it.movieCount, it.seriesCount) }
        ?: tr(R.string.vod_not_synced)
}

private fun accountDescription(info: SyncInfoEntity?): String {
    if (info == null) return tr(R.string.account_no_data)
    val expires = info.expiresAtEpochSeconds
        ?.let { tr(R.string.account_valid_until, DateFormat.getDateInstance(DateFormat.LONG, AppLanguage.locale).format(Date(it * 1000))) }
        .orEmpty()
    val connections = info.maxConnections?.let { trPlural(R.plurals.connections_count, it) }.orEmpty()
    return tr(R.string.account_line, accountStatus(info.accountStatus), expires, connections)
}

/** Os servidores Xtream devolvem o estado em inglês. */
private fun accountStatus(status: String?): String = when (status?.lowercase()) {
    null -> tr(R.string.status_unknown)
    "active" -> tr(R.string.status_active)
    "expired" -> tr(R.string.status_expired)
    "banned" -> tr(R.string.status_banned)
    "disabled" -> tr(R.string.status_disabled)
    else -> tr(R.string.status_other, status)
}
