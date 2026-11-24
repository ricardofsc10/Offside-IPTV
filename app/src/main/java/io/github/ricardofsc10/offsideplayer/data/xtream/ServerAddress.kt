package io.github.ricardofsc10.offsideplayer.data.xtream

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerAddress {
    /**
     * Normaliza o que o utilizador escreve: sem esquema assume HTTP, e retira barras finais,
     * parâmetros, `player_api.php` e `get.php` (o link M3U). Devolve `null` se não for um endereço válido.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null

        val segments = url.pathSegments.filter { it.isNotEmpty() && it !in API_FILES }
        return url.newBuilder()
            .query(null)
            .fragment(null)
            .encodedPath("/")
            .apply { segments.forEach(::addPathSegment) }
            .build()
            .toString()
            .trimEnd('/')
    }

    /**
     * O utilizador e a password de um link M3U do fornecedor, como
     * `http://servidor:8080/get.php?username=…&password=…&type=m3u_plus`. Devolve `null` se o
     * endereço não os tiver.
     */
    fun credentialsIn(input: String): Pair<String, String>? {
        val trimmed = input.trim()
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        val username = url.queryParameter("username")?.trim().orEmpty()
        val password = url.queryParameter("password").orEmpty()
        return if (username.isEmpty() || password.isEmpty()) null else username to password
    }

    private val API_FILES = setOf("player_api.php", "get.php", "xmltv.php")
}
