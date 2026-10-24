package com.esmeraldago.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.LocalAnimations
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Pantalla de carga sobre el fondo del título de Pokémon Esmeralda ([EmeraldTitleBackground], sin el logo).
 * No se cierra sola: cuando los datos están listos ([ready]) invita a tocar. Mientras se baja el fondo la primera
 * vez (o sin red) queda un cielo dibujado.
 */
@Composable
fun SplashScreen(ready: Boolean, status: String, onDone: () -> Unit) {
    var blink by remember { mutableStateOf(true) }
    val animate = LocalAnimations.current
    LaunchedEffect(animate) { while (animate) { delay(550); blink = !blink } }
    val context = LocalContext.current
    val title by produceState<EmeraldTitleArt?>(null) { value = EmeraldTitleArt.load(context) }

    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (ready) onDone() },
    ) {
        val art = title
        if (art != null) EmeraldTitleBackground(art) else TitleSky()
        Column(Modifier.align(Alignment.TopCenter).padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GbaText("EsmeraldaGO", size = 40.sp, color = Color(0xFFF8D838), shadow = Color(0xFF1C3C90))
            Spacer(Modifier.height(2.dp))
            GbaText("VERSIÓN ESMERALDA", size = 15.sp, color = Color(0xFFE8F8F0), shadow = Color(0xFF186848))
        }
        // Con el fondo del título, el texto va en el cielo, justo arriba de Rayquaza (no sobre su cara).
        val textBottom = if (art == null) 72.dp else with(LocalDensity.current) {
            val (scale, _, _) = EmeraldTitleArt.geometry(constraints.maxWidth, constraints.maxHeight)
            (EmeraldTitleArt.SCREEN_H * scale).toDp() + 8.dp
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = textBottom), horizontalAlignment = Alignment.CenterHorizontally) {
            val shown = blink || !ready
            GbaText(if (ready) "TOCÁ PARA CONTINUAR" else status, size = 16.sp,
                color = if (shown) Color(0xFFF8F8F8) else Color.Transparent, shadow = if (shown) Color(0xFF0C3B37) else Color.Transparent)
            Spacer(Modifier.height(10.dp))
            GbaText("compañero de Pokémon GO", size = 12.sp, color = Color(0xFFBFEBDD), shadow = Color(0xFF0C3B37))
        }
    }
}

/** Cielo del título: degradé verde azulado y franjas de nubes en "píxeles" grandes, claras y oscuras. */
@Composable
private fun TitleSky() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1E7A6E), Color(0xFF2E9C88), Color(0xFF268A78), Color(0xFF175F57))))
        val px = size.width / 80f
        // Cada franja ondula un poco, como las nubes del título; alternan claras y oscuras.
        for (band in 0 until 26) {
            val y = size.height * (band / 26f) + px * 2 * sin(band * 1.7f)
            val light = band % 3 != 1
            val color = if (light) Color(0x2E8FE0C8) else Color(0x33124A44)
            val start = ((band * 37) % 50) * px - px * 20
            val length = size.width * (0.45f + (band * 13 % 7) / 10f)
            var x = start
            while (x < start + length) {
                val h = px * (1 + ((x / px).toInt() + band) % 3)
                drawRect(color, Offset(x, y - h / 2), Size(px * 5, h))
                x += px * 5
            }
        }
    }
}
