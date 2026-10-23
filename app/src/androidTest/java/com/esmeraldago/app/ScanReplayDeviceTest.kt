package com.esmeraldago.app

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.esmeraldago.app.scan.OcrScanner
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.scan.ScreenParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Banco de prueba del escaneo con el OCR real (ML Kit), en el teléfono. No toca la colección.
 * Lee las capturas de Android/data/com.esmeraldago.app/files/replay/in y escribe en .../replay/out,
 * por captura, las líneas de OCR (texto + cajas normalizadas) y las barras medidas, en el formato
 * de core ScanReplayTest. Se corre con `am instrument` (no con connectedAndroidTest, que desinstala la app).
 */
@RunWith(AndroidJUnit4::class)
class ScanReplayDeviceTest {
    @Test
    fun dumpOcr() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val idx = GameIndex(GameDataSources.decode(ctx.assets.open("gamedata.json").bufferedReader().use { it.readText() }))
        val parser = ScreenParser(idx)
        val ocr = OcrScanner(ctx)
        // La app no puede leer lo que adb copia en su carpeta (Android 11+): se lee y escribe vía el shell de la instrumentación.
        fun shell(cmd: String): ByteArray =
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
        shell("mkdir -p $OUT")
        val listing = String(shell("ls $IN"))
        val names = listing.lines().map { it.trim() }
            .filter { it.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png") }.sorted()
        check(names.isNotEmpty()) { "No hay capturas en $IN: $listing" }
        names.forEach { name ->
            val f = File(name)
            val bytes = shell("cat $IN/$name")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@forEach
            val a = ocr.analyze(bmp, parser)
            val json = buildJsonObject {
                put("width", a.width); put("height", a.height)
                put("lines", buildJsonArray {
                    a.lines.forEach { l ->
                        add(buildJsonObject {
                            put("text", l.text); put("left", l.left); put("top", l.top); put("right", l.right); put("bottom", l.bottom)
                        })
                    }
                })
                a.bars?.let { b -> put("bars", buildJsonObject { put("atk", b.atk); put("def", b.def); put("sta", b.sta) }) }
                put("onDevice", buildJsonObject {
                    put("species", JsonPrimitive(a.result.species?.id)); put("cp", JsonPrimitive(a.result.cp))
                    put("problem", JsonPrimitive(a.result.problem)); put("iv", a.result.ivRangeText)
                })
            }
            // executeShellCommand no interpreta comillas: nombres sin espacios y `tee` para escribir el archivo.
            val (stdout, stdin) = inst.uiAutomation.executeShellCommandRw("tee $OUT/${f.nameWithoutExtension}.json")
            ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(json.toString().toByteArray()) }
            ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
            bmp.recycle()
        }
    }

    private companion object {
        const val IN = "/data/local/tmp/esm-in"
        const val OUT = "/data/local/tmp/esm-replay"
    }
}
