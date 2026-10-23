package com.esmeraldago.core.critic

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.scan.OcrLine
import com.esmeraldago.core.scan.ScreenParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Reproduce escaneos reales: líneas de OCR guardadas (JSON con texto + cajas normalizadas) -> ScreenParser,
 * comparadas con lo que de verdad muestra cada captura (truth.json). Es diagnóstico, no un test que deba pasar:
 * imprime y guarda un informe. Solo corre si SCAN_REPLAY_DIR apunta a una carpeta con:
 *   truth.json   [{file, species, cp, hp, dust, shadow, lucky, purified, dynamax, gigantamax, nickname, fast, charged, fix, add}]
 *   ocr-<motor>/<archivo>.json   [{text, left, top, right, bottom}]
 * Para cada motor se corre también la variante "ideal": el mismo OCR con los textos corregidos (fix/add),
 * que separa los errores del parser de los del OCR.
 */
class ScanReplayTest {
    private val idx by lazy {
        val f = listOf("../app/src/main/assets/gamedata.json", "app/src/main/assets/gamedata.json").map(::File).first { it.exists() }
        GameIndex(GameDataSources.decode(f.readText()))
    }

    @Test
    fun replay() {
        val dir = System.getenv("SCAN_REPLAY_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "truth.json").exists(), "SCAN_REPLAY_DIR no configurado")
        val truth = Json.parseToJsonElement(File(dir, "truth.json").readText()).jsonArray.map { it.jsonObject }
        val engines = dir!!.listFiles { f -> f.isDirectory && f.name.startsWith("ocr-") }!!.sortedBy { it.name }
        val report = StringBuilder()
        val parser = ScreenParser(idx)

        for (engine in engines) {
            for (ideal in listOf(false, true)) {
                val label = engine.name.removePrefix("ocr-") + if (ideal) " (texto corregido)" else " (crudo)"
                report.appendLine("\n################ $label ################")
                var fieldsOk = 0; var fieldsTotal = 0
                for (t in truth) {
                    val name = t.str("file")!!
                    val src = engine.listFiles()!!.firstOrNull { it.nameWithoutExtension.endsWith(name) } ?: continue
                    var lines = Json.parseToJsonElement(src.readText()).jsonArray.map { it.toLine() }
                    if (ideal) {
                        val fix = t["fix"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
                        lines = lines.map { l -> fix[l.text]?.let { l.copy(text = it) } ?: l } +
                            t["add"]?.jsonArray.orEmpty().map { it.toLine() }
                    }
                    val r = parser.parse(lines)
                    val expSpecies = t.str("species")
                    val expCp = t.int("cp")
                    val checks = listOf(
                        Triple("especie", expSpecies, r.species?.id),
                        Triple("PC", expCp?.toString() ?: "(no legible)", r.cp?.toString() ?: "(no legible)"),
                        Triple("PS", t.int("hp")?.toString(), r.hp?.toString()),
                        Triple("polvo", t.int("dust")?.toString(), r.dust?.toString()),
                        Triple("oscuro", t.bool("shadow").toString(), r.shadow.toString()),
                        Triple("suerte", t.bool("lucky").toString(), r.lucky.toString()),
                        Triple("dinamax", t.bool("dynamax").toString(), r.dynamax.toString()),
                        Triple("gigamax", t.bool("gigantamax").toString(), r.gigantamax.toString()),
                        Triple("apodo", if (t.bool("nickname")) "sí" else "no", if (r.nickname != null) "sí" else "no"),
                        Triple("rápido", t.str("fast") ?: "-", r.fast ?: "-"),
                        Triple("cargados", t["charged"]?.jsonArray?.joinToString(",") { it.jsonPrimitive.content } ?: "-",
                            r.charged.joinToString(",").ifEmpty { "-" }),
                    )
                    val bad = checks.filter { (_, e, g) -> e != g }
                    fieldsTotal += checks.size; fieldsOk += checks.size - bad.size

                    // IV: lo que deberían dar los valores reales vs. lo que dio el escaneo.
                    val trueSpecies = expSpecies?.let { idx.species[it] }
                    val trueC = if (trueSpecies != null && expCp != null) {
                        val purified = t.bool("purified")
                        val levels = t.int("dust")?.let { d ->
                            PokeMath.levelsForDustCost(d, t.bool("shadow"), purified, t.bool("lucky"), idx.maxLevel)
                        }?.ifEmpty { null }
                        PokeMath.solveIvs(trueSpecies, expCp, idx, hp = t.int("hp"), levels = levels, minIv = if (t.bool("lucky")) 12 else 0)
                    } else null
                    val ivNote = when {
                        trueC == null -> "IV: no calculables (PC no legible)" + if (r.candidates.isNotEmpty()) " pero el escaneo dio ${r.candidates.size} candidatos ${r.ivRangeText} ⚠" else ""
                        trueC.isEmpty() -> "IV: ¡los valores reales no tienen solución! (revisar truth o tablas)"
                        r.candidates.isEmpty() -> "IV: escaneo sin candidatos; reales ${trueC.size} (${pct(trueC)})"
                        r.candidates.toSet() == trueC.toSet() -> "IV: ok, ${trueC.size} candidatos (${pct(trueC)})"
                        else -> "IV: escaneo ${r.candidates.size} (${r.ivRangeText}) vs reales ${trueC.size} (${pct(trueC)})" +
                            if (!r.candidates.containsAll(trueC)) " ⚠ faltan reales" else ""
                    }
                    report.appendLine("\n— $name · ${t.str("what")}")
                    report.appendLine(if (bad.isEmpty()) "  todo ok" else bad.joinToString("\n") { (k, e, g) -> "  ✗ $k: esperado $e, leyó $g" })
                    report.appendLine("  $ivNote")
                    if (r.problem != null) report.appendLine("  aviso al usuario: ${r.problem}")
                    if (r.speciesAmbiguous) report.appendLine("  opciones: ${r.speciesOptions.take(4).joinToString { it.id }}")
                    if (r.nickname != null) report.appendLine("  apodo leído: ${r.nickname}")
                }
                report.appendLine("\n== $label: $fieldsOk/$fieldsTotal campos correctos")
            }
        }
        println(report)
        File(dir, "report.txt").writeText(report.toString())
    }

    private fun pct(c: List<PokeMath.IvCandidate>): String {
        val p = c.map { it.ivs.percent }
        return if (p.min() == p.max()) "${p.min()}%" else "${p.min()}–${p.max()}%"
    }

    private fun JsonElement.toLine(): OcrLine = jsonObject.let {
        OcrLine(it["text"]!!.jsonPrimitive.content, it.f("left"), it.f("top"), it.f("right"), it.f("bottom"))
    }
    private fun JsonObject.f(k: String) = this[k]!!.jsonPrimitive.float
    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.int(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.boolean ?: false
    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
}
