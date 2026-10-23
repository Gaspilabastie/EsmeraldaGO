package com.esmeraldago.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.LocalAnimations
import kotlinx.coroutines.delay
import kotlin.math.sin

/** Arte oficial de Rayquaza (PokeAPI): se baja al abrir y queda en caché; no está en el repositorio. */
private const val RAYQUAZA_ART = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/384.png"

/**
 * Pasa el arte a tonos verde azulados según su luminosidad: queda una silueta oscura pero con el dibujo
 * (anillos, ojos, marcas) un poco más claro adentro, como el Rayquaza del título.
 */
private val TEAL_SHADE = androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
    0.05f, 0.10f, 0.02f, 0f, 6f,
    0.10f, 0.26f, 0.05f, 0f, 40f,
    0.09f, 0.22f, 0.05f, 0f, 36f,
    0f, 0f, 0f, 1f, 0f,
))

/**
 * Pantalla de carga como el título de Pokémon Esmeralda: cielo verde azulado con nubes en franjas y la silueta
 * oscura y translúcida de Rayquaza detrás del título. Se cierra sola cuando los datos están listos ([ready])
 * o al tocarla.
 */
@Composable
fun SplashScreen(ready: Boolean, status: String, onDone: () -> Unit) {
    var minTimeOver by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1200); minTimeOver = true }
    LaunchedEffect(ready, minTimeOver) { if (ready && minTimeOver) onDone() }
    var blink by remember { mutableStateOf(true) }
    val animate = LocalAnimations.current
    LaunchedEffect(animate) { while (animate) { delay(550); blink = !blink } }

    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (ready) onDone() },
    ) {
        val w = maxWidth
        TitleSky()
        // Silueta de Rayquaza: un borde claro detrás y la figura oscura encima, ambas translúcidas.
        Box(Modifier.align(Alignment.Center).offset(y = (-20).dp)) {
            AsyncImage(
                RAYQUAZA_ART, null,
                modifier = Modifier.size(w * 1.05f).scale(1.035f).alpha(0.35f),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(Color(0xFF8EE3C8), BlendMode.SrcIn),
            )
            AsyncImage(
                RAYQUAZA_ART, "Rayquaza",
                modifier = Modifier.size(w * 1.05f).alpha(0.6f),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.colorMatrix(TEAL_SHADE),
            )
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GbaText("EsmeraldaGO", size = 40.sp, color = Color(0xFFF8D838), shadow = Color(0xFF1C3C90))
            Spacer(Modifier.height(2.dp))
            GbaText("VERSIÓN ESMERALDA", size = 15.sp, color = Color(0xFFE8F8F0), shadow = Color(0xFF186848))
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val shown = blink || !ready
            GbaText(if (ready) "TOCÁ PARA EMPEZAR" else status, size = 16.sp,
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
