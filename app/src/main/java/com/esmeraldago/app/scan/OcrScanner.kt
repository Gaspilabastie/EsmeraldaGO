package com.esmeraldago.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.esmeraldago.core.scan.AppraisalBars
import com.esmeraldago.core.scan.BarMeasure
import com.esmeraldago.core.scan.OcrChar
import com.esmeraldago.core.scan.OcrLine
import com.esmeraldago.core.scan.ScanResult
import com.esmeraldago.core.scan.ScreenParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * OCR de capturas de Pokémon GO. Recibe cualquier Bitmap, así que sirve tanto para capturas
 * elegidas de la galería como (más adelante) para un escaneo automático de pantalla.
 */
class OcrScanner(private val context: Context) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Resultado del OCR + lo que vio (líneas y barras), útil para depurar. */
    data class Analysis(val result: ScanResult, val lines: List<OcrLine>, val bars: AppraisalBars?, val width: Int, val height: Int)

    suspend fun scan(uri: Uri, parser: ScreenParser): ScanResult = analyze(uri, parser).result

    suspend fun analyze(uri: Uri, parser: ScreenParser): Analysis {
        val bmp = withContext(Dispatchers.IO) { loadBitmap(uri) } ?: error("No se pudo abrir la imagen")
        return try {
            analyze(bmp, parser)
        } finally {
            bmp.recycle()
        }
    }

    suspend fun scan(bitmap: Bitmap, parser: ScreenParser): ScanResult = analyze(bitmap, parser).result

    suspend fun analyze(bitmap: Bitmap, parser: ScreenParser): Analysis {
        val lines = recognize(bitmap)
        val bars = if (parser.looksLikeAppraisal(lines)) withContext(Dispatchers.Default) { measureBars(bitmap, lines, parser) } else null
        val result = withContext(Dispatchers.Default) { parser.parse(lines, bars) }
        return Analysis(result, lines, bars, bitmap.width, bitmap.height)
    }

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val b = line.boundingBox ?: return@mapNotNull null
            // Posición de cada carácter: con eso se sabe DÓNDE falta un dígito tapado (ver ScreenParser.parseFrames).
            val chars = line.elements.flatMap { it.symbols }.mapNotNull { s ->
                s.boundingBox?.let { sb -> OcrChar(s.text, sb.left / w, sb.right / w) }
            }
            OcrLine(line.text, b.left / w, b.top / h, b.right / w, b.bottom / h, chars)
        }
    }

    /** Decodifica a lo sumo ~2048 px de lado largo (una captura no necesita más; una foto de 50 MP sí reventaría). */
    private fun loadBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /**
     * Mide las tres barras de la valoración, debajo de sus rótulos ("Attack", "Defense", "HP").
     * Si el OCR perdió un rótulo, se deduce su posición por el espaciado de los otros dos.
     */
    private fun measureBars(bmp: Bitmap, lines: List<OcrLine>, parser: ScreenParser): AppraisalBars? {
        val found = listOf("ATTACK", "DEFENSE", "HP").map { name -> lines.firstOrNull { parser.isAppraisalLabel(it.text) == name } }
        val labels = inferMissing(found) ?: return null
        val fractions = labels.map { measureBelow(bmp, it) ?: return null }
        return AppraisalBars(fractions[0], fractions[1], fractions[2])
    }

    private fun inferMissing(l: List<OcrLine?>): List<OcrLine>? {
        val (a, d, h) = l
        fun shift(base: OcrLine, dy: Float) = base.copy(top = base.top + dy, bottom = base.bottom + dy)
        return when {
            a != null && d != null && h != null -> listOf(a, d, h)
            a != null && d != null -> listOf(a, d, shift(d, d.top - a.top))
            a != null && h != null -> listOf(a, shift(a, (h.top - a.top) / 2), h)
            d != null && h != null -> listOf(shift(d, -(h.top - d.top)), d, h)
            else -> null
        }
    }

    private fun measureBelow(bmp: Bitmap, label: OcrLine): Float? {
        val w = bmp.width
        val h = bmp.height
        val labelH = ((label.bottom - label.top) * h).toInt().coerceAtLeast(8)
        val yStart = (label.bottom * h).toInt().coerceIn(0, h - 1)
        val yEnd = min(h - 1, yStart + labelH * 2)
        val xStart = max(0, (label.left * w).toInt() - labelH)
        val xEnd = min(w, (w * 0.92f).toInt())
        if (xEnd - xStart < 10) return null
        // Color de fondo de la tarjeta: justo a la izquierda del rótulo, en su misma fila.
        val bg = bmp.getPixel(max(0, (label.left * w).toInt() - 4), ((label.top + label.bottom) / 2 * h).toInt().coerceIn(0, h - 1))
        val row = IntArray(xEnd - xStart)
        var best: BarMeasure.Sample? = null
        for (y in yStart..yEnd step 2) {
            bmp.getPixels(row, 0, row.size, xStart, y, row.size, 1)
            val s = BarMeasure.measure(row, bg)
            if (best == null || s.total > best.total) best = s
        }
        return best?.takeIf { it.total >= w * 0.15 }?.fraction
    }
}
