package io.github.ricardofsc10.offsideplayer.ui.login

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.BarButton
import io.github.ricardofsc10.offsideplayer.ui.theme.BarListItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.ricardofsc10.offsideplayer.ui.AppState
import io.github.ricardofsc10.offsideplayer.ui.components.TvTextField

@Composable
fun LoginScreen(
    state: AppState.Login,
    onSubmit: (name: String, server: String, username: String, password: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(state.name) { mutableStateOf(state.name) }
    var server by rememberSaveable(state.server) { mutableStateOf(state.server) }
    var username by rememberSaveable(state.username) { mutableStateOf(state.username) }
    var password by remember(state.password) { mutableStateOf(state.password) }
    val firstField = remember { FocusRequester() }
    val loginButton = remember { FocusRequester() }
    val editing = state.editingId != null
    // Enquanto valida, Voltar não faz nada: a conta nova ainda pode ficar a meio.
    BackHandler(enabled = state.canCancel) { if (state.progress == null) onCancel() }

    Row(modifier = Modifier.fillMaxSize().padding(ScreenPadding), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(360.dp).padding(top = 28.dp)) {
            Box(Modifier.background(Pal.Text).padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("OFFSIDE", style = MaterialTheme.typography.displaySmall, color = Pal.Ink)
            }
            Text(
                tr(R.string.login_tagline),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (state.canCancel) {
                Text(
                    if (editing) tr(R.string.account_edit) else tr(R.string.account_new),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Text(
                tr(R.string.login_help),
                style = MaterialTheme.typography.bodyLarge,
                color = Pal.Muted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(440.dp).padding(top = 28.dp)) {
            if (state.progress != null) {
                Text(state.progress, style = MaterialTheme.typography.headlineSmall)
                Text(tr(R.string.login_first_time), style = MaterialTheme.typography.bodyLarge, color = Pal.Muted)
                return@Column
            }
            // Depois de um erro, o foco fica em "Entrar" para tentar de novo. Os campos ficam por cima.
            LaunchedEffect(Unit) { (if (state.error != null) loginButton else firstField).requestFocus() }
            TvTextField(
                label = tr(R.string.field_account_name),
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.focusRequester(firstField),
            )
            TvTextField(
                label = tr(R.string.field_server),
                value = server,
                onValueChange = { server = it },
                keyboardType = KeyboardType.Uri,
                // Um link M3U traz a password depois do "?", e a TV pode estar à vista de outras pessoas.
                display = { if ('?' in it) it.substringBefore('?') + "?…" else it },
            )
            TvTextField(tr(R.string.field_username), username, { username = it })
            TvTextField(
                label = tr(R.string.field_password),
                value = password,
                onValueChange = { password = it },
                keyboardType = KeyboardType.Password,
                hidden = true,
                imeAction = ImeAction.Done,
                // Sem isto, ▼ ia para "Cancelar", que fica mais ao centro do campo.
                modifier = Modifier.focusProperties { down = loginButton },
            )
            state.error?.let {
                Text(it, color = Pal.Red, style = MaterialTheme.typography.bodyLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                BarButton(
                    onClick = { onSubmit(name, server, username, password) },
                    modifier = Modifier.focusRequester(loginButton),
                ) { Text(if (editing) tr(R.string.save) else tr(R.string.sign_in)) }
                if (state.canCancel) {
                    BarButton(onClick = onCancel) { Text(tr(R.string.cancel)) }
                }
            }
        }
    }
}
