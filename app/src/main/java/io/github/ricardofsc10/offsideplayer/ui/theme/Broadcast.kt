package io.github.ricardofsc10.offsideplayer.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import io.github.ricardofsc10.offsideplayer.R

/**
 * As cores do grafismo de transmissão: fundo azul-tinta, barras, verde de relvado para o foco e a
 * secção atual, e vermelho só para o direto.
 */
object Pal {
    val Ink = Color(0xFF0B1220)
    val Bar = Color(0xFF121B2C)
    val Bar2 = Color(0xFF1C2638)
    val Line = Color(0xFF26324A)
    val Text = Color(0xFFF5F7FA)
    val Muted = Color(0xFF9AA6BA)
    val Green = Color(0xFF19E08A)
    val Red = Color(0xFFFF4D3D)
}

val Barlow = FontFamily(
    Font(R.font.barlow_semicondensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semicondensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_semicondensed_bold, FontWeight.Bold),
    Font(R.font.barlow_semicondensed_extrabold, FontWeight.ExtraBold),
)

/** Algarismos da mesma largura, para as horas e os números ficarem alinhados. */
private const val TABULAR = "tnum"

private fun style(size: Int, weight: FontWeight, line: Int, spacing: Float = 0f) = TextStyle(
    fontFamily = Barlow,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
    fontFeatureSettings = TABULAR,
)

private val OffsideTypography = Typography(
    displayLarge = style(44, FontWeight.ExtraBold, 48),
    displayMedium = style(36, FontWeight.ExtraBold, 40),
    displaySmall = style(30, FontWeight.ExtraBold, 34),
    headlineLarge = style(26, FontWeight.Bold, 30),
    headlineMedium = style(23, FontWeight.Bold, 27),
    headlineSmall = style(19, FontWeight.Bold, 23),
    titleLarge = style(18, FontWeight.Bold, 22),
    titleMedium = style(15, FontWeight.Bold, 19),
    titleSmall = style(13, FontWeight.Bold, 17),
    bodyLarge = style(15, FontWeight.Medium, 20),
    bodyMedium = style(13, FontWeight.Medium, 18),
    bodySmall = style(12, FontWeight.Medium, 16),
    // Os rótulos vão em caixa alta, espaçados, como as siglas de um marcador.
    labelLarge = style(13, FontWeight.ExtraBold, 16, 1.2f),
    labelMedium = style(11, FontWeight.ExtraBold, 14, 1.1f),
    labelSmall = style(10, FontWeight.ExtraBold, 12, 1f),
)

private val OffsideColors = darkColorScheme(
    primary = Pal.Green,
    onPrimary = Pal.Ink,
    secondary = Pal.Green,
    onSecondary = Pal.Ink,
    background = Pal.Ink,
    onBackground = Pal.Text,
    surface = Pal.Ink,
    onSurface = Pal.Text,
    surfaceVariant = Pal.Bar2,
    onSurfaceVariant = Pal.Muted,
    inverseSurface = Pal.Green,
    inverseOnSurface = Pal.Ink,
    border = Pal.Line,
    error = Pal.Red,
    onError = Pal.Text,
)

@Composable
fun OffsideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OffsideColors, typography = OffsideTypography, content = content)
}

/** Cantos quase vivos, como as barras de um grafismo. */
val BarShape = RoundedCornerShape(2.dp)

/** Margem do ecrã de TV, dentro da zona segura. */
val ScreenPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

/**
 * Botão retangular: barra escura em repouso e verde com texto tinta quando tem foco.
 * Sem crescer, para o foco mudar num passo seco e barato.
 */
@Composable
fun BarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** A opção escolhida: barra mais clara com o traço verde por baixo. */
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ButtonDefaults.shape(shape = BarShape),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Pal.Line else Pal.Bar2,
            contentColor = Pal.Text,
            focusedContainerColor = Pal.Green,
            focusedContentColor = Pal.Ink,
            pressedContainerColor = Pal.Green,
            pressedContentColor = Pal.Ink,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 0.98f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(if (selected && !focused) Modifier.selectedMark() else Modifier),
    ) {
        ProvideTextStyle(MaterialTheme.typography.titleMedium) { content() }
    }
}

/**
 * Linha de lista: uma barra escura em repouso, verde com texto tinta com foco. A linha escolhida, sem
 * foco, fica numa barra mais clara.
 */
@Composable
fun BarListItem(
    selected: Boolean,
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        shape = ListItemDefaults.shape(shape = BarShape),
        colors = ListItemDefaults.colors(
            containerColor = Pal.Bar,
            contentColor = Pal.Text,
            selectedContainerColor = Pal.Bar2,
            selectedContentColor = Pal.Text,
            focusedContainerColor = Pal.Green,
            focusedContentColor = Pal.Ink,
            pressedContainerColor = Pal.Green,
            pressedContentColor = Pal.Ink,
            focusedSelectedContainerColor = Pal.Green,
            focusedSelectedContentColor = Pal.Ink,
            pressedSelectedContainerColor = Pal.Green,
            pressedSelectedContentColor = Pal.Ink,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1f, pressedScale = 0.99f),
        modifier = modifier,
    )
}

/** O traço verde por baixo da opção escolhida ou da secção atual. */
fun Modifier.selectedMark(): Modifier = drawWithContent {
    drawContent()
    val height = 3.dp.toPx()
    drawRect(Pal.Green, topLeft = Offset(0f, size.height - height), size = Size(size.width, height))
}

/** Título de secção em caixa alta, verde, como os separadores de um grafismo. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = Pal.Green,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** Etiqueta curta num bloco de cor, como "AO VIVO" ou o tempo de jogo. */
@Composable
fun Tag(text: String, color: Color = Pal.Red, contentColor: Color = Pal.Text, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        modifier = modifier.background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Barra fina de progresso de um programa ou de um filme. */
@Composable
fun ProgressRail(fraction: Float, modifier: Modifier = Modifier, color: Color = Pal.Green, track: Color = Pal.Bar2, height: Dp = 4.dp) {
    Box(modifier = modifier.height(height).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

/**
 * Bloco com o nome curto do canal, branco com texto tinta, como a sigla de uma equipa no
 * marcador. Tira o país e a qualidade: "PT: Arena 1 FHD" fica "ARENA 1".
 */
@Composable
fun ChannelTagBlock(name: String, modifier: Modifier = Modifier, focused: Boolean = false) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(if (focused) Pal.Ink else Pal.Text).padding(horizontal = 6.dp),
    ) {
        Text(
            channelTag(name),
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Pal.Text else Pal.Ink,
            maxLines = 2,
        )
    }
}

private val countryPrefix = Regex("^\\s*[A-Za-z]{2,4}\\s*[:|\\-]\\s*")
private val qualityTokens = setOf("HD", "FHD", "UHD", "SD", "4K", "HEVC", "H265", "RAW", "50FPS", "60FPS")

/** Nome curto do canal para os blocos: sem o país à frente e sem a qualidade no fim. */
fun channelTag(name: String): String {
    val withoutCountry = countryPrefix.replace(name, "")
    val words = withoutCountry.split(Regex("\\s+")).filter { it.isNotBlank() }
    val kept = words.filterNot { word ->
        val clean = word.trim('(', ')', '[', ']', '|').uppercase()
        clean in qualityTokens || clean.all { it in "ᴴᴰᶠᴿᴬᵂᵁˢ⁴ᴷ" }
    }
    return (kept.ifEmpty { words }).joinToString(" ").uppercase().take(18)
}

/** Linha fina entre barras. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
}

/** Espaço vertical e horizontal entre blocos encaixados. */
val BarGap: Arrangement.HorizontalOrVertical = Arrangement.spacedBy(4.dp)
