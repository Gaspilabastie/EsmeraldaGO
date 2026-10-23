package com.esmeraldago.core.critic

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.scan.AppraisalBars
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
import kotlin.math.abs

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
            // Las correcciones (fix/add) se escribieron sobre el texto del OCR de Windows.
            for (ideal in if (engine.name == "ocr-windows") listOf(false, true) else listOf(false)) {
                val label = engine.name.removePrefix("ocr-") + if (ideal) " (texto corregido)" else " (crudo)"
                report.appendLine("\n################ $label ################")
                var fieldsOk = 0; var fieldsTotal = 0; var slowest = 0L
                for (t in truth) {
                    val name = t.str("file")!!
                    val src = engine.listFiles()!!.firstOrNull { it.nameWithoutExtension.endsWith(name) } ?: continue
                    // Formato del OCR de Windows: [líneas]. Del teléfono (ML Kit): {lines, bars?}.
                    val root = Json.parseToJsonElement(src.readText())
                    var lines = (if (root is JsonArray) root else root.jsonObject["lines"]!!.jsonArray).map { it.toLine() }
                    val bars = (root as? JsonObject)?.get("bars")?.jsonObject?.let { b ->
                        AppraisalBars(b.f("atk"), b.f("def"), b.f("sta"))
                    }
                    if (ideal) {
                        val fix = t["fix"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
                        lines = lines.map { l -> fix[l.text]?.let { l.copy(text = it) } ?: l } +
                            t["add"]?.jsonArray.orEmpty().map { it.toLine() }
                    }
                    val t0 = System.nanoTime()
                    val r = parser.parse(lines, bars)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    slowest = maxOf(slowest, ms)
                    val expSpecies = t.str("species")
                    val expCp = t.int("cp")
                    val checks = listOf(
                        Triple("especie", expSpecies, r.species?.id),
                        // PC tapado: vale "no legible" o el valor real si se conoce por otra captura (cpActual).
                        Triple("PC", expCp?.toString() ?: "(no legible)", when {
                            r.cp == null -> "(no legible)"
                            r.cp == expCp -> r.cp.toString()
                            expCp == null && r.cp == t.int("cpActual") -> "(no legible)"
                            else -> "${r.cp}" + if (r.cpInferred) " (deducido)" else ""
                        }),
                        Triple("purificado", t.bool("purified").toString(), r.purified.toString()),
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
                    val expIvs = t["ivs"]?.jsonArray?.map { it.jsonPrimitive.int }?.let { Ivs(it[0], it[1], it[2]) }
                    val trueC = if (trueSpecies != null && expCp != null) {
                        val levels = t.int("dust")?.let { d -> gameLevels(d, t.bool("shadow"), t.bool("purified"), t.bool("lucky")) }?.ifEmpty { null }
                        // Purificar suma 2 a cada IV; los intercambios con suerte garantizan 12.
                        PokeMath.solveIvs(trueSpecies, expCp, idx, hp = t.int("hp"), levels = levels, appraisal = expIvs,
                            minIv = maxOf(if (t.bool("lucky")) 12 else 0, if (t.bool("purified")) 2 else 0))
                    } else null
                    val appraisalNote = when {
                        expIvs == null && r.appraisal == null -> null
                        r.appraisal == null -> "valoración: no se midieron las barras (reales $expIvs)" + (if (bars == null) "" else " ⚠")
                        expIvs == null -> "valoración: leyó ${r.appraisal} en una pantalla sin valoración ⚠"
                        r.appraisal == expIvs -> "valoración: ok ${r.appraisal}" + bars?.let { " (barras ${fmt(it)})" }.orEmpty()
                        else -> "valoración: leyó ${r.appraisal}, real $expIvs" + bars?.let { " (barras ${fmt(it)})" }.orEmpty() + " ⚠"
                    }
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
                    appraisalNote?.let { report.appendLine("  $it") }
                    if (r.problem != null) report.appendLine("  aviso al usuario: ${r.problem}")
                    if (r.speciesAmbiguous) report.appendLine("  opciones: ${r.speciesOptions.take(4).joinToString { it.id }}")
                    if (r.nickname != null) report.appendLine("  apodo leído: ${r.nickname}")
                }
                report.appendLine("\n== $label: $fieldsOk/$fieldsTotal campos correctos · captura más lenta ${slowest} ms (PC)")
            }
        }
        report.append(perturbations(dir, truth, parser))
        report.append(pairs(dir, parser))
        println(report)
        File(dir, "report.txt").writeText(report.toString())
    }

    /**
     * Prueba adversaria sobre el OCR real (ML Kit): a cada captura se le quita o estropea UNA pista por vez.
     * Lo grave es el error silencioso (especie o IV equivocados SIN aviso); "no sé + aviso" es degradar bien.
     */
    private fun perturbations(dir: File, truth: List<JsonObject>, parser: ScreenParser): String {
        val engine = File(dir, "ocr-mlkit").takeIf { it.isDirectory } ?: return ""
        val upper = { l: OcrLine -> l.text.trim().uppercase() }
        val isCandy = { l: OcrLine -> upper(l).contains("CANDY") }
        val types = com.esmeraldago.core.model.Types.ALL.map { it.uppercase() }
        val cases: List<Pair<String, (List<OcrLine>) -> List<OcrLine>>> = listOf(
            "sin cambios" to { ls -> ls },
            "sin nombre" to { ls -> val n = nameLineOf(ls); ls.filter { it !== n } },
            "apodo cualquiera" to { ls -> val n = nameLineOf(ls); ls.map { if (it === n) it.copy(text = "Bobby") else it } },
            "sin caramelos" to { ls -> ls.filterNot(isCandy) },
            "sin tipos" to { ls -> ls.filterNot { l -> upper(l).split('/', ' ').filter(String::isNotBlank).let { w -> w.isNotEmpty() && w.all { it in types } } } },
            "sin frase de captura" to { ls -> ls.filterNot { it.text.trim().startsWith("This ", true) } },
            "sin polvo" to { ls -> val pu = ls.firstOrNull { upper(it).replace(" ", "").contains("POWERUP") }
                ls.filterNot { pu != null && it !== pu && abs(it.centerY - pu.centerY) < 0.012f && it.left > pu.right } },
            "PC con O/l/S" to { ls -> ls.map { l -> if (Regex("""^CP\d+$""").matches(l.text.trim())) l.copy(text = l.text.replace('0', 'O').replace('1', 'l').replace('5', 'S')) else l } },
            "líneas de una fila unidas" to { ls -> joinRows(ls) },
            "apodo y sin caramelos" to { ls -> val n = nameLineOf(ls); ls.filterNot(isCandy).map { if (it === n) it.copy(text = "Bobby") else it } },
            "sin nombre/caramelos/frase" to { ls -> val n = nameLineOf(ls); ls.filterNot { it === n || isCandy(it) || it.text.trim().startsWith("This ", true) } },
            "basura inyectada" to { ls -> ls + listOf(
                OcrLine("Mx 7", 0.10f, 0.20f, 0.20f, 0.23f), OcrLine("CP", 0.80f, 0.30f, 0.84f, 0.32f),
                OcrLine("1,234", 0.70f, 0.47f, 0.80f, 0.49f), OcrLine("Pikachu", 0.05f, 0.95f, 0.20f, 0.97f)) },
            "cajas corridas ±0.4%" to { ls -> ls.mapIndexed { i, l -> val d = if (i % 2 == 0) 0.004f else -0.004f; l.copy(top = l.top + d, bottom = l.bottom + d, left = l.left - d, right = l.right - d) } },
            "orden mezclado" to { ls -> ls.shuffled(java.util.Random(7)) },
        )
        val sb = StringBuilder("\n################ prueba adversaria (ML Kit) ################\n")
        sb.appendLine(String.format("%-28s %8s %8s %8s %8s", "perturbación", "especie", "IV ok", "aviso", "SILENC."))
        for ((label, f) in cases) {
            var spOk = 0; var ivOk = 0; var warned = 0; var silent = 0; var n = 0; var worst = 0L
            val silentCases = mutableListOf<String>()
            val otherCases = mutableListOf<String>()
            for (t in truth) {
                val name = t.str("file")!!
                val src = engine.listFiles()!!.firstOrNull { it.nameWithoutExtension.endsWith(name) } ?: continue
                val root = Json.parseToJsonElement(src.readText()).jsonObject
                val lines = root["lines"]!!.jsonArray.map { it.toLine() }
                val bars = root["bars"]?.jsonObject?.let { b -> AppraisalBars(b.f("atk"), b.f("def"), b.f("sta")) }
                val t0 = System.nanoTime()
                val r = parser.parse(f(lines), bars)
                worst = maxOf(worst, (System.nanoTime() - t0) / 1_000_000)
                n++
                val trueSpecies = t.str("species")?.let { idx.species[it] }
                val expIvs = t["ivs"]?.jsonArray?.map { it.jsonPrimitive.int }?.let { Ivs(it[0], it[1], it[2]) }
                val expCp = t.int("cp") ?: t.int("cpActual")
                val trueC = if (trueSpecies != null && expCp != null) {
                    val lv = t.int("dust")?.let { gameLevels(it, t.bool("shadow"), t.bool("purified"), t.bool("lucky")) }?.ifEmpty { null }
                    PokeMath.solveIvs(trueSpecies, expCp, idx, t.int("hp"), lv, expIvs,
                        maxOf(if (t.bool("lucky")) 12 else 0, if (t.bool("purified")) 2 else 0)).toSet()
                } else null
                val speciesRight = r.species?.dex == trueSpecies?.dex && r.species?.types?.toSet() == trueSpecies?.types?.toSet()
                // IV correctos: los reales están entre los candidatos (o no se pueden saber y no se inventaron).
                val ivRight = if (trueC.isNullOrEmpty()) r.candidates.isEmpty() || r.cpInferred else r.candidates.containsAll(trueC)
                if (speciesRight) spOk++
                if (speciesRight && ivRight) ivOk++
                if (r.problem != null || r.speciesAmbiguous) warned++
                if ((!speciesRight || !ivRight) && r.problem == null && !r.speciesAmbiguous) { silent++; silentCases += "$name→${r.species?.id}/${r.ivRangeText}" }
                else if (!speciesRight || !ivRight) otherCases += "$name→${r.species?.id}/${r.ivRangeText}"
            }
            sb.appendLine(String.format("%-28s %5d/%d %5d/%d %5d/%d %5d %6d ms", label, spOk, n, ivOk, n, warned, n, silent, worst))
            if (silentCases.isNotEmpty()) sb.appendLine("    silenciosos: " + silentCases.joinToString("; "))
            if (otherCases.isNotEmpty()) sb.appendLine("    con aviso: " + otherCases.joinToString("; "))
        }
        return sb.toString()
    }

    /**
     * Detalle + valoración del MISMO Pokémon, como los combina ScanSession (mismos PS; PC igual o ilegible en
     * una; misma especie). Incluye dos Fennekin distintos con los mismos PS que NO deben combinarse.
     */
    private fun pairs(dir: File, parser: ScreenParser): String {
        val engine = File(dir, "ocr-mlkit").takeIf { it.isDirectory } ?: return ""
        fun load(name: String) = engine.listFiles()!!.first { it.nameWithoutExtension.endsWith(name) }.let { f ->
            val root = Json.parseToJsonElement(f.readText()).jsonObject
            parser.parse(root["lines"]!!.jsonArray.map { it.toLine() }, root["bars"]?.jsonObject?.let { b -> AppraisalBars(b.f("atk"), b.f("def"), b.f("sta")) })
        }
        data class Pair4(val detail: String, val appraisal: String, val expect: String)
        val cases = listOf(
            Pair4("3.25.46 AM (2)", "12.24.00 PM", "mewtwo 2447 15/15/15"),
            Pair4("3.25.46 AM", "12.24.01 PM", "machamp 2050 4/9/15 purificado"),
            Pair4("3.25.46 AM (1)", "12.24.00 PM (1)", "dragonite 2345 10/11/10"),
            Pair4("3.25.51 AM", "12.23.58 PM", "cinderace 2600 15/13/3 gigamax"),
            Pair4("3.25.44 AM (2)", "12.24.02 PM", "(no se combinan: otro Fennekin)"),
        )
        val sb = StringBuilder("\n################ detalle + valoración (ML Kit) ################\n")
        for (c in cases) {
            val a = load(c.detail); val b = load(c.appraisal)
            val match = a.hp == b.hp && (a.cp == b.cp || a.cp == null || b.cp == null) && a.species?.dex == b.species?.dex
            val got = if (!match) "(no se combinan: otro Fennekin)".takeIf { c.expect.startsWith("(no") } ?: "no se combinaron"
            else parser.combine(a, b).let { m ->
                "${m.species?.id} ${m.cp} ${if (m.ivExact) m.candidates.first().ivs else m.ivRangeText}" +
                    (if (m.purified) " purificado" else "") + (if (m.gigantamax) " gigamax" else "") + (m.problem?.let { " [$it]" } ?: "")
            }
            // Una nota ("revisalo") no es error si el resultado es el correcto.
            val ok = got.substringBefore(" [") == c.expect
            sb.appendLine("${if (ok) "ok " else "✗  "} ${c.detail} + ${c.appraisal}: $got" + if (!ok) "   (esperado: ${c.expect})" else "")
        }
        return sb.toString()
    }

    /** La línea del nombre grande (más alta, centrada, justo encima de los PS). */
    private fun nameLineOf(ls: List<OcrLine>): OcrLine? {
        val hp = ls.firstOrNull { Regex("""\d+\s*/\s*\d+\s*HP""").containsMatchIn(it.text) } ?: return null
        return ls.filter { it.bottom <= hp.top + 0.005f && it.top >= hp.top - 0.1f && it.centerX in 0.25f..0.75f }.maxByOrNull { it.height }
    }

    /** Como hace ML Kit a veces ("MEWTWO MEGA ENERGY MEWTWO MEGA ENERGY Y"): une los textos de una misma fila. */
    private fun joinRows(ls: List<OcrLine>): List<OcrLine> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        ls.sortedBy { it.centerY }.forEach { l ->
            val row = rows.lastOrNull()?.takeIf { r -> abs(r.first().centerY - l.centerY) < 0.006f }
            if (row != null) row += l else rows += mutableListOf(l)
        }
        return rows.map { r -> r.sortedBy { it.left }.let { s -> OcrLine(s.joinToString(" ") { it.text }, s.minOf { it.left }, s.minOf { it.top }, s.maxOf { it.right }, s.maxOf { it.bottom }) } }
    }

    /**
     * Niveles según el costo que muestra el juego: lo calcula en float y redondea hacia arriba, por eso
     * 800×1.2 se ve 961 y 3000×1.2 se ve 3,601, pero 2200×1.2 se ve 2,640 (verificado en capturas reales).
     */
    private fun gameLevels(dust: Int, shadow: Boolean, purified: Boolean, lucky: Boolean): List<Double> =
        generateSequence(1.0) { it + 0.5 }.takeWhile { it < 50.0 }.filter { l ->
            var c = PokeMath.dustFor(l).toFloat()
            if (shadow) c *= 1.2f else if (purified) c *= 0.9f
            if (lucky) c *= 0.5f
            kotlin.math.ceil(c.toDouble()).toInt() == dust
        }.toList()

    private fun fmt(b: AppraisalBars) = "%.2f/%.2f/%.2f".format(b.atk, b.def, b.sta)

    private fun pct(c: List<PokeMath.IvCandidate>): String {
        val p = c.map { it.ivs.percent }
        return if (p.min() == p.max()) "${p.min()}%" else "${p.min()}–${p.max()}%"
    }

    private fun JsonElement.toLine(): OcrLine = jsonObject.let {
        OcrLine(it["text"]!!.jsonPrimitive.content, it.f("left"), it.f("top"), it.f("right"), it.f("bottom"),
            it["chars"]?.jsonArray?.map { c -> c.jsonObject.let { o -> com.esmeraldago.core.scan.OcrChar(o["t"]!!.jsonPrimitive.content, o.f("l"), o.f("r")) } }.orEmpty())
    }
    private fun JsonObject.f(k: String) = this[k]!!.jsonPrimitive.float
    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.int(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.boolean ?: false
    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
}
