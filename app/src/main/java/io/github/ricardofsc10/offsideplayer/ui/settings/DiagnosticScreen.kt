package io.github.ricardofsc10.offsideplayer.ui.settings

import io.github.ricardofsc10.offsideplayer.ui.text.tr
import io.github.ricardofsc10.offsideplayer.R
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.ricardofsc10.offsideplayer.ui.AppViewModel
import io.github.ricardofsc10.offsideplayer.ui.DiagnosticExport
import io.github.ricardofsc10.offsideplayer.ui.RequestInitialFocus
import io.github.ricardofsc10.offsideplayer.ui.theme.BarButton
import io.github.ricardofsc10.offsideplayer.ui.theme.BroadcastHeader
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import io.github.ricardofsc10.offsideplayer.ui.theme.ScreenPadding
import io.github.ricardofsc10.offsideplayer.ui.theme.Section

/**
 * O relatório de diagnóstico num QR. O QR abre no telemóvel uma issue no GitHub já preenchida, e o
 * texto ao lado é exatamente o que vai nela, para o utilizador ver o que envia.
 */
@Composable
fun DiagnosticScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    var export by remember { mutableStateOf<DiagnosticExport?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    val back = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    RequestInitialFocus(back)
    LaunchedEffect(Unit) {
        val loaded = viewModel.diagnosticExport()
        export = loaded
        qr = qrBitmap(loaded.url)
    }

    Column(modifier = Modifier.fillMaxSize().padding(ScreenPadding)) {
        BroadcastHeader(current = Section.SETTINGS)
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(top = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(QR_SIZE).background(Color.White), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    qr?.let {
                        // Sem filtro, para os módulos do QR ficarem nítidos ao ampliar.
                        Image(it, contentDescription = tr(R.string.diagnostics_qr), filterQuality = FilterQuality.None, modifier = Modifier.size(QR_SIZE))
                    } ?: Text(if (export == null) tr(R.string.preparing) else tr(R.string.report_too_big), color = Pal.Ink)
                }
                BarButton(onClick = onBack, modifier = Modifier.focusRequester(back)) { Text(tr(R.string.back)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.diagnostics_send), style = MaterialTheme.typography.headlineSmall)
                Text(
                    tr(R.string.diagnostics_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Pal.Muted,
                )
                Text(
                    export?.text.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = Pal.Muted,
                )
            }
        }
    }
}

private val QR_SIZE = 380.dp

/** Pixel a pixel, com uma margem de um módulo. Null se o texto não couber num QR. */
private fun qrBitmap(text: String): ImageBitmap? {
    val matrix = try {
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1),
        )
    } catch (_: WriterException) {
        return null
    }
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
