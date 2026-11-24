package io.github.ricardofsc10.offsideplayer

import android.content.Context
import android.os.Bundle
import io.github.ricardofsc10.offsideplayer.ui.text.AppLanguage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Surface
import io.github.ricardofsc10.offsideplayer.ui.theme.OffsideTheme
import coil3.compose.setSingletonImageLoaderFactory
import io.github.ricardofsc10.offsideplayer.ui.AppState
import io.github.ricardofsc10.offsideplayer.ui.AppViewModel
import io.github.ricardofsc10.offsideplayer.ui.MainScreen
import io.github.ricardofsc10.offsideplayer.ui.components.buildImageLoader
import io.github.ricardofsc10.offsideplayer.ui.login.LoginScreen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    /**
     * A frequência escolhida pelo player mantém-se enquanto a app estiver aberta, para o ecrã não
     * piscar ao sair e entrar de um canal. Só volta à frequência normal ao sair da app.
     */
    override fun onStop() {
        super.onStop()
        window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Os erros do Media3 podem citar o URL do stream, que contém as credenciais.
        Log.setLogLevel(Log.LOG_LEVEL_OFF)

        setContent {
            // As imagens usam o mesmo cliente HTTP do resto da app.
            setSingletonImageLoaderFactory { context -> buildImageLoader(context, viewModel.http) }
            OffsideTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val state = viewModel.state) {
                        AppState.Starting -> Box(Modifier.fillMaxSize())
                        is AppState.Login -> LoginScreen(
                            state = state,
                            onSubmit = viewModel::submitAccount,
                            onCancel = viewModel::cancelAccountForm,
                        )
                        // Uma conta nova começa do zero: a navegação e as listas não passam de uma conta para a outra.
                        is AppState.Main -> key(state.session) {
                            MainScreen(viewModel, state.session.credentials, state.openSettings, onExit = ::finish)
                        }
                    }
                }
            }
        }
    }
}
