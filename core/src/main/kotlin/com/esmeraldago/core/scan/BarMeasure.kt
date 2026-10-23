package com.esmeraldago.core.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Mide una fila de píxeles (ARGB) que cruza una barra de la valoración:
 * - fondo: parecido al color de la tarjeta (se muestrea fuera de la barra);
 * - lleno: naranja/rojo (matiz 0–50° o ≥ 340°) y saturado;
 * - vacío: gris (poca saturación) distinto del fondo.
 * La fracción se calcula entre el primer y el último píxel que no es fondo.
 */
object BarMeasure {
    data class Sample(val filled: Int, val empty: Int) {
        val total get() = filled + empty
        val fraction get() = if (total == 0) 0f else filled.toFloat() / total
    }

    fun measure(row: IntArray, background: Int): Sample {
        var first = -1
        var last = -1
        var bgRun = 0
        // La barra termina en el primer tramo largo de fondo (los separadores entre segmentos son cortos):
        // así no se cuenta lo que hay más allá de la tarjeta.
        val maxGap = maxOf(12, row.size / 25)
        for (i in row.indices) {
            if (isBackground(row[i], background)) {
                if (first >= 0 && ++bgRun >= maxGap) break
            } else {
                if (first < 0) first = i
                last = i
                bgRun = 0
            }
        }
        if (first < 0) return Sample(0, 0)
        var filled = 0
        var empty = 0
        for (i in first..last) {
            val c = row[i]
            when {
                isBackground(c, background) -> Unit // separadores entre segmentos
                isFilled(c) -> filled++
                isTrack(c) -> empty++
            }
        }
        return Sample(filled, empty)
    }

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    fun isBackground(c: Int, bg: Int): Boolean =
        abs(r(c) - r(bg)) < 14 && abs(g(c) - g(bg)) < 14 && abs(b(c) - b(bg)) < 14

    fun isFilled(c: Int): Boolean {
        val (h, s, v) = hsv(c)
        return s > 0.35f && v > 0.45f && (h <= 50f || h >= 340f)
    }

    fun isTrack(c: Int): Boolean {
        val (_, s, v) = hsv(c)
        return s < 0.18f && v in 0.55f..0.97f
    }

    fun hsv(c: Int): Triple<Float, Float, Float> {
        val rf = r(c) / 255f; val gf = g(c) / 255f; val bf = b(c) / 255f
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d) % 6f)
            mx == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }.let { if (it < 0) it + 360f else it }
        return Triple(h, if (mx == 0f) 0f else d / mx, mx)
    }
}
