package io.github.ricardofsc10.offsideplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEdgeDataSourceTest {

    @Test
    fun `deteta a queda de ritmo depois do conteúdo em atraso`() {
        val detector = BacklogDetector()
        var now = 0L
        // 5 MB/s durante 400 ms: ainda há atraso.
        repeat(8) {
            now += 50
            assertFalse(detector.onBytes(250_000, now))
        }
        // 1 MB/s: chegou ao direto.
        var detected = false
        repeat(6) {
            now += 50
            detected = detected || detector.onBytes(50_000, now)
        }
        assertTrue(detected)
        assertTrue(now < 1_000)
    }

    @Test
    fun `não para antes de observar o tempo mínimo`() {
        val detector = BacklogDetector()
        assertFalse(detector.onBytes(1_000_000, 100))
        assertFalse(detector.onBytes(10, 210))
    }

    @Test
    fun `ritmo constante termina no limite de tempo`() {
        val detector = BacklogDetector()
        var now = 0L
        var done = false
        while (!done && now < 5_000) {
            now += 50
            done = detector.onBytes(100_000, now)
        }
        assertTrue(done)
        assertEquals(1_550L, now)
    }

    @Test
    fun `memória guarda só os últimos bytes por ordem`() {
        val tail = TailBuffer(4)
        tail.write(byteArrayOf(1, 2, 3), 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), tail.toByteArray())
        tail.write(byteArrayOf(4, 5, 6), 3)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), tail.toByteArray())
        tail.write(byteArrayOf(7, 8, 9, 10, 11, 12), 6)
        assertArrayEquals(byteArrayOf(9, 10, 11, 12), tail.toByteArray())
    }

    @Test
    fun `memória respeita a quantidade indicada`() {
        val tail = TailBuffer(4)
        tail.write(byteArrayOf(1, 2, 9, 9), 2)
        assertArrayEquals(byteArrayOf(1, 2), tail.toByteArray())
    }

    @Test
    fun `descarta o atraso e entrega o final seguido do resto do stream`() {
        // Três blocos seguidos com tempo a passar até esgotar o limite, e depois dados novos.
        val upstream = ScriptedSource(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), byteArrayOf(7, 8, 9)))
        var now = 0L
        var report: SkipReport? = null
        val source = LiveEdgeDataSource(upstream, keepBytes = 4, clock = { now.also { now += 600 } }, onSkip = { report = it })

        assertArrayEquals(byteArrayOf(6, 7, 8, 9), readAll(source))
        assertEquals(9L, report?.readBytes)
        assertEquals(4, report?.keptBytes)
    }

    @Test
    fun `sem bytes a guardar não mexe no stream`() {
        val upstream = ScriptedSource(listOf(byteArrayOf(1, 2), byteArrayOf(3)))
        var reported = false
        val source = LiveEdgeDataSource(upstream, keepBytes = 0, clock = { 0L }, onSkip = { reported = true })

        assertArrayEquals(byteArrayOf(1, 2, 3), readAll(source))
        assertFalse(reported)
    }

    private fun readAll(source: DataSource): ByteArray {
        val result = mutableListOf<Byte>()
        val buffer = ByteArray(16)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return result.toByteArray()
            result += buffer.take(count)
        }
    }

    private class ScriptedSource(chunks: List<ByteArray>) : DataSource {
        private val remaining = ArrayDeque(chunks)

        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()
        override fun getUri(): Uri? = null
        override fun close() = Unit

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val chunk = remaining.removeFirstOrNull() ?: return C.RESULT_END_OF_INPUT
            chunk.copyInto(buffer, offset)
            return chunk.size
        }
    }
}
