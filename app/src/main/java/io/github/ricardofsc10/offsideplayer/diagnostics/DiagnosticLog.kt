package io.github.ricardofsc10.offsideplayer.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registo curto de acontecimentos, uma linha por acontecimento, num ficheiro que só guarda as
 * últimas [maxLines]. As linhas já chegam sem endereços nem credenciais.
 */
class DiagnosticLog(
    private val file: File,
    private val maxLines: Int = 400,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var writes = 0

    @Synchronized
    fun append(line: String) {
        file.parentFile?.mkdirs()
        file.appendText("${stamp(clock())} ${line.replace('\n', ' ')}\n")
        // Cortar a cada linha obrigava a reescrever o ficheiro sempre. Assim cresce no máximo 25%.
        if (++writes % (maxLines / 4).coerceAtLeast(1) == 0) trim()
    }

    @Synchronized
    fun lines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.takeLast(maxLines) else emptyList()

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun trim() {
        val lines = file.readLines()
        if (lines.size > maxLines) file.writeText(lines.takeLast(maxLines).joinToString("\n", postfix = "\n"))
    }
}

/** Os erros que fecharam a app, um ficheiro por erro, só os [keep] mais recentes. */
class CrashStore(private val dir: File, private val keep: Int = 3) {
    fun save(text: String, atMs: Long) {
        dir.mkdirs()
        File(dir, "crash-$atMs.txt").writeText(text)
        files().dropLast(keep).forEach { it.delete() }
    }

    /** Do mais recente para o mais antigo. */
    fun all(): List<String> = files().reversed().map { it.readText() }

    fun clear() = files().forEach { it.delete() }

    private fun files(): List<File> =
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedBy { it.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0 }
            ?: emptyList()
}

object CrashFormat {
    private const val APP_PACKAGE = "io.github.ricardofsc10"
    private const val TOP_FRAMES = 6
    private const val APP_FRAMES = 10
    private const val MAX_CAUSES = 4
    private const val MAX_MESSAGE = 200

    /**
     * A pilha do erro em poucas linhas: as primeiras chamadas e as da app, em cada causa. As
     * mensagens passam por [Redact], porque às vezes citam o servidor.
     */
    fun format(error: Throwable, atMs: Long, thread: String, secrets: Collection<String>): String = buildString {
        append("crash ${stamp(atMs)} thread=$thread\n")
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) append("caused by ")
            append(current.javaClass.name)
            current.message?.let { append(": ").append(Redact.text(it, secrets).take(MAX_MESSAGE)) }
            append('\n')
            val frames = current.stackTrace
            val shown = frames.take(TOP_FRAMES) +
                frames.drop(TOP_FRAMES).filter { it.className.startsWith(APP_PACKAGE) }.take(APP_FRAMES)
            shown.forEach { append(" at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})\n") }
            if (frames.size > shown.size) append(" … ${frames.size - shown.size} more\n")
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }
}

internal fun stamp(ms: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))
