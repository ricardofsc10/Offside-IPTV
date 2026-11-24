package io.github.ricardofsc10.offsideplayer.ui.text

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * A língua da app, que pode ser diferente da do aparelho: muitas TVs em Portugal estão em inglês.
 * Fica em SharedPreferences porque tem de se ler antes de haver qualquer ecrã.
 */
object AppLanguage {
    enum class Choice(val tag: String?) {
        AUTOMATIC(null),
        PORTUGUESE("pt-PT"),
        ENGLISH("en"),
    }

    private const val PREFS = "language"
    private const val KEY = "choice"

    private lateinit var resourcesContext: Context

    /** Para usar em `attachBaseContext` da app e da atividade. */
    fun wrap(base: Context): Context {
        val tag = read(base).tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /** Guarda o contexto com a língua certa, para os textos fora dos ecrãs. */
    fun install(context: Context) {
        resourcesContext = context
    }

    fun read(context: Context): Choice {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return Choice.entries.firstOrNull { it.name == name } ?: Choice.AUTOMATIC
    }

    /** Muda a língua e abre a app de novo, que é a única forma de todos os textos mudarem. */
    fun change(activity: Activity, choice: Choice) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.name).commit()
        val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    /** A língua em uso, para as datas. */
    val locale: Locale get() = resourcesContext.resources.configuration.locales[0]

    fun string(@StringRes id: Int, vararg args: Any): String = resourcesContext.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        resourcesContext.resources.getQuantityString(id, count, *(if (args.isEmpty()) arrayOf<Any>(count) else args))
}

/** Um texto da app, na língua escolhida. */
fun tr(@StringRes id: Int, vararg args: Any): String = AppLanguage.string(id, *args)

fun trPlural(@PluralsRes id: Int, count: Int): String = AppLanguage.plural(id, count)
