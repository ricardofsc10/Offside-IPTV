package io.github.ricardofsc10.offsideplayer.diagnostics

import io.github.ricardofsc10.offsideplayer.player.SessionEvent
import io.github.ricardofsc10.offsideplayer.player.SessionSummary
import java.net.URLEncoder
import java.util.Locale

/** O aparelho e as escolhas do leitor, para o relatório. Nada da conta. */
data class ReportContext(
    val appVersion: String,
    val device: String,
    val android: String,
    val settings: String,
)

/**
 * Monta o relatório que vai num QR para uma issue no GitHub. O formato é compacto mas legível, com
 * uma linha por tema, para se perceber logo o problema sem ferramentas. Tudo cabe em [MAX_URL]
 * caracteres de link, para o QR se ler do sofá. Quando não cabe tudo, ficam primeiro
 * os erros que fecharam a app, depois as sessões com problemas, das mais recentes para as mais antigas,
 * e por fim as últimas linhas do registo.
 */
object DiagnosticReport {
    const val ISSUE_URL = "https://github.com/ricardofsc10/Offside-IPTV/issues/new"
    /**
     * Com 2 800 caracteres, o QR tinha cerca de 170 módulos de lado e não se lia do sofá. Com este
     * limite fica com cerca de 110, e cada módulo sai quase com o dobro do tamanho na TV.
     */
    const val MAX_URL = 1_250
    private const val MAX_SESSIONS = 4
    private const val MAX_EVENTS_LINE = 220
    private const val MAX_CRASH = 300

    fun issueUrl(title: String, body: String): String =
        "$ISSUE_URL?title=${encode(title)}&body=${encode(body)}"

    fun title(sessions: List<SessionSummary>, crashes: List<String>): String {
        val problems = sessions.count { it.outcome == "problem" }
        val parts = buildList {
            if (crashes.isNotEmpty()) add("${crashes.size} ${if (crashes.size == 1) "erro" else "erros"}")
            if (problems > 0) add("$problems ${if (problems == 1) "sessão com problemas" else "sessões com problemas"}")
        }
        return "Diagnóstico: " + parts.ifEmpty { listOf("sem problemas registados") }.joinToString(", ")
    }

    /**
     * O corpo da issue. [sessions] e [crashes] vêm do mais recente para o mais antigo, e [log]
     * do mais antigo para o mais recente, como no ficheiro.
     */
    fun body(
        context: ReportContext,
        sessions: List<SessionSummary>,
        log: List<String>,
        crashes: List<String>,
        secrets: Collection<String> = emptyList(),
        question: String = "**O que aconteceu?** (escreve aqui, por exemplo o canal ou o filme e a hora)",
    ): String {
        val title = title(sessions, crashes)
        // O que o utilizador escreve no telemóvel vem primeiro, e o bloco técnico a seguir.
        // Cada caráter conta para o tamanho do QR, por isso não há texto fixo além da pergunta.
        val intro = "$question\n\n\n```\n"
        val outro = "```\n"
        val lines = mutableListOf<String>()
        fun fits(extra: List<String>): Boolean {
            val text = intro + (lines + extra).joinToString("\n", postfix = "\n") + outro
            return issueUrl(title, Redact.text(text, secrets)).length <= MAX_URL
        }
        fun add(block: List<String>): Boolean = fits(block).also { if (it) lines += block }

        add(
            listOf(
                "app ${context.appVersion}",
                "dev ${context.device} · ${context.android}",
                "cfg ${context.settings}",
            ),
        )
        crashes.firstOrNull()?.let { crash ->
            val crashLines = crash.take(MAX_CRASH).trimEnd().lines()
            if (!add(listOf("--- crash (${crashes.size})") + crashLines)) {
                // Sem espaço para a pilha toda, fica a primeira linha, que diz qual foi o erro.
                add(listOf("--- crash (${crashes.size})") + crashLines.take(3))
            }
        }
        val kept = sessions.sortedByDescending { it.outcome == "problem" }.take(MAX_SESSIONS)
        if (kept.isNotEmpty()) {
            val problems = sessions.count { it.outcome == "problem" }
            add(listOf("--- sessions $problems problem / ${sessions.size} kept"))
            kept.forEachIndexed { index, session ->
                if (!add(sessionLines(index + 1, session))) return@forEachIndexed
                // Os acontecimentos só da primeira com problemas, que é a que costuma interessar.
                if (index == 0 && session.outcome == "problem") add(listOf(" ev " + events(session.events)))
            }
        }
        if (log.isNotEmpty()) {
            add(listOf("--- log"))
            // Das mais recentes para trás, mas mostradas pela ordem em que aconteceram.
            val fitted = mutableListOf<String>()
            for (line in log.asReversed()) {
                if (!fits(fitted.asReversed().let { listOf(line) + it })) break
                fitted += line
            }
            lines += fitted.asReversed()
        }
        return Redact.text(intro + lines.joinToString("\n", postfix = "\n") + outro, secrets)
    }

    private fun sessionLines(n: Int, s: SessionSummary): List<String> {
        val video = listOfNotNull(
            s.format,
            if (s.width != null && s.height != null) "${s.width}x${s.height}" else null,
            s.fps?.let { "%.0ffps".format(Locale.ROOT, it) },
            s.codec,
        ).joinToString(" ")
        return listOf(
            "#$n ${s.startedAt} ch=${s.channel} ${s.outcome} lat=${s.latencyMode} $video",
            " open=${s.openS}s watch=${s.watchedS}s ff=${s.firstFrameMs ?: "-"}ms rebuf=${s.rebuffers}" +
                " stall=${s.stalledMs}/${s.longestStallMs}ms rc=${s.reconnects} retry=${s.retries} fail=${s.failure ?: "-"}",
            " drop=${s.droppedFrames} slow=${s.slowdowns}/${s.slowedMs}ms buf=${s.bufferMinMs ?: "-"}/${s.bufferAvgMs ?: "-"}ms" +
                " kbps=${s.kbpsAvg ?: "-"} gaps=${s.deliveryGaps} maxread=${s.longestReadMs}ms",
        )
    }

    /** "segundos tipo:detalhe", separados por "|", até caber numa linha razoável. */
    private fun events(events: List<SessionEvent>): String {
        val out = StringBuilder()
        for (e in events) {
            val item = "%.1f %s".format(Locale.ROOT, e.t / 1000.0, e.type) + (e.detail?.let { ":$it" } ?: "")
            if (out.length + item.length + 1 > MAX_EVENTS_LINE) {
                out.append("|…")
                break
            }
            if (out.isNotEmpty()) out.append('|')
            out.append(item)
        }
        return out.toString()
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
}
