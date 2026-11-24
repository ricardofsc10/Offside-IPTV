package io.github.ricardofsc10.offsideplayer.data.settings

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [keepBytes] é quanto fica do conteúdo que o servidor tem em atraso, ao abrir em MPEG-TS. Com zero,
 * nada é descartado. Bytes e não segundos, porque o débito do canal só se conhece depois de abrir.
 */
enum class LatencyMode(
    @StringRes private val labelRes: Int,
    val keepBytes: Int,
    val maxBufferMs: Int,
    val startBufferMs: Int,
    val rebufferMs: Int,
    val stallReconnectMs: Long,
) {
    MINIMUM(R.string.latency_minimum, 3_000_000, 6_000, 1_500, 2_500, 4_000),
    BALANCED(R.string.latency_balanced, 6_000_000, 8_000, 2_000, 3_000, 6_000),
    STABLE(R.string.latency_stable, 0, 12_000, 2_500, 4_000, 10_000),
    ;

    val label: String get() = tr(labelRes)
}

enum class FrameRateMode(@StringRes private val labelRes: Int) {
    ALWAYS(R.string.frame_rate_always),
    ONLY_IF_SEAMLESS(R.string.frame_rate_seamless),
    ;

    val label: String get() = tr(labelRes)
}

/** Como a imagem dos filmes e séries ocupa o ecrã. */
enum class VideoFit(@StringRes private val labelRes: Int) {
    /** A imagem inteira, com barras pretas se o formato não for o da TV. */
    FIT(R.string.fit_fit),
    /** Ocupa o ecrã todo e corta o que sobra, sem deformar. */
    FILL(R.string.fit_fill),
    /** Ocupa o ecrã todo e deforma a imagem. */
    STRETCH(R.string.fit_stretch),
    ;

    val label: String get() = tr(labelRes)
}

enum class FormatPreference(@StringRes private val labelRes: Int) {
    AUTOMATIC(R.string.automatic),
    MPEG_TS(R.string.format_mpeg_ts),
    HLS(R.string.format_hls),
    ;

    val label: String get() = tr(labelRes)
}

data class PlayerSettings(
    val latency: LatencyMode = LatencyMode.MINIMUM,
    val frameRate: FrameRateMode = FrameRateMode.ALWAYS,
    val format: FormatPreference = FormatPreference.AUTOMATIC,
    val tunneling: Boolean = false,
    val stats: Boolean = false,
    val videoFit: VideoFit = VideoFit.FIT,
    /** Guarda neste aparelho dados técnicos para o relatório de diagnóstico. Só com o consentimento do utilizador. */
    val diagnostics: Boolean = false,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<PlayerSettings> = dataStore.data.map { prefs ->
        PlayerSettings(
            latency = enumOf(prefs[LATENCY], LatencyMode.MINIMUM),
            frameRate = enumOf(prefs[FRAME_RATE], FrameRateMode.ALWAYS),
            format = enumOf(prefs[FORMAT], FormatPreference.AUTOMATIC),
            tunneling = prefs[TUNNELING] ?: false,
            stats = prefs[STATS] ?: false,
            videoFit = enumOf(prefs[VIDEO_FIT], VideoFit.FIT),
            diagnostics = prefs[DIAGNOSTICS] ?: false,
        )
    }

    suspend fun update(settings: PlayerSettings) {
        dataStore.edit { prefs ->
            prefs[LATENCY] = settings.latency.name
            prefs[FRAME_RATE] = settings.frameRate.name
            prefs[FORMAT] = settings.format.name
            prefs[TUNNELING] = settings.tunneling
            prefs[STATS] = settings.stats
            prefs[VIDEO_FIT] = settings.videoFit.name
            prefs[DIAGNOSTICS] = settings.diagnostics
        }
    }

    /**
     * O endereço do guia que as versões com uma só conta guardavam aqui. Agora cada conta tem o seu,
     * e este só serve para a migração.
     */
    suspend fun legacyEpgUrl(): String = dataStore.data.first()[EPG_URL].orEmpty()

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        val LATENCY: Preferences.Key<String> = stringPreferencesKey("latency")
        val FRAME_RATE: Preferences.Key<String> = stringPreferencesKey("frame_rate")
        val FORMAT: Preferences.Key<String> = stringPreferencesKey("format")
        val TUNNELING: Preferences.Key<Boolean> = booleanPreferencesKey("tunneling")
        val STATS: Preferences.Key<Boolean> = booleanPreferencesKey("stats")
        val VIDEO_FIT: Preferences.Key<String> = stringPreferencesKey("video_fit")
        val DIAGNOSTICS: Preferences.Key<Boolean> = booleanPreferencesKey("diagnostics")
        val EPG_URL: Preferences.Key<String> = stringPreferencesKey("epg_url")
    }
}
