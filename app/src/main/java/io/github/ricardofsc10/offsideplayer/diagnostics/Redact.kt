package io.github.ricardofsc10.offsideplayer.diagnostics

/**
 * Retira de um texto o que pode identificar a conta: endereços, IPs e os valores de `secrets`
 * (servidores, utilizadores e passwords das contas). Corre sobre tudo o que o diagnóstico guarda.
 */
object Redact {
    private val URL = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]+""")
    private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}(:\d+)?\b""")

    /** Valores mais curtos do que isto apanhavam palavras comuns e estragavam o texto. */
    private const val MIN_SECRET = 3

    fun text(input: String, secrets: Collection<String> = emptyList()): String {
        var out = URL.replace(input, "<url>")
        // Os mais compridos primeiro, para um utilizador dentro de um servidor não deixar restos.
        secrets.map { it.trim() }.filter { it.length >= MIN_SECRET }.distinct().sortedByDescending { it.length }
            .forEach { out = out.replace(it, "<conta>", ignoreCase = true) }
        return IPV4.replace(out, "<ip>")
    }
}
