package io.github.ricardofsc10.offsideplayer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

private const val MEMORY_CACHE_FRACTION = 0.15
private const val DISK_CACHE_BYTES = 150L * 1024 * 1024

/**
 * Carregador de imagens da app. O Shield tem pouca memória: a cache em memória é pequena e as
 * imagens ficam sobretudo em disco.
 */
fun buildImageLoader(context: PlatformContext, http: OkHttpClient): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_FRACTION).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("artwork").toOkioPath())
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .build()

/**
 * Capa ou logótipo. Enquanto não carrega, ou se falhar, mostra a inicial do [title].
 * O Coil reduz a imagem ao tamanho do [modifier] antes de a guardar em memória.
 *
 * @param opaque capas sem transparência usam metade da memória. Os logótipos precisam dela.
 */
@Composable
fun Artwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    opaque: Boolean = true,
    initialStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (!loaded) {
            Text(
                text = initialOf(title),
                style = initialStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (url != null) {
            val context = LocalContext.current
            val request = remember(url, opaque) {
                ImageRequest.Builder(context)
                    .data(url)
                    .apply { if (opaque) bitmapConfig(Bitmap.Config.RGB_565) }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Primeira letra ou algarismo do título, ignorando símbolos como "|" ou "[". */
fun initialOf(title: String): String =
    title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
