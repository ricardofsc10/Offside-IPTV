package io.github.ricardofsc10.offsideplayer.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {

    @Test
    fun `sem esquema assume HTTP`() {
        assertEquals("http://example.test:8080", ServerAddress.normalize("example.test:8080"))
    }

    @Test
    fun `mantém HTTPS e retira espaços e barras`() {
        assertEquals("https://example.test", ServerAddress.normalize("  https://example.test/  "))
    }

    @Test
    fun `retira player_api e parâmetros`() {
        assertEquals(
            "http://example.test:8080",
            ServerAddress.normalize("http://example.test:8080/player_api.php?username=test&password=test"),
        )
    }

    @Test
    fun `mantém subpasta`() {
        assertEquals("http://example.test/painel", ServerAddress.normalize("http://example.test/painel/"))
    }

    @Test
    fun `rejeita endereços inválidos`() {
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("   "))
        assertNull(ServerAddress.normalize("ftp://example.test"))
        assertNull(ServerAddress.normalize("não é um servidor"))
    }

    @Test
    fun `lê o servidor e a conta de um link M3U`() {
        val link = "http://example.test:8080/get.php?username=test&password=test%26more&type=m3u_plus&output=ts"
        assertEquals("http://example.test:8080", ServerAddress.normalize(link))
        assertEquals("test" to "test&more", ServerAddress.credentialsIn(link))
    }

    @Test
    fun `sem utilizador e password não há conta no link`() {
        assertNull(ServerAddress.credentialsIn("http://example.test:8080"))
        assertNull(ServerAddress.credentialsIn("http://example.test/get.php?username=test"))
    }
}
