package io.github.ricardofsc10.offsideplayer.data.library

object ProgressPolicy {
    /** A partir daqui conta como visto, porque os créditos finais raramente se veem até ao fim. */
    const val FINISHED_FRACTION = 0.95

    /** Abaixo disto não vale a pena retomar: é quase o mesmo que começar do início. */
    const val MIN_RESUME_MS = 10_000L

    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

    /** Onde retomar, ou `null` para começar do início. */
    fun resumePositionMs(positionMs: Long?, durationMs: Long?, finished: Boolean?): Long? {
        if (positionMs == null || finished == true || positionMs < MIN_RESUME_MS) return null
        if (durationMs != null && isFinished(positionMs, durationMs)) return null
        return positionMs
    }
}

/** Fração vista para a barra de progresso, ou `null` sem dados suficientes. */
fun progressFraction(positionMs: Long?, durationMs: Long?): Float? {
    if (positionMs == null || durationMs == null || durationMs <= 0) return null
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

object EpisodesPolicy {
    /** Os episódios guardados voltam a ser pedidos ao abrir a série, se tiverem mais do que isto. */
    const val REFRESH_MS = SyncPolicy.INTERVAL_MS

    fun isStale(fetchedAt: Long?, now: Long): Boolean =
        fetchedAt == null || now < fetchedAt || now - fetchedAt >= REFRESH_MS
}

/** Conta as extensões dos ficheiros, só para medir. Não guarda títulos. */
class ExtensionCounter {
    private val counts = sortedMapOf<String, Int>()

    fun add(extension: String) {
        counts[extension] = (counts[extension] ?: 0) + 1
    }

    /** Por exemplo `mkv:120,mp4:3400`. */
    override fun toString(): String = counts.entries.joinToString(",") { "${it.key}:${it.value}" }
}

/**
 * Os IDs vistos numa sincronização, guardados num LongArray em vez de um conjunto de Long,
 * para 200 000 títulos ocuparem pouca memória.
 */
class IdCollector {
    private var ids = LongArray(1024)
    private var size = 0

    fun add(id: Long) {
        if (size == ids.size) ids = ids.copyOf(size * 2)
        ids[size++] = id
    }

    /** Os IDs de [existing] que não foram vistos: os que desapareceram do fornecedor. */
    fun missingFrom(existing: LongArray): List<Long> {
        val seen = ids.copyOf(size).also { it.sort() }
        return existing.filter { seen.binarySearch(it) < 0 }
    }
}
