package io.github.ricardofsc10.offsideplayer.diagnostics

import android.util.Log
import io.github.ricardofsc10.offsideplayer.player.SessionStore
import java.io.File
import java.util.concurrent.Executors

/**
 * O diagnóstico que o utilizador escolhe ligar. Desligado, nada fica guardado e [note] só escreve
 * no logcat. Ligado, guarda neste aparelho as sessões dos canais, os acontecimentos e os erros
 * que fecharam a app. Nada sai do aparelho sem o utilizador enviar o relatório.
 */
object Diagnostics {
    @Volatile
    var enabled = false
        private set

    /** Servidores, utilizadores e passwords das contas, para nunca entrarem no que fica guardado. */
    @Volatile
    var secrets: List<String> = emptyList()

    lateinit var log: DiagnosticLog
        private set
    lateinit var crashes: CrashStore
        private set
    lateinit var sessions: SessionStore
        private set
    private val io = Executors.newSingleThreadExecutor { Thread(it, "diagnostics").apply { isDaemon = true } }

    fun init(dir: File) {
        log = DiagnosticLog(File(dir, "diagnostics/log.txt"))
        crashes = CrashStore(File(dir, "diagnostics"))
        // A pasta das sessões é a de antes, para o que já estava guardado não se perder.
        sessions = SessionStore(File(dir, "sessions"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (enabled) {
                // Sem passar pelo executor: a app fecha logo a seguir.
                runCatching {
                    val now = System.currentTimeMillis()
                    crashes.save(CrashFormat.format(error, now, thread.name, secrets), now)
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Ao desligar, apaga tudo o que estava guardado. */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) io.execute { runCatching { clear() } }
    }

    /** Uma linha para o logcat e, com o diagnóstico ligado, para o registo. Sem URLs nem credenciais. */
    fun note(tag: String, line: String) {
        Log.i(tag, line)
        if (!enabled) return
        val clean = Redact.text(line, secrets)
        io.execute { runCatching { log.append(clean) } }
    }

    private fun clear() {
        log.clear()
        crashes.clear()
        sessions.clear()
    }
}
