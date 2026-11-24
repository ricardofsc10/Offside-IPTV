package io.github.ricardofsc10.offsideplayer.data.library

import java.text.Normalizer
import java.util.Locale

object SyncPolicy {
    const val INTERVAL_MS = 8L * 60 * 60 * 1000

    /** Também sincroniza se o relógio tiver andado para trás, para não ficar preso a uma data futura. */
    fun isDue(lastSyncAt: Long?, now: Long): Boolean =
        lastSyncAt == null || now < lastSyncAt || now - lastSyncAt >= INTERVAL_MS
}

object TextNormalizer {
    private val marks = Regex("\\p{M}+")

    /** Sem acentos nem maiúsculas, para a pesquisa encontrar "Benfica" ao escrever "benfica". */
    fun normalize(text: String): String =
        marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT)
            .trim()

    /** Padrão LIKE para pesquisar em qualquer parte do nome, com `%` e `_` tratados como texto. */
    fun likePattern(query: String): String {
        val escaped = normalize(query)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}
