package com.esmeraldago.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlin.math.floor

/**
 * Íconos pixel-art de estado (oscuro, shiny, con suerte, purificado, Dinamax, Gigamax), dibujados celda por celda.
 * Cada fila es un renglón de píxeles; cada carácter es un color de la paleta ('.' = transparente).
 */
private class PixelArt(val rows: List<String>, val palette: Map<Char, Color>)

private val FLAME = PixelArt(
    listOf(
        "....o......",
        "...oo......",
        "...o#o.....",
        "..o##o..o..",
        "..o#+#o.oo.",
        ".o##++#o#o.",
        ".o#+++##+o.",
        "o##++++#+#o",
        "o#+++++++#o",
        "o#++++++##o",
        ".o#+++++#o.",
        "..o##+##o..",
        "...ooooo...",
    ),
    mapOf('o' to Color(0xFF2A0E4A), '#' to Color(0xFF8030C8), '+' to Color(0xFFD090F8)),
)

/** Tres destellos (grande, mediano y chico), como el ícono de shiny del juego. */
private val SPARKLES = PixelArt(
    listOf(
        ".........o...",
        "........o+o..",
        ".......o+#+o.",
        "....o...o+o..",
        "...o+o...o...",
        "...o+o.......",
        ".oo+++oo...o.",
        "o+++#+++o.o#o",
        ".oo+++oo...o.",
        "...o+o.......",
        "...o+o.......",
        "....o........",
    ),
    // El centro amarillo claro (no blanco) para que no se pierda sobre el fondo de las ventanas.
    mapOf('o' to Color(0xFF8A5A00), '+' to Color(0xFFF8C820), '#' to Color(0xFFFFF2A0)),
)

/** Trébol de cuatro hojas con tallo: "con suerte" (la estrella dorada se confundía con la de favorito del juego). */
private val CLOVER = PixelArt(
    listOf(
        ".ooo...ooo...",
        "o+++o.o+++o..",
        "o+#++o+#++o..",
        "o++++o++++o..",
        ".o+++o+++o...",
        "..ooooooo....",
        ".o+++o+++o...",
        "o++++o++++o..",
        "o++++o++++o..",
        "o+++o.o+++o..",
        ".ooo...ooo.o.",
        "...........o.",
        "............o",
    ),
    mapOf('o' to Color(0xFF1E5A1E), '+' to Color(0xFF50B838), '#' to Color(0xFFB8F090)),
)

private val PURIFIED = PixelArt(
    listOf(
        ".....o.....",
        "....o+o....",
        "...o+#+o...",
        "..o+###+o..",
        ".o+##+##+o.",
        "o+##+++##+o",
        ".o+##+##+o.",
        "..o+###+o..",
        "...o+#+o...",
        "....o+o....",
        ".....o.....",
    ),
    mapOf('o' to Color(0xFF2058A8), '+' to Color(0xFF78C8F8), '#' to Color(0xFFFFFFFF)),
)

/** Nube roja de energía Max: "puede Dinamaxizar". */
private val MAX_CLOUD = PixelArt(
    listOf(
        "....ooo......",
        "...o+++o.ooo.",
        "..o++#++o+++o",
        ".oo+###++#++o",
        "o+++###+###+o",
        "o++#####+#++o",
        "o+++###+++++o",
        ".o+++++++++o.",
        "..ooooooooo..",
    ),
    mapOf('o' to Color(0xFF6A0A28), '+' to Color(0xFFE0306A), '#' to Color(0xFFFFB0C8)),
)

/** Nube Max más grande y oscura, con un núcleo dorado: "tiene forma Gigamax". */
private val GMAX_CLOUD = PixelArt(
    listOf(
        "...ooo...ooo.",
        "..o+++o.o+++o",
        ".o++#++o++#+o",
        "o++#*#+++#*#o",
        "o+##*##+##*#o",
        "o++###+++###o",
        "o+++#++#++#+o",
        ".o+++++++++o.",
        "o+++++#++++o.",
        ".ooooooooooo.",
    ),
    mapOf('o' to Color(0xFF4A0018), '+' to Color(0xFFB0103A), '#' to Color(0xFFF05080), '*' to Color(0xFFFFD030)),
)

/** Todas las figuras se dibujan sobre la misma grilla: así el píxel mide lo mismo en todos los íconos. */
private const val GRID = 13

@Composable
private fun PixelIcon(art: PixelArt, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val cols = art.rows.maxOf { it.length }
        // Celdas de un número entero de píxeles reales (si no, unos "píxeles" salen más gruesos que otros) y
        // del MISMO tamaño en todos los íconos: la celda sale de la grilla común, no de cada dibujo.
        val cell = floor(minOf(this.size.width, this.size.height) / GRID).coerceAtLeast(1f)
        val x0 = floor((this.size.width - cell * cols) / 2)
        val y0 = floor((this.size.height - cell * art.rows.size) / 2)
        art.rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                art.palette[c]?.let { drawRect(it, Offset(x0 + x * cell, y0 + y * cell), Size(cell, cell)) }
            }
        }
    }
}

@Composable fun ShadowIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(FLAME, size, modifier)
@Composable fun ShinyIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(SPARKLES, size, modifier)
@Composable fun LuckyIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(CLOVER, size, modifier)
@Composable fun PurifiedIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(PURIFIED, size, modifier)
@Composable fun DynamaxIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(MAX_CLOUD, size, modifier)
@Composable fun GigamaxIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(GMAX_CLOUD, size, modifier)
