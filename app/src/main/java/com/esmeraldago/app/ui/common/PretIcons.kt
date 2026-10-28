package com.esmeraldago.app.ui.common

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/*
 * Gráficos originales de los juegos (proyectos de pret). Como el fondo de Rayquaza, se descargan al usarlos y nunca
 * se suben al repositorio; [SheetCell] recorta el que se usa de cada hoja.
 */

/** Íconos del menú de inicio de Platino: una columna de celdas de 32×32 separadas por una línea de 1 píxel. */
const val PLATINUM_MENU_ICONS = "https://raw.githubusercontent.com/pret/pokeplatinum/main/res/graphics/start_menu/icons.png"

/** Cintas del PokéNav de Esmeralda: una columna de celdas de 32×32; la primera es la Champion Ribbon. */
const val EMERALD_RIBBONS = "https://raw.githubusercontent.com/pret/pokeemerald/master/graphics/pokenav/ribbons/icons_big.png"

/** Recorta una celda de una hoja y vuelve transparente su color de fondo (el de la esquina de la celda). */
class SheetCell(private val left: Int, private val top: Int, private val width: Int, private val height: Int) : Transformation {
    override val cacheKey = "sheet-cell-$left-$top-$width-$height-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val px = IntArray(width * height)
        input.getPixels(px, 0, width, left, top, width, height)
        val key = px[0]
        for (i in px.indices) if (px[i] == key) px[i] = 0
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }
}

/** La mochila del chico en el menú de Platino (celda 2: 0 Pokédex, 1 Pokémon, 2 Mochila…). */
val PlatinumBag = SheetCell(0, 2 * 33, 32, 32)

/** Champion Ribbon de Esmeralda. */
val ChampionRibbon = SheetCell(0, 0, 32, 32)

/** Poké Balls de la barra que aparece al empezar un combate en Esmeralda: celdas de 8×8, la primera es la sana. */
const val EMERALD_BATTLE_BALLS = "https://raw.githubusercontent.com/pret/pokeemerald/master/graphics/battle_interface/ball_display.png"

/** El equipo de 6: la Poké Ball sana repetida en dos filas de 3, con 1 píxel entre cada una. */
object TeamOfSix : Transformation {
    override val cacheKey = "team-of-six-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val cell = 8
        val ball = IntArray(cell * cell)
        input.getPixels(ball, 0, cell, 0, 0, cell, cell)
        val key = ball[0]
        val w = 3 * cell + 2
        val h = 2 * cell + 1
        val out = IntArray(w * h)
        for (i in 0 until 6) {
            val ox = (i % 3) * (cell + 1)
            val oy = (i / 3) * (cell + 1)
            for (y in 0 until cell) for (x in 0 until cell) {
                val p = ball[y * cell + x]
                if (p != key) out[(oy + y) * w + ox + x] = p
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
