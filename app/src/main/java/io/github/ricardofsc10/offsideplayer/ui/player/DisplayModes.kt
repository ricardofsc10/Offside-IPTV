package io.github.ricardofsc10.offsideplayer.ui.player

import android.app.Activity
import io.github.ricardofsc10.offsideplayer.player.DisplayModeInfo
import io.github.ricardofsc10.offsideplayer.player.DisplayModeMatcher

/**
 * Passa a TV para uma frequência compatível com [frameRate]. A escolha mantém-se até sair da app,
 * para o ecrã não piscar ao mudar entre a lista e o player.
 */
fun applyFrameRate(activity: Activity, frameRate: Float) {
    val display = activity.display ?: return
    val current = display.mode.toInfo()
    val best = DisplayModeMatcher.bestMode(display.supportedModes.map { it.toInfo() }, current, frameRate) ?: return
    activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = best.id }
}

private fun android.view.Display.Mode.toInfo() =
    DisplayModeInfo(modeId, physicalWidth, physicalHeight, refreshRate)
