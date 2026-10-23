package com.esmeraldago.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Íconos pixel-art de estado (oscuro, shiny, con suerte, purificado), dibujados celda por celda.
 * Cada fila es un renglón de píxeles; cada carácter es un color de la paleta ('.' = transparente).
 */
private class PixelArt(val rows: List<String>, val palette: Map<Char, Color>)

private val FLAME = PixelArt(
    listOf(
        "...o....",
        "..o#o...",
        "..o#+o..",
        ".o##+o.o",
        ".o#+#o#o",
        "o##++##o",
        "o#+++#+o",
        "o#++++#o",
        ".o#++#o.",
        "..oooo..",
    ),
    mapOf('o' to Color(0xFF2A0E4A), '#' to Color(0xFF8030C8), '+' to Color(0xFFD090F8)),
)

private val SPARKLE = PixelArt(
    listOf(
        "...o...",
        "...+...",
        "..o+o..",
        "o++#++o",
        "..o+o..",
        "...+...",
        "...o...",
    ),
    mapOf('o' to Color(0xFFB07800), '+' to Color(0xFFF8D030), '#' to Color(0xFFFFFFFF)),
)

private val STAR = PixelArt(
    listOf(
        "....o....",
        "...o+o...",
        "ooo+++ooo",
        "o+++#+++o",
        ".o+++++o.",
        "..o+++o..",
        ".o++o++o.",
        ".o+o.o+o.",
        ".oo...oo.",
    ),
    mapOf('o' to Color(0xFF8A5A00), '+' to Color(0xFFF8C030), '#' to Color(0xFFFFF8D0)),
)

private val PURIFIED = PixelArt(
    listOf(
        "...o...",
        "..o+o..",
        ".o+#+o.",
        "o+###+o",
        ".o+#+o.",
        "..o+o..",
        "...o...",
    ),
    mapOf('o' to Color(0xFF2058A8), '+' to Color(0xFF78C8F8), '#' to Color(0xFFFFFFFF)),
)

@Composable
private fun PixelIcon(art: PixelArt, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val cols = art.rows.maxOf { it.length }
        val cell = minOf(this.size.width / cols, this.size.height / art.rows.size)
        art.rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                art.palette[c]?.let { drawRect(it, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f)) }
            }
        }
    }
}

@Composable fun ShadowIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(FLAME, size, modifier)
@Composable fun ShinyIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(SPARKLE, size, modifier)
@Composable fun LuckyIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(STAR, size, modifier)
@Composable fun PurifiedIcon(size: Dp, modifier: Modifier = Modifier) = PixelIcon(PURIFIED, size, modifier)
