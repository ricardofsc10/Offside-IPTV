package io.github.ricardofsc10.offsideplayer.ui

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ricardofsc10.offsideplayer.data.accounts.Account
import io.github.ricardofsc10.offsideplayer.data.accounts.AccountList
import io.github.ricardofsc10.offsideplayer.data.accounts.AccountStore
import io.github.ricardofsc10.offsideplayer.data.accounts.Accounts
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import io.github.ricardofsc10.offsideplayer.data.library.ProgressKind
import io.github.ricardofsc10.offsideplayer.data.library.VodItem
import io.github.ricardofsc10.offsideplayer.data.library.VodKind
import io.github.ricardofsc10.offsideplayer.data.settings.PlayerSettings
import io.github.ricardofsc10.offsideplayer.data.settings.SettingsStore
import io.github.ricardofsc10.offsideplayer.data.xtream.ServerAddress
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamClient
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamException
import io.github.ricardofsc10.offsideplayer.diagnostics.DiagnosticReport
import io.github.ricardofsc10.offsideplayer.diagnostics.Diagnostics
import io.github.ricardofsc10.offsideplayer.diagnostics.ReportContext
import io.github.ricardofsc10.offsideplayer.player.SessionStore
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** O texto do relatório e os dois destinos: uma issue no GitHub ou um email. */
data class DiagnosticExport(val text: String, val issueUrl: String, val mailUrl: String)

sealed interface AppState {
    data object Starting : AppState

    /**
     * O formulário de uma conta. Sem [editingId], acrescenta uma conta nova. [canCancel] é falso
     * quando não há nenhuma conta para onde voltar.
     */
    data class Login(
        val name: String = "",
        val server: String = "",
        val username: String = "",
        val password: String = "",
        val error: String? = null,
        val progress: String? = null,
        val editingId: String? = null,
        val canCancel: Boolean = false,
    ) : AppState

    /** [openSettings] abre logo as Definições, ao voltar do formulário de uma conta. */
    data class Main(val session: AccountSession, val openSettings: Boolean = false) : AppState
}

@Suppress("TooManyFunctions")
class AppViewModel(private val application: Application) : AndroidViewModel(application) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val accountStore = AccountStore(application)
    private val settingsStore = SettingsStore(application)

    val client = XtreamClient(http)

    /** Só guarda alguma coisa com o diagnóstico ligado. */
    val sessions: SessionStore get() = Diagnostics.sessions

    val settings: StateFlow<PlayerSettings?> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var state by mutableStateOf<AppState>(AppState.Starting)
        private set

    var accounts by mutableStateOf(AccountList())
        private set

    private var session: AccountSession? = null

    // O resto da app fala com a conta ativa através destes atalhos.
    private val active: AccountSession get() = checkNotNull(session) { "no active account" }
    val library get() = active.library
    val vod get() = active.vod
    val movieBrowser get() = active.movieBrowser
    val seriesBrowser get() = active.seriesBrowser
    val epg get() = active.epg
    val guide get() = active.guide
    val epgState get() = active.epgState
    val syncState get() = active.syncState
    val vodSyncState get() = active.vodSyncState
    val categoryChannels get() = active.categoryChannels
    val searchResults get() = active.searchResults
    val searchQuery get() = active.searchQuery

    fun openCategory(id: String) = active.openCategory(id)
    fun reloadCategoryAround(channel: ChannelRow) = active.reloadCategoryAround(channel)
    fun setSearchQuery(query: String) = active.setSearchQuery(query)
    fun setFavorite(streamId: Long, favorite: Boolean) = active.setFavorite(streamId, favorite)
    fun syncIfDue() = active.syncIfDue()
    fun syncNow() = active.syncNow()
    fun setVodFavorite(item: VodItem, favorite: Boolean) = active.setVodFavorite(item, favorite)
    fun removeFromContinue(item: VodItem) = active.removeFromContinue(item)
    fun setVodCategoryHidden(kind: VodKind, id: String, hidden: Boolean) = active.setVodCategoryHidden(kind, id, hidden)
    fun setCategoryHidden(id: String, hidden: Boolean) = active.setCategoryHidden(id, hidden)
    fun saveProgress(kind: ProgressKind, id: Long, seriesId: Long?, positionMs: Long, durationMs: Long) =
        active.saveProgress(kind, id, seriesId, positionMs, durationMs)

    fun updateSettings(settings: PlayerSettings) {
        viewModelScope.launch { settingsStore.update(settings) }
    }

    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch { session?.retryAfterNetwork() }
        }
    }

    init {
        viewModelScope.launch {
            accounts = accountStore.load(settingsStore::legacyEpgUrl)
            Diagnostics.secrets = secretsOf(accounts)
            val active = accounts.active
            state = if (active == null) AppState.Login() else AppState.Main(open(active))
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        viewModelScope.launch {
            settingsStore.settings.collect { Diagnostics.setEnabled(it.diagnostics) }
        }
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
        session?.close()
    }

    private fun open(account: Account): AccountSession {
        session?.close()
        return AccountSession(application, account, viewModelScope, http, client).also { session = it }
    }

    private suspend fun saveAccounts(list: AccountList) {
        accounts = list
        Diagnostics.secrets = secretsOf(list)
        accountStore.save(list)
    }

    fun startAddAccount() {
        state = AppState.Login(canCancel = session != null)
    }

    fun startEditAccount(id: String) {
        val account = accounts.accounts.firstOrNull { it.id == id } ?: return
        state = AppState.Login(
            name = account.name,
            server = account.server,
            username = account.username,
            password = account.password,
            editingId = account.id,
            canCancel = true,
        )
    }

    fun cancelAccountForm() {
        val current = session ?: return
        state = AppState.Main(current, openSettings = true)
    }

    /** Acrescenta uma conta nova ou grava as alterações da que está a ser editada. */
    fun submitAccount(name: String, server: String, username: String, password: String) {
        val form = state as? AppState.Login ?: return
        val normalized = ServerAddress.normalize(server)
        // Um link M3U do fornecedor já traz o utilizador e a password, e os campos podem ficar vazios.
        val fromLink = ServerAddress.credentialsIn(server)
        val user = username.trim().ifEmpty { fromLink?.first.orEmpty() }
        val pass = password.ifEmpty { fromLink?.second.orEmpty() }
        if (normalized == null || user.isEmpty() || pass.isEmpty()) {
            state = form.copy(
                name = name, server = server, username = username, password = password,
                error = tr(R.string.err_fill_account), progress = null,
            )
            return
        }
        val duplicate = accounts.accounts.any {
            it.id != form.editingId && it.server == normalized && it.username == user
        }
        fun formState(error: String? = null, progress: String? = null) = form.copy(
            name = name, server = normalized, username = user, password = pass, error = error, progress = progress,
        )
        if (duplicate) {
            state = formState(error = tr(R.string.err_account_exists))
            return
        }
        val displayName = Accounts.displayName(name, normalized)
        if (form.editingId == null) {
            addAccount(displayName, XtreamCredentials(normalized, user, pass), ::formState)
        } else {
            editAccount(form.editingId, displayName, XtreamCredentials(normalized, user, pass), ::formState)
        }
    }

    private fun addAccount(
        name: String,
        credentials: XtreamCredentials,
        formState: (String?, String?) -> AppState.Login,
    ) {
        val id = UUID.randomUUID().toString().replace("-", "").take(ID_LENGTH)
        val account = Account(
            id = id,
            name = name,
            server = credentials.server,
            username = credentials.username,
            password = credentials.password,
            databaseName = Accounts.databaseName(id),
        )
        viewModelScope.launch {
            state = formState(null, tr(R.string.progress_validating))
            // A conta nova tem a sua base de dados. A anterior só fecha se a nova funcionar.
            val candidate = AccountSession(application, account, viewModelScope, http, client)
            try {
                candidate.firstSync { channels -> state = formState(null, tr(R.string.progress_reading_channels, channels)) }
            } catch (e: XtreamException) {
                candidate.close()
                application.deleteDatabase(account.databaseName)
                state = formState(e.userMessage(), null)
                return@launch
            }
            session?.close()
            session = candidate
            saveAccounts(Accounts.upsertAndActivate(accounts, account))
            state = AppState.Main(candidate)
            candidate.startVodSync()
        }
    }

    /** Valida a conta no servidor e grava. Os dados da conta ficam todos. */
    private fun editAccount(
        id: String,
        name: String,
        credentials: XtreamCredentials,
        formState: (String?, String?) -> AppState.Login,
    ) {
        val existing = accounts.accounts.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            state = formState(null, tr(R.string.progress_validating))
            try {
                client.login(credentials)
            } catch (e: XtreamException) {
                state = formState(e.userMessage(), null)
                return@launch
            }
            val updated = existing.copy(
                name = name,
                server = credentials.server,
                username = credentials.username,
                password = credentials.password,
            )
            saveAccounts(Accounts.update(accounts, updated))
            val current = session
            if (current != null && current.account.id == id) {
                current.updateAccount(updated)
                current.syncNow()
            }
            state = current?.let { AppState.Main(it, openSettings = true) } ?: AppState.Login()
        }
    }

    fun switchAccount(id: String) {
        if (session?.account?.id == id) return
        val account = accounts.accounts.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            saveAccounts(Accounts.activate(accounts, id))
            state = AppState.Main(open(account))
        }
    }

    /** Apaga a conta e todos os dados dela neste aparelho. */
    fun removeAccount(id: String) {
        val account = accounts.accounts.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val wasActive = session?.account?.id == id
            if (wasActive) {
                session?.close()
                session = null
            }
            application.deleteDatabase(account.databaseName)
            val remaining = Accounts.remove(accounts, id)
            saveAccounts(remaining)
            val next = remaining.active
            state = when {
                !wasActive -> session?.let { AppState.Main(it, openSettings = true) } ?: AppState.Login()
                next != null -> AppState.Main(open(next), openSettings = true)
                else -> AppState.Login()
            }
        }
    }

    fun updateEpgUrl(url: String) {
        val current = session ?: return
        val updated = current.account.copy(epgUrl = url.trim())
        current.updateAccount(updated)
        viewModelScope.launch { saveAccounts(Accounts.update(accounts, updated)) }
    }

    /** O relatório de diagnóstico, com o texto que o utilizador vê e o link que vai no QR. */
    suspend fun diagnosticExport(): DiagnosticExport = withContext(Dispatchers.IO) {
        val current = settings.value ?: PlayerSettings()
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        val context = ReportContext(
            appVersion = "${info.versionName} (${info.longVersionCode})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            settings = "lat=${current.latency.name} fmt=${current.format.name} fps=${current.frameRate.name}" +
                " tun=${if (current.tunneling) 1 else 0} fit=${current.videoFit.name} accounts=${accounts.accounts.size}",
        )
        val sessions = Diagnostics.sessions.recent(REPORT_SESSIONS)
        val crashes = Diagnostics.crashes.all()
        val secrets = secretsOf(accounts)
        val title = DiagnosticReport.title(sessions, crashes)
        val body = DiagnosticReport.body(context, sessions, Diagnostics.log.lines(), crashes, secrets, tr(R.string.report_intro))
        DiagnosticExport(body, DiagnosticReport.issueUrl(title, body), DiagnosticReport.mailUrl(title, body))
    }

    private fun secretsOf(list: AccountList): List<String> =
        list.accounts.flatMap { listOf(Accounts.defaultName(it.server), it.username, it.password) }

    private companion object {
        const val ID_LENGTH = 12
        const val REPORT_SESSIONS = 40
    }
}
