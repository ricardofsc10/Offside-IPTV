package io.github.ricardofsc10.offsideplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Deteta o fim do conteúdo em atraso que o servidor envia ao abrir um stream: enquanto há atraso,
 * os dados chegam muito mais depressa do que o direto, e depois o ritmo cai.
 */
class BacklogDetector(
    private val windowMs: Long = 100,
    private val minObserveMs: Long = 300,
    private val budgetMs: Long = 1_500,
    private val dropRatio: Double = 0.4,
) {
    private var startedAt = -1L
    private var windowStart = 0L
    private var windowBytes = 0L
    private var peakRate = 0.0

    /** Devolve verdadeiro quando já se pode parar de descartar. */
    fun onBytes(bytes: Int, nowMs: Long): Boolean {
        if (startedAt < 0) {
            startedAt = nowMs
            windowStart = nowMs
        }
        windowBytes += bytes
        val windowElapsed = nowMs - windowStart
        if (windowElapsed >= windowMs) {
            val rate = windowBytes.toDouble() / windowElapsed
            if (rate > peakRate) {
                peakRate = rate
            } else if (nowMs - startedAt >= minObserveMs && rate < peakRate * dropRatio) {
                return true
            }
            windowStart = nowMs
            windowBytes = 0
        }
        return nowMs - startedAt >= budgetMs
    }
}

/** Guarda só os últimos bytes escritos, por ordem. */
class TailBuffer(capacity: Int) {
    private val data = ByteArray(capacity)
    private var writeAt = 0
    private var filled = false

    fun write(source: ByteArray, count: Int) {
        if (data.isEmpty()) return
        if (count >= data.size) {
            source.copyInto(data, 0, count - data.size, count)
            writeAt = 0
            filled = true
            return
        }
        val first = minOf(count, data.size - writeAt)
        source.copyInto(data, writeAt, 0, first)
        source.copyInto(data, 0, first, count)
        if (writeAt + count >= data.size) filled = true
        writeAt = (writeAt + count) % data.size
    }

    fun toByteArray(): ByteArray =
        if (filled) data.copyOfRange(writeAt, data.size) + data.copyOfRange(0, writeAt) else data.copyOf(writeAt)
}

data class SkipReport(val readBytes: Long, val keptBytes: Int, val elapsedMs: Long)

/** [maxReadMs] é a espera mais longa de uma só leitura na janela. Mostra uma falha de entrega do servidor. */
data class FlowReport(val bytes: Long, val waitedMs: Long, val elapsedMs: Long, val maxReadMs: Long = 0)

/**
 * Em MPEG-TS, começa perto do ponto mais recente: lê depressa o que o servidor tem em atraso, deita-o
 * fora e entrega só os últimos [keepBytes], que servem de buffer inicial. O leitor de TS volta a
 * alinhar-se sozinho, por isso os dados podem começar a meio de um pacote.
 */
@OptIn(UnstableApi::class)
class LiveEdgeDataSource(
    private val upstream: DataSource,
    private val keepBytes: Int,
    private val clock: () -> Long,
    private val onSkip: (SkipReport) -> Unit,
    private val onFlow: (FlowReport) -> Unit = {},
) : DataSource {
    private var flowStart = 0L
    private var flowBytes = 0L
    private var flowWaitedMs = 0L
    private var flowMaxReadMs = 0L
    private var pending = EMPTY
    private var pendingAt = 0
    private var skipDone = keepBytes <= 0
    private var ended = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        pending = EMPTY
        pendingAt = 0
        skipDone = keepBytes <= 0
        ended = false
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!skipDone) skipBacklog()
        if (pendingAt < pending.size) {
            val count = minOf(length, pending.size - pendingAt)
            pending.copyInto(buffer, offset, pendingAt, pendingAt + count)
            pendingAt += count
            return count
        }
        if (ended) return C.RESULT_END_OF_INPUT
        val readStart = clock()
        val count = upstream.read(buffer, offset, length)
        trackFlow(count, clock() - readStart, readStart)
        return count
    }

    /**
     * Mede quanto tempo a leitura fica à espera de dados. Quase nada significa que os dados já
     * estavam à espera, isto é, que ainda há atraso acumulado na ligação.
     */
    private fun trackFlow(count: Int, waitedMs: Long, now: Long) {
        if (flowStart == 0L) flowStart = now
        if (count > 0) flowBytes += count
        flowWaitedMs += waitedMs
        if (waitedMs > flowMaxReadMs) flowMaxReadMs = waitedMs
        val elapsed = now - flowStart
        if (elapsed >= FLOW_WINDOW_MS) {
            onFlow(FlowReport(flowBytes, flowWaitedMs, elapsed, flowMaxReadMs))
            flowStart = now
            flowBytes = 0
            flowWaitedMs = 0
            flowMaxReadMs = 0
        }
    }

    private fun skipBacklog() {
        skipDone = true
        val detector = BacklogDetector()
        val tail = TailBuffer(keepBytes)
        val chunk = ByteArray(CHUNK_BYTES)
        val startedAt = clock()
        var total = 0L
        while (true) {
            val count = upstream.read(chunk, 0, chunk.size)
            if (count == C.RESULT_END_OF_INPUT) {
                ended = true
                break
            }
            tail.write(chunk, count)
            total += count
            if (detector.onBytes(count, clock())) break
        }
        pending = tail.toByteArray()
        pendingAt = 0
        onSkip(SkipReport(total, pending.size, clock() - startedAt))
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    private companion object {
        val EMPTY = ByteArray(0)
        const val CHUNK_BYTES = 64 * 1024
        const val FLOW_WINDOW_MS = 10_000L
    }
}
