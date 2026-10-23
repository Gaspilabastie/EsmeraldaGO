package com.esmeraldago.core.scan

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.model.Types
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Una línea de texto reconocida, con coordenadas normalizadas (0..1) respecto de la imagen. */
data class OcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerY get() = (top + bottom) / 2
    val centerX get() = (left + right) / 2
    val height get() = bottom - top
}

enum class ScreenType { DETAIL, APPRAISAL, UNKNOWN }

/** Fracción llena (0..1) de las tres barras de la valoración, medida sobre los píxeles. */
data class AppraisalBars(val atk: Float, val def: Float, val sta: Float) {
    fun toIvs() = Ivs(toIv(atk), toIv(def), toIv(sta))
    private fun toIv(f: Float) = (f * 15).roundToInt().coerceIn(0, 15)
}

/**
 * Lo que se leyó de una captura, sin interpretar todavía. Se guarda en el resultado para poder
 * re-resolver (otra especie elegida a mano, o combinar detalle + valoración del mismo Pokémon).
 */
data class ScreenReading(
    val appraisalScreen: Boolean = false,
    val cp: Int? = null,
    /** Dígitos del PC tal como se leyeron (para intentar repararlo si no cuadra). */
    val cpDigits: String? = null,
    /** Se vio el rótulo "CP" junto al número (si no, el número salió solo de su posición). */
    val cpLabeled: Boolean = false,
    val hp: Int? = null,
    val dust: Int? = null,
    val shadow: Boolean = false,
    val lucky: Boolean = false,
    val dynamax: Boolean = false,
    val gigantamax: Boolean = false,
    /** Tiene Return: solo lo aprenden los purificados. */
    val hasReturn: Boolean = false,
    val types: Set<String> = emptySet(),
    val nameText: String? = null,
    val candyNames: List<String> = emptyList(),
    /** "This Sylveon was caught on…" (valoración): la especie, aunque el Pokémon tenga apodo. */
    val captionName: String? = null,
    val moveTexts: List<String> = emptyList(),
) {
    /** Combina dos capturas del mismo Pokémon: gana lo que esté en cualquiera de las dos. */
    fun mergedWith(o: ScreenReading) = ScreenReading(
        appraisalScreen = appraisalScreen || o.appraisalScreen,
        cp = cp ?: o.cp, cpDigits = cpDigits ?: o.cpDigits, cpLabeled = cpLabeled || o.cpLabeled,
        hp = hp ?: o.hp, dust = dust ?: o.dust,
        shadow = shadow || o.shadow, lucky = lucky || o.lucky,
        dynamax = dynamax || o.dynamax, gigantamax = gigantamax || o.gigantamax,
        hasReturn = hasReturn || o.hasReturn,
        types = types.ifEmpty { o.types }, nameText = nameText ?: o.nameText,
        candyNames = (candyNames + o.candyNames).distinct(), captionName = captionName ?: o.captionName,
        // La parte de arriba suele mostrar solo el primer ataque (o cortado): se suman los de las dos.
        moveTexts = (moveTexts + o.moveTexts).distinct(),
    )
}

data class ScanResult(
    val screen: ScreenType,
    val species: Species? = null,
    val speciesOptions: List<Species> = emptyList(),
    val cp: Int? = null,
    val hp: Int? = null,
    val dust: Int? = null,
    val fast: String? = null,
    val charged: List<String> = emptyList(),
    val shadow: Boolean = false,
    val purified: Boolean = false,
    val lucky: Boolean = false,
    val dynamax: Boolean = false,
    val gigantamax: Boolean = false,
    val nickname: String? = null,
    val appraisal: Ivs? = null,
    val candidates: List<PokeMath.IvCandidate> = emptyList(),
    /** Hay más de una especie/forma compatible y la pantalla no alcanza para decidir. */
    val speciesAmbiguous: Boolean = false,
    /** La valoración no es compatible con el PC/PS leídos (se conservan los candidatos del detalle). */
    val appraisalConflict: Boolean = false,
    /** El PC no estaba legible: se dedujo de otros datos (valoración + PS) o se corrigió. */
    val cpInferred: Boolean = false,
    /** Por qué no se pudo leer (para mostrárselo al usuario). */
    val problem: String? = null,
    val reading: ScreenReading? = null,
) {
    val ivExact get() = candidates.size == 1 && !speciesAmbiguous && !appraisalConflict

    /** Rango de IV% posible (como lo muestra Poke Genie cuando no hay valoración), ej. "76–91%". */
    val ivRangeText: String
        get() {
            if (candidates.isEmpty()) return "?"
            val pcts = candidates.map { it.ivs.percent }
            val lo = pcts.min(); val hi = pcts.max()
            return if (lo == hi) "$lo%" else "$lo–$hi%"
        }
}

/**
 * Interpreta capturas de la pantalla de un Pokémon (cliente en inglés) y de la valoración.
 * No depende de Android: recibe líneas de OCR y, opcionalmente, las barras de valoración medidas.
 *
 * Trabaja en dos pasos: [read] junta todas las pistas de la pantalla (PC, PS, polvo, tipos, nombre,
 * caramelos, frase de captura, ataques…) y [resolve] busca la especie y los IV que mejor explican TODAS
 * a la vez. Ninguna pista es imprescindible: el PC tapado se deduce o se descarta, el nombre puede ser
 * un apodo, los caramelos pueden no verse.
 */
class ScreenParser(private val idx: GameIndex) {
    /**
     * Nivel máximo posible del Pokémon. Con el nivel de entrenador se acota a entrenador + 10 (máx. 50;
     * 51 con Mejor Compañero). El nivel lo fija sobre todo el costo de polvo; esto solo ayuda si no se leyó.
     */
    var levelCap: Double = idx.maxLevel

    // Caracteres que el OCR confunde con dígitos.
    private val digitLike = "0-9OoIl|SsBZ"
    private val cpRegex = Regex("""\b[cC][pP]\s*[:.]?\s*([$digitLike][$digitLike ,.]{0,6})""")
    private val cpOnlyRegex = Regex("""^\s*[cC][pP]\s*[:.]?\s*$""")
    private val hpRegex = Regex("""([$digitLike]{1,4})\s*[/|Il]\s*([$digitLike]{1,4})\s*[hH][pP]""")
    private val powerUpRegex = Regex("""POWER\s*UP""", RegexOption.IGNORE_CASE)
    private val candyRegex = Regex("""^(?:\d[\d,.]*\s+)?(.+?)\s+CANDY$""", RegexOption.IGNORE_CASE)
    private val firstNumberRegex = Regex("""(\d{1,3}(?:[,.]\d{3})+|\d+)""")
    private val luckyRegex = Regex("""LUCKY\s+POK""", RegexOption.IGNORE_CASE)
    private val captionRegex = Regex("""^This\s+(.+?)\s+was\b""", RegexOption.IGNORE_CASE)
    private val statLabels = listOf("WEIGHT", "HEIGHT", "HEAVIEST", "LIGHTEST", "TALLEST", "SHORTEST")
    private val movesTabsRegex = Regex("""GYMS\s*&?\s*RAIDS|TRAINER\s+BATTLES""", RegexOption.IGNORE_CASE)
    private val spanishHints = listOf("MEJORAR", "PURIFICAR", "CARAMELO", "POLVOS ESTELARES", "EVOLUCIONAR")

    private val typeNames = Types.ALL.toSet()

    // ================================================================ valoración

    /** ¿Tiene esta captura los rótulos de la valoración? (para decidir si medir las barras) */
    fun looksLikeAppraisal(lines: List<OcrLine>): Boolean = appraisalLabels(lines) != null

    /**
     * "ATTACK" / "DEFENSE" / "HP" exactos (con a lo sumo un error en las palabras largas), o null.
     * Las etiquetas del usuario ("Attack.") terminan en punto y no cuentan.
     */
    fun isAppraisalLabel(text: String): String? {
        val t = text.trim()
        if (t.endsWith(".")) return null
        val u = t.uppercase()
        if (u == "HP") return "HP"
        return listOf("ATTACK", "DEFENSE").firstOrNull { levenshtein(it, u) <= 1 }
    }

    /**
     * Los rótulos de la valoración, en orden ATK/DEF/PS: tienen que estar apilados (uno debajo del otro,
     * alineados a la izquierda). Así una etiqueta del usuario llamada "Attack" no la dispara.
     */
    fun appraisalLabels(lines: List<OcrLine>): List<OcrLine?>? {
        val found = listOf("ATTACK", "DEFENSE", "HP").map { n -> lines.filter { isAppraisalLabel(it.text) == n } }
        val combos = found.map { it.ifEmpty { listOf(null) } }
        for (a in combos[0]) for (d in combos[1]) for (h in combos[2]) {
            val present = listOfNotNull(a, d, h)
            if (present.size < 2) continue
            val stacked = present.zipWithNext().all { (x, y) -> y.top > x.bottom && y.top - x.top < 0.12f }
            val aligned = present.maxOf { it.left } - present.minOf { it.left } < 0.08f
            if (stacked && aligned) return listOf(a, d, h)
        }
        return null
    }

    // ================================================================ entrada

    fun parse(lines: List<OcrLine>, bars: AppraisalBars? = null): ScanResult {
        val reading = read(lines)
        // La ficha y la valoración SIEMPRE muestran "x / y HP". Sin eso no es la pantalla de un Pokémon
        // (la lista del almacenamiento, un menú, otra app): adivinar ahí daba "Puede ser A, B, C…" con
        // 200 combinaciones. Se devuelve lo leído igual: la parte de abajo de la ficha (los ataques) se
        // combina con la de arriba.
        if (reading.hp == null) {
            val spanish = lines.any { l -> spanishHints.any { it in l.text.uppercase() } }
            return ScanResult(
                ScreenType.UNKNOWN,
                reading = reading,
                problem = when {
                    spanish -> "Parece el juego en español: la app lee el cliente en inglés."
                    reading.cp != null -> "Esta pantalla no es la ficha de un Pokémon (no se ven los PS). Abrí uno y volvé a escanear."
                    else -> "No es la ficha de un Pokémon: abrí uno en el juego y volvé a escanear."
                },
            )
        }
        val appraisal = if (reading.appraisalScreen) bars?.toIvs() else null
        val r = resolve(reading, appraisal)
        return if (reading.appraisalScreen && appraisal == null) {
            r.copy(problem = r.problem ?: "No se pudieron medir las barras de la valoración.")
        } else r
    }

    /** Combina una pantalla de detalle con la de valoración (IV exactos). */
    fun withAppraisal(detail: ScanResult, appraisal: Ivs): ScanResult {
        val reading = detail.reading ?: ScreenReading(cp = detail.cp, cpLabeled = detail.cp != null, hp = detail.hp,
            dust = detail.dust, shadow = detail.shadow, lucky = detail.lucky)
        val forced = detail.species?.takeIf { !detail.speciesAmbiguous && detail.reading == null }
        return resolve(reading, appraisal, forced).let { r ->
            // Si la valoración no encaja, se conserva lo que había y se avisa.
            if (r.appraisalConflict) r.copy(candidates = detail.candidates) else r
        }
    }

    /** Detalle + valoración del mismo Pokémon, en cualquier orden: junta las pistas de ambas. */
    fun combine(a: ScanResult, b: ScanResult): ScanResult {
        val appraisal = a.appraisal ?: b.appraisal
        val ra = a.reading; val rb = b.reading
        // La parte de abajo de la ficha (solo ataques) no es una pantalla por sí sola: aporta sus pistas a la otra.
        if (ra != null && rb != null && (a.screen == ScreenType.UNKNOWN || b.screen == ScreenType.UNKNOWN)) {
            return resolve(ra.mergedWith(rb), appraisal)
        }
        if (ra == null || rb == null) {
            val detail = if (a.screen == ScreenType.APPRAISAL) b else a
            return appraisal?.let { withAppraisal(detail, it) } ?: detail
        }
        return resolve(ra.mergedWith(rb), appraisal)
    }

    /** El usuario eligió la especie a mano: recalcular con lo leído. */
    fun resolveFor(s: Species, r: ScanResult): ScanResult {
        val reading = r.reading ?: ScreenReading(cp = r.cp, cpLabeled = r.cp != null, hp = r.hp, dust = r.dust,
            shadow = r.shadow, lucky = r.lucky)
        return resolve(reading, r.appraisal, forced = s)
    }

    // ================================================================ lectura

    fun read(lines: List<OcrLine>): ScreenReading {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.top }
        val upper = sorted.map { it.text.trim().uppercase() }

        val appraisalScreen = appraisalLabels(sorted) != null
        val (cp, cpDigits, cpLabeled) = readCp(sorted)
        val hpLine = sorted.firstOrNull { hpRegex.containsMatchIn(it.text) }
        val hp = hpLine?.let { hpRegex.find(it.text)?.groupValues?.get(2)?.let(::fixDigits)?.toIntOrNull() }
        val powerUp = sorted.firstOrNull { powerUpRegex.containsMatchIn(it.text) }

        // Fila de peso / tipos / altura: ancla para ubicar los tipos y lo que hay debajo (fila Dinamax).
        val statRow = sorted.filter { l -> tokens(l.text).any(::isStatLabel) }
        val (types, typeLines) = findTypes(sorted, statRow, powerUp, hpLine)
        val rowBottom = (statRow + typeLines).maxOfOrNull { it.bottom } ?: hpLine?.let { it.bottom + 0.12f }

        val shadow = upper.any { Regex("""\bPURIFY\b""").containsMatchIn(it) || it.contains("SHADOW BONUS") }
        val lucky = sorted.any { luckyRegex.containsMatchIn(it.text) }
        val maxRow = rowBottom?.let { rb -> sorted.filter { it.top >= rb - 0.005f && (powerUp == null || it.bottom <= powerUp.top) } }.orEmpty()
        // Las etiquetas del usuario ("Dynamax.") están entre los PS y la fila de peso; muy por debajo de la
        // última etiqueta, un punto final es ruido del OCR y no una etiqueta.
        val lastTag = hpLine?.let { hp -> sorted.filter { it.text.trim().endsWith(".") && it.top in hp.bottom..(hp.bottom + 0.15f) }.maxOfOrNull { it.bottom } }
        fun rowLabel(l: OcrLine, label: String) = isRowLabel(l.text, label) ||
            (lastTag != null && l.top > lastTag + 0.08f && isRowLabel(l.text.trim().removeSuffix("."), label))
        val gigantamax = maxRow.any { rowLabel(it, "GIGANTAMAX") } || upper.any { it.contains("G-MAX") }
        val dynamax = gigantamax || maxRow.any { rowLabel(it, "DYNAMAX") } ||
            upper.any { it.contains("MAX MOVES") || it.contains("MAX ATTACK") || it.contains("MAX GUARD") }

        // Ataques: debajo de POWER UP o de las pestañas GYMS & RAIDS / TRAINER BATTLES. La valoración no los muestra.
        val movesFrom = if (appraisalScreen) null
        else (listOfNotNull(powerUp) + sorted.filter { movesTabsRegex.containsMatchIn(it.text) }).maxOfOrNull { it.bottom }
        val moveTexts = movesFrom?.let { from -> sorted.filter { it.top >= from - 0.005f }.map { it.text.trim() } }.orEmpty()
        val hasReturn = moveTexts.any { key(stripMoveNoise(it)).let { k -> k.length >= 5 && levenshtein(ocrNorm(k), "retum") <= 1 } }

        return ScreenReading(
            appraisalScreen = appraisalScreen,
            cp = cp, cpDigits = cpDigits, cpLabeled = cpLabeled,
            hp = hp,
            dust = findDust(sorted, powerUp),
            shadow = shadow, lucky = lucky, dynamax = dynamax, gigantamax = gigantamax,
            hasReturn = hasReturn,
            types = types,
            nameText = findNameLine(sorted, hpLine)?.text?.trim(),
            candyNames = findCandyNames(sorted),
            captionName = sorted.firstNotNullOfOrNull { captionRegex.find(it.text.trim())?.groupValues?.get(1) },
            moveTexts = moveTexts,
        )
    }

    private data class CpRead(val cp: Int?, val digits: String?, val labeled: Boolean)

    private fun readCp(lines: List<OcrLine>): CpRead {
        // 1) "CP2455" en una línea (la de más arriba: la etiqueta del usuario "High CP." no trae número).
        lines.firstNotNullOfOrNull { l -> cpRegex.find(l.text)?.groupValues?.get(1)?.let(::fixDigits)?.takeIf { it.all(Char::isDigit) } }
            ?.let { d -> d.toIntOrNull()?.takeIf { it in 10..9999 }?.let { return CpRead(it, d, true) } }
        // 2) "CP" y el número en líneas separadas de la misma fila.
        lines.firstOrNull { cpOnlyRegex.matches(it.text) }?.let { label ->
            lines.filter { it !== label && abs(it.centerY - label.centerY) < max(label.height, 0.01f) && it.left >= label.left }
                .minByOrNull { it.left }?.text?.let(::fixDigits)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.let { d -> d.toIntOrNull()?.takeIf { it in 10..9999 }?.let { return CpRead(it, d, true) } }
        }
        // 3) Sin rótulo legible (tapado o mal leído: "Q2455"): el número grande centrado arriba de todo.
        val top = lines.filter { it.top in 0.035f..0.16f && it.centerX in 0.3f..0.7f }.maxByOrNull { it.height }
        val digits = top?.text?.trim()?.replace(" ", "")?.let { t ->
            // "Q2455": rótulo leído como letra; "02050": rótulo leído como cero.
            val m = Regex("""^[^0-9]{0,2}([$digitLike]{2,4})$""").find(t) ?: Regex("""^0(\d{2,4})$""").find(t) ?: return@let null
            fixDigits(m.groupValues[1]).takeIf { it.all(Char::isDigit) }
        }
        return digits?.toIntOrNull()?.takeIf { it in 10..9999 }?.let { CpRead(it, digits, false) } ?: CpRead(null, null, false)
    }

    /** Polvo del botón POWER UP: solo números de esa misma fila, a la derecha del texto. */
    private fun findDust(lines: List<OcrLine>, powerUp: OcrLine?): Int? {
        powerUp ?: return null
        val valid = PokeMath.ALL_SHOWN_DUST
        // Mismo renglón: "POWER UP 5,000 4"
        powerUpRegex.split(powerUp.text).getOrNull(1)?.let { rest ->
            firstNumberRegex.find(rest)?.value?.let(::parseNumber)?.takeIf { it in valid }?.let { return it }
        }
        val tolerance = max(powerUp.height * 0.6f, 0.012f)
        return lines.filter { it !== powerUp && abs(it.centerY - powerUp.centerY) < tolerance && it.left >= powerUp.left }
            .sortedBy { it.left }
            .firstNotNullOfOrNull { l ->
                // Una línea que es solo "número" puede traer letras confundidas ("5,OOO").
                val text = if (Regex("""^[$digitLike,.\s]+$""").matches(l.text)) fixDigits(l.text) else l.text
                firstNumberRegex.find(text)?.value?.let(::parseNumber)?.takeIf { it in valid }
            }
    }

    /**
     * Tipos: la línea de tipos está en la misma fila que "WEIGHT"/"HEIGHT" (o "HEAVIEST", "LIGHTEST"…).
     * En esa fila se aceptan tipos cortados por la valoración ("STEEL / RO"). Sin esa fila, solo líneas
     * formadas por nombres de tipo completos y por encima de POWER UP (un ataque puede llamarse "Psychic").
     */
    private fun findTypes(lines: List<OcrLine>, statRow: List<OcrLine>, powerUp: OcrLine?, hpLine: OcrLine?): Pair<Set<String>, List<OcrLine>> {
        if (statRow.isNotEmpty()) {
            val y = statRow.map { it.centerY }.average().toFloat()
            val inRow = lines.filter { abs(it.centerY - y) < 0.015f }.mapNotNull { l -> typeWords(l.text, inStatRow = true)?.let { l to it } }
            if (inRow.isNotEmpty()) return inRow.flatMap { it.second }.toSet() to inRow.map { it.first }
        }
        val from = hpLine?.bottom ?: 0f
        val to = powerUp?.top ?: (from + 0.3f)
        val found = lines.filter { it.top >= from && it.bottom <= to + 0.005f }.mapNotNull { l -> typeWords(l.text)?.let { l to it } }
        return found.flatMap { it.second }.toSet() to found.map { it.first }
    }

    private fun tokens(text: String) = text.split('/', ' ', '·').map { it.trim() }.filter { it.isNotEmpty() }

    private fun isStatLabel(token: String) = token.length >= 5 && statLabels.any { levenshtein(it, token.uppercase()) <= 1 }

    /**
     * Tipos de una línea ("Fire / Ghost"), o null si la línea tiene otra cosa. En la fila de peso/altura
     * ([inStatRow]) se ignoran rótulos y medidas (el OCR puede pegar "WEIGHT POISON / DARK HEIGHT") y se
     * aceptan tipos cortados por la valoración ("STEEL / RO" → rock, si el prefijo no es ambiguo).
     */
    private fun typeWords(text: String, inStatRow: Boolean = false): List<String>? {
        val w = tokens(text.lowercase()).filterNot { inStatRow && (isStatLabel(it) || it.any(Char::isDigit)) }
        if (w.isEmpty() || w.size > 2) return null
        return w.map { word ->
            if (word in typeNames) word
            else if (inStatRow && word.length >= 2) typeNames.singleOrNull { it.startsWith(word) } ?: return null
            else return null
        }
    }

    /**
     * Rótulo de la fila Dinamax/Gigamax, aunque esté cortado por el entrenador ("Giganta", "Dynama") o mal
     * leído ("Dvnamnax"). Solo se busca debajo de la fila de tipos, así que no lo confunde con etiquetas del usuario.
     */
    private fun isRowLabel(text: String, label: String): Boolean {
        val t = text.trim().uppercase()
        if (t.endsWith(".") || t.length < 5) return false
        return levenshtein(t, label.take(t.length)) <= (if (t.length >= 7) 2 else 1) ||
            (t.length > label.length && levenshtein(t, label) <= 2)
    }

    /** El nombre grande: justo encima de los PS, centrado, la línea más alta (no "LUCKY POKÉMON" ni el género). */
    private fun findNameLine(lines: List<OcrLine>, hpLine: OcrLine?): OcrLine? {
        hpLine ?: return null
        return lines.filter { it.bottom <= hpLine.top + 0.005f && it.top >= hpLine.top - 0.1f }
            .filter { it.centerX in 0.25f..0.75f && it.text.count(Char::isLetter) >= 2 }
            .filter { !luckyRegex.containsMatchIn(it.text) && typeWords(it.text) == null }
            .maxByOrNull { it.height }
    }

    /** "GASTLY CANDY", o "EXEGGCUTE" con "CANDY" en la línea de abajo (nombres largos se parten en dos). */
    private fun findCandyNames(lines: List<OcrLine>): List<String> {
        val out = mutableListOf<String>()
        lines.forEach { l ->
            val t = l.text.trim()
            candyRegex.find(t)?.groupValues?.get(1)?.let { out += it; return@forEach }
            // (sin exigir mayúsculas: el OCR a veces lee "scORBUNNY")
            if (t.length >= 3 && t.all(Char::isLetter)) {
                val below = lines.firstOrNull { b ->
                    b !== l && b.top >= l.bottom - 0.002f && b.top - l.bottom < l.height * 1.5f &&
                        abs(b.centerX - l.centerX) < 0.08f && b.text.trim().uppercase().startsWith("CANDY")
                }
                if (below != null && below.text.trim().uppercase() == "CANDY") out += t
            }
        }
        return out.filterNot { it.uppercase().endsWith(" XL") }.distinct()
    }

    // ================================================================ resolución

    private data class Option(val s: Species, val c: List<PokeMath.IvCandidate>, val identity: Int, val fit: Int) {
        val score get() = identity + fit
    }

    fun resolve(reading: ScreenReading, appraisal: Ivs?, forced: Species? = null): ScanResult {
        val nameMatches = reading.nameText?.let { nameCandidates(it) }.orEmpty()
        val candyBase = reading.candyNames.flatMap { speciesNamed(it) }
        val candyFamily = candyBase.flatMap { idx.family(it) + idx.byDex[it.dex].orEmpty() }
            .flatMap { listOf(it) + idx.byDex[it.dex].orEmpty() }.filter { !it.shadow && !it.mega }.distinctBy { it.id }
        val candyIds = candyFamily.map { it.id }.toSet()
        val captionMatches = reading.captionName?.let { speciesNamed(it) }.orEmpty()
        val captionIds = captionMatches.map { it.id }.toSet()

        // Dust → niveles posibles. Un purificado paga el 90 %; se nota por Return o porque solo esa tabla coincide.
        val (levels, purified) = levelsFor(reading)
        val minIv = maxOf(if (reading.lucky) 12 else 0, if (purified) 2 else 0)
        val allLevels = generateSequence(1.0) { it + 0.5 }.takeWhile { it <= minOf(levelCap, idx.maxLevel) }.toList()

        var pool = forced?.let { listOf(it) }
            ?: (nameMatches + candyFamily + captionMatches).filter { !it.shadow && !it.mega }.distinctBy { it.id }.take(40)
        // Ni nombre, ni caramelos, ni frase legibles (un apodo en la valoración): probar las especies de esos tipos.
        val guessedByTypes = pool.isEmpty() && reading.types.isNotEmpty() && (reading.cp != null || appraisal != null)
        if (guessedByTypes) pool = idx.byDex.values.flatten().filter { it.types.toSet() == reading.types }

        fun identity(s: Species): Int {
            val types = reading.types
            val typeScore = when {
                types.isEmpty() -> 0
                s.types.toSet() == types -> 100
                else -> s.types.count { it in types } * 10 - types.count { it !in s.types } * 30
            }
            val nameScore = if (nameMatches.any { it.id == s.id }) 20 else 0
            // La línea de caramelos y la frase de captura no pueden ser un apodo; el nombre grande sí.
            val candyScore = when { candyIds.isEmpty() -> 0; s.id in candyIds -> 30; else -> -40 }
            val captionScore = when { captionIds.isEmpty() -> 0; s.id in captionIds -> 40; else -> -60 }
            return typeScore + nameScore + candyScore + captionScore
        }

        fun solve(s: Species, cp: Int?, lv: List<Double>?) =
            PokeMath.solveIvs(s, cp, idx, hp = reading.hp, levels = lv ?: allLevels, appraisal = appraisal, minIv = minIv)

        fun options(cp: Int?): List<Option> = pool.map { s ->
            var c = solve(s, cp, levels)
            var fit = if (c.isNotEmpty() && levels != null) 50 else if (c.isNotEmpty()) 20 else 0
            // Si el polvo se leyó mal, mejor ignorarlo que descartar la especie.
            if (c.isEmpty() && levels != null) { c = solve(s, cp, null); if (c.isNotEmpty()) fit = 20 }
            Option(s, c, identity(s), fit)
        }

        var cp = reading.cp
        var cpInferred = false
        var cpNote: String? = null
        var opts = if (cp != null || appraisal != null) options(cp) else pool.map { Option(it, emptyList(), identity(it), 0) }

        // El PC leído no cuadra con NINGUNA especie posible (dígito tapado o mal leído): probar variantes
        // para la especie que mejor encaja con el resto y, si no hay una sola, descartarlo. (Si alguna especie
        // cuadra, el PC está bien y la que no cuadra es la pista dudosa, p. ej. un apodo con nombre de otra.)
        val bestIdentity = opts.maxOfOrNull { it.identity }
        if (cp != null && opts.none { it.c.isNotEmpty() }) {
            val target = opts.filter { it.identity == bestIdentity }.map { it.s }
            val fixes = reading.cpDigits?.let { cpVariants(it) }.orEmpty()
                .filter { v -> target.any { s -> solve(s, v, levels).isNotEmpty() } }
            val read = cp
            when {
                fixes.size == 1 -> { cp = fixes.first(); cpInferred = true; cpNote = "Leí PC $read, pero con los PS y el polvo solo cuadra PC $cp: revisalo." }
                appraisal != null -> { cp = null; cpNote = "El PC leído ($read) no cuadra: se dedujo de la valoración." }
                else -> { cp = null; cpNote = "El PC no se lee bien (leí $read, ¿lo tapa el Pokémon?): agregá la valoración o escribilo." }
            }
            opts = if (cp != null || appraisal != null) options(cp) else pool.map { Option(it, emptyList(), identity(it), 0) }
        }

        // Formas con mismos stats y tipos (disfraces de Pikachu, Genesect, Burmy…) dan los mismos IV:
        // no cuentan como ambigüedad; se queda la forma "simple" (sin paréntesis) si está.
        fun statKey(s: Species) = "${s.atk}|${s.def}|${s.sta}|${s.types.sorted()}"
        val solvable = opts.filter { it.c.isNotEmpty() }
        val ranked = (solvable.ifEmpty { opts }).groupBy { statKey(it.s) }.values
            .map { g -> g.firstOrNull { !it.s.name.contains("(") } ?: g.first() }
            .sortedByDescending { it.score }
        // Empate sin nada que lo decida (p. ej. sin PC: Mewtwo vs Mewtwo con armadura, que el juego muestra
        // igual): se elige la forma común, que es la que casi siempre es; la otra queda entre las opciones.
        // Solo si las formas empatadas tienen los mismos tipos: si difieren (Muk / Muk de Alola) y no se vieron
        // los tipos, la forma de verdad no se sabe y hay que preguntar.
        val top = ranked.filter { it.score == ranked.firstOrNull()?.score }
        val plain = top.filter { !it.s.name.contains("(") }
        val sameTypes = top.map { it.s.types.toSet() }.distinct().size == 1
        val tied = if (plain.size == 1 && sameTypes) plain else top
        val chosen = tied.firstOrNull()
        // Elegida solo por tipo + números: es una conjetura, nunca se da por buena sin que la confirmes.
        val ambiguous = tied.size > 1 || (guessedByTypes && chosen != null)
        val species = chosen?.s

        var candidates = chosen?.c.orEmpty()
        // Sin PC legible pero con valoración: el PC sale de los IV + PS (+ polvo).
        if (cp == null && candidates.isNotEmpty() && species != null) {
            val cps = candidates.map { PokeMath.cp(species, it.level, it.ivs, idx) }.distinct()
            if (cps.size == 1) { cp = cps.first(); cpInferred = true }
        }
        var appraisalConflict = false
        if (appraisal != null && species != null && candidates.isEmpty() && cp != null) {
            // Las barras pudieron leerse con un punto de error: probar IV vecinos.
            candidates = neighbors(appraisal).flatMap {
                PokeMath.solveIvs(species, cp, idx, hp = reading.hp, levels = levels ?: allLevels, appraisal = it, minIv = minIv)
            }
            if (candidates.isEmpty()) appraisalConflict = true
        }

        val movePool = species?.let { s ->
            (s.fast + s.charged + listOf("RETURN") + (if (reading.shadow) listOf("FRUSTRATION") else emptyList())).mapNotNull { idx.move(it) }
        }
        val foundMoves = reading.moveTexts.mapNotNull { matchMove(it, movePool) }.distinctBy { it.id }

        val problem = when {
            species == null -> "No se reconoció la especie: elegila a mano."
            guessedByTypes -> "No se leyó el nombre ni los caramelos: por PC, PS y tipo podría ser " +
                ranked.take(3).joinToString(", ") { it.s.name } + ". Confirmá la especie."
            ambiguous -> "Puede ser ${tied.joinToString(" o ") { it.s.name }}: confirmá la forma."
            appraisalConflict -> "La valoración ($appraisal) no coincide con el PC/PS leídos: revisá los IV."
            cpNote != null && candidates.isEmpty() -> cpNote
            cp == null -> "No se ve el PC (¿lo tapa el Pokémon?): agregá la valoración o escribilo."
            candidates.isEmpty() -> "Ningún IV da ese PC/PS: revisá la especie o el PC."
            else -> cpNote
        }

        return ScanResult(
            screen = if (reading.appraisalScreen) ScreenType.APPRAISAL else ScreenType.DETAIL,
            species = species,
            speciesOptions = (tied.map { it.s } + ranked.map { it.s } + pool).distinctBy { it.id },
            cp = cp, hp = reading.hp, dust = reading.dust,
            fast = foundMoves.firstOrNull { it.fast }?.id,
            charged = foundMoves.filter { !it.fast }.map { it.id }.take(2),
            shadow = reading.shadow, purified = purified, lucky = reading.lucky,
            dynamax = reading.dynamax, gigantamax = reading.gigantamax,
            nickname = species?.let { nicknameOf(reading.nameText, it) },
            appraisal = appraisal,
            candidates = candidates,
            speciesAmbiguous = ambiguous,
            appraisalConflict = appraisalConflict,
            cpInferred = cpInferred,
            problem = problem,
            reading = reading,
        )
    }

    /** Niveles compatibles con el polvo y si el Pokémon es purificado. Polvo ilegible o incoherente → null. */
    private fun levelsFor(r: ScreenReading): Pair<List<Double>?, Boolean> {
        val d = r.dust ?: return null to r.hasReturn
        fun lv(purified: Boolean) = PokeMath.levelsForDustCost(d, r.shadow, purified, r.lucky, idx.maxLevel).filter { it <= levelCap }
        if (r.shadow) return lv(false).ifEmpty { null } to false
        val normal = lv(false)
        val pure = lv(true)
        return when {
            r.hasReturn && pure.isNotEmpty() -> pure to true
            normal.isEmpty() && pure.isNotEmpty() -> pure to true
            else -> (normal + pure).distinct().sorted().ifEmpty { null } to r.hasReturn
        }
    }

    /** PC alternativos: un dígito tapado (insertar), mal leído (cambiar) o de más (quitar). */
    private fun cpVariants(d: String): List<Int> = buildSet {
        val digits = "0123456789"
        for (i in 0..d.length) for (c in digits) add(d.substring(0, i) + c + d.substring(i))
        for (i in d.indices) for (c in digits) add(d.substring(0, i) + c + d.substring(i + 1))
        for (i in d.indices) add(d.removeRange(i, i + 1))
    }.filter { !it.startsWith("0") && it != d }.mapNotNull { it.toIntOrNull() }.filter { it in 10..9999 }.distinct()

    /** Nombres de especie que muestra el juego, por clave (para corregir un error de OCR en caramelos/frase). */
    private val shownNames: Map<String, String> by lazy {
        idx.byDex.values.flatten().associate { GameIndex.nameKey(it.name.substringBefore(" (")) to it.name.substringBefore(" (") }
    }

    /**
     * Especies por un texto que SIEMPRE es un nombre de especie (caramelos, "This X was caught"): admite un
     * error de OCR (dos en nombres largos) si hay una sola especie así de parecida.
     */
    private fun speciesNamed(text: String): List<Species> {
        idx.findAllByName(text).takeIf { it.isNotEmpty() }?.let { return it }
        val k = GameIndex.nameKey(text)
        if (k.length < 5) return emptyList()
        val tol = if (k.length >= 8) 2 else 1
        val close = shownNames.filterKeys { abs(it.length - k.length) <= tol && levenshtein(it, k) <= tol }.values.distinct()
        return close.singleOrNull()?.let { idx.findAllByName(it) }.orEmpty()
    }

    /** Especies por el nombre grande, tolerando lo que el OCR agrega a un apodo ("Aggron9" por "Aggron⁸⁹", "ⒼCorvisq…"). */
    private fun nameCandidates(text: String): List<Species> {
        idx.findAllByName(text).takeIf { it.isNotEmpty() }?.let { return it }
        val letters = text.trim().trimEnd { !it.isLetter() }.trimStart { !it.isLetter() }
        idx.findAllByName(letters).takeIf { it.isNotEmpty() }?.let { return it }
        // Ⓖ y similares se leen como una mayúscula pegada: "GCorvisq".
        if (letters.length > 3 && letters[0].isUpperCase() && letters[1].isUpperCase()) {
            idx.findAllByName(letters.drop(1)).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    /** El nombre grande es apodo salvo que, sin lo que agrega el OCR (superíndices), sea el de la especie. */
    private fun nicknameOf(text: String?, s: Species): String? {
        text ?: return null
        val shown = (listOf(s) + idx.byDex[s.dex].orEmpty()).map { GameIndex.nameKey(it.name.substringBefore(" (")) }.toSet()
        val core = text.trim().trimEnd { !it.isLetter() }
        if (GameIndex.nameKey(text) in shown || GameIndex.nameKey(core) in shown) return null
        return text.trim()
    }

    private fun neighbors(iv: Ivs): List<Ivs> = buildList {
        for (da in -1..1) for (dd in -1..1) for (ds in -1..1) {
            if (da == 0 && dd == 0 && ds == 0) continue
            val n = Ivs(iv.atk + da, iv.def + dd, iv.sta + ds)
            if (listOf(n.atk, n.def, n.sta).all { it in 0..15 }) add(n)
        }
    }

    private fun parseNumber(s: String) = s.replace(Regex("[,.]"), "").toIntOrNull()

    /** Quita el ícono de tipo leído como letra ("O Dragon Tail", "@Retum") y la energía ("13+3"). */
    private fun stripMoveNoise(text: String) = text.trim()
        .replace(Regex("""\s*\d+\s*(\+\s*\d+)?\s*$"""), "")
        .replace(Regex("""^[^A-Za-z]+"""), "")
        .replace(Regex("""^[OoQe@]\s+"""), "")

    private fun matchMove(text: String, pool: List<Move>?): Move? {
        val k = key(stripMoveNoise(text))
        if (k.length < 3) return null
        val moves = pool ?: idx.data.moves
        val byKey = moves.groupBy { key(it.name.substringBefore(" (")) }
        byKey[k]?.let { return it.first() }
        // Un ícono pegado sin espacio ("OWing Attack"): probar sin la primera letra.
        if (k.length > 4) byKey[k.drop(1)]?.let { return it.first() }
        if (k.length < 5) return null
        // "rn" y "m" se confunden ("Retum" por "Return").
        val nk = ocrNorm(k)
        byKey.entries.filter { abs(it.key.length - k.length) <= 2 }
            .map { it.value.first() to levenshtein(ocrNorm(it.key), nk) }
            .filter { it.second <= (if (k.length >= 8) 2 else 1) }
            .minByOrNull { it.second }?.first?.let { return it }
        // Cortado por la barra de navegación o un botón ("Charge" por "Charge Beam"): prefijo único entre SUS ataques.
        if (pool != null) byKey.filterKeys { it.startsWith(k) }.values.singleOrNull()?.let { return it.first() }
        return null
    }

    private fun ocrNorm(k: String) = k.replace("rn", "m")

    companion object {
        fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

        /** Corrige confusiones típicas de OCR dentro de un número (O→0, l→1, S→5…) y quita espacios. */
        fun fixDigits(s: String): String = s.map {
            when (it) {
                'O', 'o' -> '0'; 'I', 'l', '|' -> '1'; 'S', 's' -> '5'; 'B' -> '8'; 'Z' -> '2'
                else -> it
            }
        }.joinToString("").replace(" ", "").replace(",", "").replace(".", "")

        fun levenshtein(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
