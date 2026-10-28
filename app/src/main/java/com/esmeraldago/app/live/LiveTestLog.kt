package com.esmeraldago.app.live

import android.content.Context
import com.esmeraldago.core.scan.AppraisalBars
import com.esmeraldago.core.scan.OcrLine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Registro TEMPORAL de las pruebas del escaneo en vivo, mientras se ajusta: el log de cada paso, las imágenes que
 * leyó el OCR y, por cada una, el texto reconocido con su posición (el formato de ScanReplayTest, para reproducir
 * el escaneo en la compu). Vive en la carpeta privada de la app y se exporta como .zip desde Opciones.
 */
object LiveTestLog {
    /** Imágenes que se conservan (~100 KB cada una, más su texto). */
    const val MAX_FRAMES = 300

    fun dir(context: Context) = File(context.getExternalFilesDir(null), "live").apply { mkdirs() }

    /** Texto del OCR (y barras de la valoración, si hay) junto a la imagen [frameName]. */
    fun saveOcr(context: Context, frameName: String, lines: List<OcrLine>, bars: AppraisalBars?) = runCatching {
        val json = JsonObject(buildMap {
            put("lines", JsonArray(lines.map { l ->
                JsonObject(mapOf(
                    "text" to JsonPrimitive(l.text), "left" to JsonPrimitive(l.left), "top" to JsonPrimitive(l.top),
                    "right" to JsonPrimitive(l.right), "bottom" to JsonPrimitive(l.bottom),
                    "chars" to JsonArray(l.chars.map { c ->
                        JsonObject(mapOf("t" to JsonPrimitive(c.text), "l" to JsonPrimitive(c.left), "r" to JsonPrimitive(c.right)))
                    }),
                ))
            }))
            bars?.let { put("bars", JsonObject(mapOf("atk" to JsonPrimitive(it.atk), "def" to JsonPrimitive(it.def), "sta" to JsonPrimitive(it.sta)))) }
        })
        File(File(dir(context), "frames"), frameName.removeSuffix(".jpg") + ".json").writeText(json.toString())
    }

    /** Todo el registro en un .zip. Devuelve cuántas imágenes incluyó. */
    fun exportZip(context: Context, out: OutputStream): Int {
        val root = dir(context)
        var images = 0
        ZipOutputStream(out.buffered()).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                if (f.extension == "jpg") images++
            }
        }
        return images
    }

    /** Borra el registro (después de exportarlo, para que la próxima prueba empiece limpia). */
    fun clear(context: Context) { dir(context).deleteRecursively() }
}
