package io.github.ricardofsc10.offsideplayer.ui.components

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import io.github.ricardofsc10.offsideplayer.data.library.ChannelRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Usa a sequência real do comando: tecla em baixo, repetições, sinal de pressão longa e largar. */
@RunWith(AndroidJUnit4::class)
class ChannelMenuKeyTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val channel = ChannelRow(1, 1, "Canal", "a", null, 0, false)
    private var menuOpen = false
    private var toggles = 0
    private var clicks = 0

    @Before
    fun setUp() {
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(false) }
                menuOpen = open
                val focus = remember { FocusRequester() }
                ChannelItem(
                    channel = channel,
                    onClick = { clicks++ },
                    onLongClick = { open = true },
                    modifier = Modifier.focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
                if (open) {
                    ChannelMenu(
                        channel = channel,
                        onToggleFavorite = {
                            toggles++
                            open = false
                        },
                        onDismiss = { open = false },
                    )
                }
            }
        }
        settle()
    }

    @Test
    fun largarDepoisDePressaoLongaNaoMarcaFavorito() {
        holdCenter(repeatsAfterMenu = 12)

        assertTrue("o menu devia continuar aberto", menuOpen)
        assertEquals(0, toggles)
        assertEquals(0, clicks)
    }

    @Test
    fun novoToqueDepoisDePressaoLongaMarcaUmaVez() {
        holdCenter(repeatsAfterMenu = 12)

        pressCenter()

        assertEquals(1, toggles)
        assertFalse(menuOpen)
    }

    @Test
    fun largarLogoQueOMenuAbreNaoObrigaADoisToques() {
        holdCenter(repeatsAfterMenu = 0)
        assertEquals(0, toggles)

        pressCenter()

        assertEquals(1, toggles)
    }

    @Test
    fun daParaCancelarDepoisDePressaoLonga() {
        holdCenter(repeatsAfterMenu = 12)

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        pressCenter()

        assertEquals(0, toggles)
        assertFalse(menuOpen)
    }

    private fun holdCenter(repeatsAfterMenu: Int) {
        val code = KeyEvent.KEYCODE_DPAD_CENTER
        val downTime = SystemClock.uptimeMillis()
        send(downTime, KeyEvent.ACTION_DOWN, code, repeat = 0)
        SystemClock.sleep(500)
        send(downTime, KeyEvent.ACTION_DOWN, code, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS)
        settle()
        for (repeat in 2 until 2 + repeatsAfterMenu) {
            send(downTime, KeyEvent.ACTION_DOWN, code, repeat)
            SystemClock.sleep(50)
        }
        send(downTime, KeyEvent.ACTION_UP, code, repeat = 0)
        settle()
    }

    private fun pressCenter() = press(KeyEvent.KEYCODE_DPAD_CENTER)

    private fun press(code: Int) {
        val downTime = SystemClock.uptimeMillis()
        send(downTime, KeyEvent.ACTION_DOWN, code, repeat = 0)
        SystemClock.sleep(50)
        send(downTime, KeyEvent.ACTION_UP, code, repeat = 0)
        settle()
    }

    private fun send(downTime: Long, action: Int, code: Int, repeat: Int, flags: Int = 0) {
        instrumentation.sendKeySync(
            KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                code,
                repeat,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                flags or KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_DPAD,
            ),
        )
    }

    private fun settle() {
        compose.waitForIdle()
        SystemClock.sleep(400)
        compose.waitForIdle()
    }
}
