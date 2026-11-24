package io.github.ricardofsc10.offsideplayer.ui.components

import io.github.ricardofsc10.offsideplayer.ui.theme.BarShape
import io.github.ricardofsc10.offsideplayer.ui.theme.Pal
import androidx.compose.foundation.background
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Campo de texto para o comando. Fora de edição é um botão normal, para o D-pad percorrer o
 * formulário sem abrir o teclado. O OK entra em edição, e o Back ou as setas saem.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    hidden: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    /** O que se mostra fora da edição, por exemplo um endereço sem as credenciais. */
    display: ((String) -> String)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var moveAfterEditing by remember { mutableStateOf<FocusDirection?>(null) }
    var wasEditing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }
    val textFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    val shape = BarShape

    fun finishEditing(move: FocusDirection? = null) {
        moveAfterEditing = move
        editing = false
    }

    LaunchedEffect(editing) {
        if (editing) {
            wasEditing = true
            textFocus.requestFocus()
            keyboard?.show()
        } else if (wasEditing) {
            wasEditing = false
            buttonFocus.requestFocus()
            moveAfterEditing?.let { focusManager.moveFocus(it) }
            moveAfterEditing = null
        }
    }
    BackHandler(enabled = editing) { finishEditing() }

    // O Back que fecha o teclado não chega à app, por isso sai da edição quando o teclado desaparece.
    val view = LocalView.current
    DisposableEffect(editing, view) {
        var keyboardSeen = false
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            if (visible) keyboardSeen = true else if (keyboardSeen && editing) finishEditing()
        }
        if (editing) view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface),
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions { finishEditing(FocusDirection.Down) },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Pal.Bar2, shape)
                            .border(3.dp, colors.primary, shape)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) { inner() }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(textFocus)
                    .onPreviewKeyEvent { event ->
                        // O teclado aberto recebe as teclas primeiro, por isso isto só corre depois de o fechar.
                        val move = when (event.key) {
                            Key.DirectionDown -> FocusDirection.Down
                            Key.DirectionUp -> FocusDirection.Up
                            else -> null
                        }
                        if (move != null && event.type == KeyEventType.KeyDown) finishEditing(move)
                        move != null
                    },
            )
        } else {
            // O Surface só mostra o foco que vê chegar. Um foco pedido ao abrir o ecrã pode chegar
            // antes, e o campo ficava com foco mas sem o mostrar. Por isso o aspeto segue o foco real.
            val container = if (focused) Pal.Bar2 else Pal.Bar
            val border = if (focused) {
                Border(BorderStroke(3.dp, colors.primary), shape = shape)
            } else {
                Border(BorderStroke(1.dp, colors.border), shape = shape)
            }
            Surface(
                onClick = { editing = true },
                shape = ClickableSurfaceDefaults.shape(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(containerColor = container, focusedContainerColor = container),
                border = ClickableSurfaceDefaults.border(border = border, focusedBorder = border),
                modifier = modifier
                    .fillMaxWidth()
                    .focusRequester(buttonFocus)
                    .onFocusChanged { focused = it.isFocused },
            ) {
                Text(
                    text = when {
                        hidden -> "•".repeat(value.length)
                        display != null -> display(value)
                        else -> value
                    }.ifEmpty { " " },
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
