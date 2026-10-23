package com.esmeraldago.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.esmeraldago.app.ui.common.spriteUrls
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.LocalAnimations
import kotlinx.coroutines.delay

/**
 * Pantalla de carga como el título de Pokémon Esmeralda: cielo verde oscuro con nubes pixeladas y Rayquaza.
 * El sprite (el de Esmeralda, de PokeAPI) se baja al abrir y queda en caché: no está en el repositorio.
 * Se cierra sola cuando los datos están listos ([ready]) o al tocarla.
 */
@Composable
fun SplashScreen(ready: Boolean, status: String, onDone: () -> Unit) {
    var minTimeOver by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1200); minTimeOver = true }
    LaunchedEffect(ready, minTimeOver) { if (ready && minTimeOver) onDone() }
    var blink by remember { mutableStateOf(true) }
    val animate = LocalAnimations.current
    LaunchedEffect(animate) { while (animate) { delay(550); blink = !blink } }

    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (ready) onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF04140C), Color(0xFF0B3A24), Color(0xFF145A38))))
            // Nubes en bandas, de a "píxeles" grandes, como las del título de Esmeralda.
            val px = size.width / 90f
            val bands = listOf(0.18f to 0.35f, 0.30f to 0.55f, 0.62f to 0.40f, 0.74f to 0.65f, 0.86f to 0.30f)
            bands.forEachIndexed { i, (y, len) ->
                val color = if (i % 2 == 0) Color(0x332E9C64) else Color(0x2252C890)
                val startX = ((i * 23) % 40) * px
                var x = startX
                while (x < startX + size.width * len) {
                    val h = px * (2 + (x / px).toInt() % 3)
                    drawRect(color, Offset(x, size.height * y - h / 2), Size(px * 4, h))
                    x += px * 4
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AsyncImage(
                model = spriteUrls(384).first(),
                contentDescription = "Rayquaza",
                modifier = Modifier.size(280.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
            GbaText("EsmeraldaGO", size = 36.sp, color = Color(0xFFF8F0B0), shadow = Color(0xFF1E5A1E))
            Spacer(Modifier.height(4.dp))
            GbaText("compañero de Pokémon GO", size = 14.sp, color = Color(0xFF98D8B0), shadow = Color(0xFF04140C))
            Spacer(Modifier.height(36.dp))
            val shown = blink || !ready
            GbaText(if (ready) "TOCÁ PARA EMPEZAR" else status, size = 15.sp,
                color = if (shown) Color(0xFFF8F8F8) else Color.Transparent, shadow = if (shown) Color(0xFF04140C) else Color.Transparent)
        }
    }
}
