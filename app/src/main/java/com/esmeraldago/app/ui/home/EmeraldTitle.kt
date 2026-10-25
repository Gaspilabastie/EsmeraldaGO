package com.esmeraldago.app.ui.home

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.esmeraldago.app.ui.theme.LocalAnimations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.zip.InflaterInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fondo de la pantalla de título de Pokémon Esmeralda, sin el logo: las capas originales (Rayquaza y nubes) de la
 * decompilación pret/pokeemerald. Se bajan la primera vez y quedan en el teléfono; no están en el repositorio.
 */
class EmeraldTitleArt private constructor(
    private val ray: ByteArray,     // 256×256 índices de paleta (0 = transparente)
    private val clouds: ByteArray,  // 256×256
    private val palette: IntArray,  // 16 colores RGB
) {
    /** Color de cada franja del cielo (columna 0 de la imagen), para estirarla a los costados en pantallas anchas. */
    private val bandColor = IntArray(SCREEN_H) { y -> palette[ray[y * 256].toInt().coerceAtLeast(1)] }

    /**
     * Dibuja un cuadro de [vw]×[vh] píxeles GBA de la escena (240×160, anclada abajo), desde la columna [cropX].
     * Como en el juego (title_screen.c): las nubes se mezclan 6/16 sobre 15/16, suben 1 px cada 4 cuadros con una
     * ondulación horizontal, y las marcas de Rayquaza laten entre el color del cuerpo y amarillo.
     */
    fun render(out: IntArray, vw: Int, vh: Int, frame: Int, cropX: Int) {
        val top = vh - SCREEN_H
        val glow = (cos(2 * PI * frame / 256) * 0.5 + 0.5)
        // RGB de 5 bits del GBA: (31 - i·31, 31 - i·22, 12); con i = 1 se confunde con el cuerpo.
        val marking = rgb((31 - glow * 31).roundToInt() * 255 / 31, (31 - glow * 22).roundToInt() * 255 / 31, 12 * 255 / 31)
        val cloudY = frame / 4
        for (y in 0 until vh) {
            val sy = y - top
            val wave = (4 * sin(2 * PI * (y + frame / 2) / 64.0)).roundToInt()
            val cy = ((y + cloudY) and 255) * 256
            for (x in 0 until vw) {
                val wx = x + cropX
                val base = when {
                    sy < 0 -> bandColor[0]
                    wx < 0 || wx >= SCREEN_W -> bandColor[sy]
                    else -> when (val i = ray[sy * 256 + wx].toInt()) {
                        0 -> bandColor[sy]
                        15 -> marking
                        else -> palette[i]
                    }
                }
                val c = clouds[cy + ((wx + wave) and 255)].toInt()
                out[y * vw + x] = if (c == 0) base or OPAQUE else blend(palette[c], base)
            }
        }
    }

    companion object {
        const val SCREEN_W = 240
        const val SCREEN_H = 160
        /** Cuadros (a 60 por segundo) de una ida y vuelta del paneo: unos 24 segundos. */
        private const val PAN_FRAMES = 60 * 24
        /** Centro de la cara de Rayquaza (los ojos están en x 116–139) y ancho que tiene que quedar siempre a la vista. */
        private const val FACE_X = 128
        private const val FACE_W = 48

        /**
         * Columna (con fracción) donde empieza la vista en el cuadro [frame]: un vaivén leve alrededor de la cara,
         * que nunca sale de cuadro. Si la pantalla es más ancha que la escena, queda centrada.
         */
        fun panX(vw: Int, frame: Int): Double {
            if (vw >= SCREEN_W) return (SCREEN_W - vw) / 2.0
            val center = (FACE_X - vw / 2.0).coerceIn(0.0, (SCREEN_W - vw).toDouble())
            val amp = minOf((vw - FACE_W) / 2.0, center, SCREEN_W - vw - center).coerceIn(0.0, 14.0)
            return center + amp * sin(2 * PI * frame / PAN_FRAMES)
        }
        /**
         * Escala entera (píxeles reales por píxel GBA) y tamaño visible en píxeles GBA para un lienzo de [wPx]×[hPx].
         * La escena ocupa todo el alto, como en la GBA: en un teléfono se ve la parte central (cabeza y anillos)
         * enorme, y se recortan los costados.
         */
        fun geometry(wPx: Int, hPx: Int): Triple<Int, Int, Int> {
            val scale = max(1, ceil(hPx / SCREEN_H.toFloat()).toInt())
            return Triple(scale, ceil(wPx / scale.toFloat()).toInt(), ceil(hPx / scale.toFloat()).toInt())
        }
        private const val OPAQUE = 0xFF000000.toInt()
        private const val BASE = "https://raw.githubusercontent.com/pret/pokeemerald/master/graphics/title_screen/"
        private val FILES = listOf("rayquaza.png", "rayquaza.bin", "clouds.png", "clouds.bin", "rayquaza_and_clouds.pal")

        @Volatile private var cached: EmeraldTitleArt? = null

        /** Baja (una sola vez) y arma las capas; null si no hay red la primera vez o algo vino roto. */
        suspend fun load(context: Context): EmeraldTitleArt? = cached ?: withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "emerald_title").apply { mkdirs() }
                val bytes = FILES.associateWith { name ->
                    val f = File(dir, name)
                    if (!f.exists() || f.length() == 0L) {
                        val tmp = File(dir, "$name.part")
                        tmp.writeBytes(download(BASE + name))
                        tmp.renameTo(f)
                    }
                    f.readBytes()
                }
                EmeraldTitleArt(
                    layer(bytes.getValue("rayquaza.png"), bytes.getValue("rayquaza.bin")),
                    layer(bytes.getValue("clouds.png"), bytes.getValue("clouds.bin")),
                    jascPalette(bytes.getValue("rayquaza_and_clouds.pal").decodeToString()),
                ).also { cached = it }
            }.getOrNull()
        }

        private fun download(url: String): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            try {
                check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                return c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }

        /** Arma un fondo de 32×32 baldosas: cada entrada del mapa es baldosa (10 bits) + espejo H/V (bits 10 y 11). */
        private fun layer(png: ByteArray, map: ByteArray): ByteArray {
            val (w, h, px) = indexedPng(png)
            val perRow = w / 8
            val out = ByteArray(256 * 256)
            for (e in 0 until 1024) {
                val v = (map[2 * e].toInt() and 0xFF) or ((map[2 * e + 1].toInt() and 0xFF) shl 8)
                val tile = v and 0x3FF
                val hf = v and 0x400 != 0
                val vf = v and 0x800 != 0
                val tx = tile % perRow * 8
                val ty = tile / perRow * 8
                val ox = e % 32 * 8
                val oy = e / 32 * 8
                for (y in 0 until 8) for (x in 0 until 8) {
                    val sx = tx + if (hf) 7 - x else x
                    val sy = ty + if (vf) 7 - y else y
                    if (sy < h) out[(oy + y) * 256 + ox + x] = px[sy * w + sx]
                }
            }
            return out
        }

        /** PNG indexado de 4 bits (como los de pret) → (ancho, alto, índice por píxel). */
        private fun indexedPng(bytes: ByteArray): Triple<Int, Int, ByteArray> {
            val buf = ByteBuffer.wrap(bytes, 8, bytes.size - 8)
            var w = 0; var h = 0; var depth = 0; var colorType = 0
            val idat = ByteArrayOutputStream()
            while (buf.remaining() >= 12) {
                val len = buf.int
                val type = ByteArray(4).also { buf.get(it) }.decodeToString()
                val data = ByteArray(len).also { buf.get(it) }
                buf.int // CRC
                when (type) {
                    "IHDR" -> ByteBuffer.wrap(data).let { w = it.int; h = it.int; depth = data[8].toInt(); colorType = data[9].toInt() }
                    "IDAT" -> idat.write(data)
                    "IEND" -> break
                }
            }
            require(depth == 4 && colorType == 3) { "PNG no indexado de 4 bits" }
            val raw = InflaterInputStream(idat.toByteArray().inputStream()).readBytes()
            val stride = (w * depth + 7) / 8
            val px = ByteArray(w * h)
            var prev = ByteArray(stride)
            for (y in 0 until h) {
                val filter = raw[y * (stride + 1)].toInt()
                val line = raw.copyOfRange(y * (stride + 1) + 1, (y + 1) * (stride + 1))
                for (i in 0 until stride) {
                    val a = if (i > 0) line[i - 1].toInt() and 0xFF else 0
                    val b = prev[i].toInt() and 0xFF
                    val c = if (i > 0) prev[i - 1].toInt() and 0xFF else 0
                    val add = when (filter) {
                        1 -> a; 2 -> b; 3 -> (a + b) / 2
                        4 -> { val p = a + b - c; val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
                            if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                        else -> 0
                    }
                    line[i] = (line[i] + add).toByte()
                }
                for (x in 0 until w) {
                    val byte = line[x / 2].toInt() and 0xFF
                    px[y * w + x] = (if (x % 2 == 0) byte shr 4 else byte and 0x0F).toByte()
                }
                prev = line
            }
            return Triple(w, h, px)
        }

        /** Paleta JASC-PAL: tres líneas de encabezado y después "R G B" por color. */
        private fun jascPalette(text: String): IntArray {
            val colors = text.lines().drop(3).filter { it.isNotBlank() }.map { l ->
                val (r, g, b) = l.trim().split(Regex("\\s+")).map { it.toInt() }
                rgb(r, g, b)
            }
            require(colors.size >= 16) { "paleta incompleta" }
            return colors.take(16).toIntArray()
        }

        private fun rgb(r: Int, g: Int, b: Int) = OPAQUE or (r shl 16) or (g shl 8) or b

        /** Mezcla alfa del GBA: nubes 6/16 + fondo 15/16 por canal, con tope. */
        private fun blend(top: Int, base: Int): Int {
            fun ch(shift: Int) = minOf(255, ((top shr shift and 0xFF) * 6 + (base shr shift and 0xFF) * 15) / 16) shl shift
            return OPAQUE or ch(16) or ch(8) or ch(0)
        }
    }
}

/**
 * Fondo animado del título: la escena a todo el alto, en píxeles GBA enteros escalados sin suavizado, con un
 * vaivén leve de lado a lado que siempre deja ver la cara (Rayquaza enorme, como en la GBA). Un cuadro del juego
 * por cuadro de pantalla: nubes y marcas van a su velocidad original.
 */
@Composable
fun EmeraldTitleBackground(art: EmeraldTitleArt, modifier: Modifier = Modifier) {
    val animate = LocalAnimations.current
    // Arranca con las marcas encendidas (cuadro 128 del ciclo de 256): sin animaciones queda ese cuadro fijo.
    var frame by remember { mutableIntStateOf(128) }
    LaunchedEffect(animate) {
        while (animate) {
            withFrameNanos { }
            frame += 1
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wPx = max(1, constraints.maxWidth)
        val hPx = max(1, constraints.maxHeight)
        val (scale, vw, vh) = EmeraldTitleArt.geometry(wPx, hPx)
        // Una columna de más: el paneo avanza de a fracciones de píxel GBA corriendo la imagen, no a saltos.
        val rw = vw + 1
        val bitmap = remember(rw, vh) { Bitmap.createBitmap(rw, vh, Bitmap.Config.ARGB_8888) }
        val pixels = remember(rw, vh) { IntArray(rw * vh) }
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        Canvas(Modifier.fillMaxSize()) {
            val pan = EmeraldTitleArt.panX(vw, frame - 128)
            val cropX = kotlin.math.floor(pan).toInt()
            art.render(pixels, rw, vh, frame, cropX)
            bitmap.setPixels(pixels, 0, rw, 0, 0, rw, vh)
            // Anclado abajo: si sobra un poco por el redondeo, se recorta arriba (cielo).
            val dx = -((pan - cropX) * scale).roundToInt()
            drawImage(image, dstOffset = IntOffset(dx, hPx - vh * scale), dstSize = IntSize(rw * scale, vh * scale),
                filterQuality = FilterQuality.None)
        }
    }
}
