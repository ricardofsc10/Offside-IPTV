package io.github.ricardofsc10.offsideplayer

import android.app.Application
import android.content.Context
import io.github.ricardofsc10.offsideplayer.diagnostics.Diagnostics
import io.github.ricardofsc10.offsideplayer.ui.text.AppLanguage

class OffsideApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguage.install(this)
        // Na pasta externa da app, para se poder copiar por ADB sem a versão debug.
        Diagnostics.init(getExternalFilesDir(null) ?: filesDir)
    }
}
