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
    val height get() = bottom - top
}

enum class ScreenType { DETAIL, APPRAISAL, UNKNOWN }

/** Fracción llena (0..1) de las tres barras de la valoración, medida sobre los píxeles. */
data class AppraisalBars(val atk: Float, val def: Float, val sta: Float) {
    fun toIvs() = Ivs(toIv(atk), toIv(def), toIv(sta))
    private fun toIv(f: Float) = (f * 15).roundToInt().coerceIn(0, 15)
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
    /** Por qué no se pudo leer (para mostrárselo al usuario). */
    val problem: String? = null,
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
 */
class ScreenParser(private val idx: GameIndex) {
    /**
     * Nivel máximo posible del Pokémon. Con el nivel de entrenador se acota a entrenador + 10 (máx. 50;
     * 51 con Mejor Compañero). El nivel lo fija sobre todo el costo de polvo; esto solo ayuda si no se leyó.
     */
    var levelCap: Double = idx.maxLevel

    // Caracteres que el OCR confunde con dígitos.
    private val digitLike = "0-9OoIl|SBZ"
    private val cpRegex = Regex("""\b[cC][pP]\s*[:.]?\s*([$digitLike][$digitLike ,.]{1,6})""")
    private val cpOnlyRegex = Regex("""^\s*[cC][pP]\s*[:.]?\s*$""")
    private val hpRegex = Regex("""([$digitLike]{1,4})\s*[/|Il]\s*([$digitLike]{1,4})\s*[hH][pP]""")
    private val powerUpRegex = Regex("""POWER\s*UP""", RegexOption.IGNORE_CASE)
    private val candyRegex = Regex("""^(?:\d[\d,.]*\s+)?(.+?)\s+CANDY$""", RegexOption.IGNORE_CASE)
    private val firstNumberRegex = Regex("""(\d{1,3}(?:[,.]\d{3})+|\d+)""")
    private val luckyRegex = Regex("""LUCKY\s+POK""", RegexOption.IGNORE_CASE)
    private val spanishHints = listOf("MEJORAR", "PURIFICAR", "CARAMELO", "POLVOS ESTELARES", "EVOLUCIONAR")

    private val validDust: Set<Int> = buildSet {
        for (base in listOf(200, 400, 600, 800, 1000, 1300, 1600, 1900, 2200, 2500, 3000, 3500, 4000, 4500,
            5000, 6000, 7000, 8000, 9000, 10000, 11000, 12000, 13000, 14000, 15000)) {
            add(base); add((base * 1.2).roundToInt()); add((base * 0.9).roundToInt()); add((base * 0.5).roundToInt())
        }
    }

    private val typeNames = Types.ALL.toSet()

    /** ¿Tiene esta captura los rótulos de la valoración? (para decidir si medir las barras) */
    fun looksLikeAppraisal(lines: List<OcrLine>): Boolean = lines.count { isAppraisalLabel(it.text) != null } >= 2

    /** "ATTACK" / "DEFENSE" / "HP" tolerando un error de OCR, o null. */
    fun isAppraisalLabel(text: String): String? {
        val t = text.trim().uppercase()
        if (t == "HP") return "HP"
        return listOf("ATTACK", "DEFENSE").firstOrNull { levenshtein(it, t) <= 1 }
    }

    fun parse(lines: List<OcrLine>, bars: AppraisalBars? = null): ScanResult {
        val sorted = lines.sortedBy { it.top }
        val detail = parseDetail(sorted)
        if (!looksLikeAppraisal(sorted)) return detail
        // La valoración deja visible la parte de arriba (CP, nombre, PS): con eso ya se resuelven los IV.
        val appraisal = bars?.toIvs()
            ?: return detail.copy(
                screen = ScreenType.APPRAISAL,
                problem = detail.problem ?: "No se pudieron medir las barras de la valoración.",
            )
        return if (detail.screen == ScreenType.UNKNOWN) ScanResult(ScreenType.APPRAISAL, appraisal = appraisal)
        else withAppraisal(detail, appraisal).copy(screen = ScreenType.APPRAISAL)
    }

    private fun parseDetail(lines: List<OcrLine>): ScanResult {
        val texts = lines.map { it.text.trim() }
        val upper = texts.map { it.uppercase() }

        val cp = findCp(lines)
        val hp = texts.firstNotNullOfOrNull { t -> hpRegex.find(t)?.groupValues?.get(2)?.let(::fixDigits)?.toIntOrNull() }
        if (cp == null && hp == null) {
            val spanish = upper.any { u -> spanishHints.any { it in u } }
            return ScanResult(
                ScreenType.UNKNOWN,
                problem = if (spanish) "Parece el juego en español: la app lee el cliente en inglés."
                else "No se encontró el PC ni los PS: ¿es la pantalla de un Pokémon?",
            )
        }

        val powerUp = lines.firstOrNull { powerUpRegex.containsMatchIn(it.text) }
        val shadow = upper.any { Regex("""\bPURIFY\b""").containsMatchIn(it) }
        val lucky = texts.any { luckyRegex.containsMatchIn(it) }
        val gigantamax = upper.any { it.contains("GIGANTAMAX") || it.contains("G-MAX") }
        val dynamax = gigantamax || upper.any { it.contains("MAX MOVES") || it.contains("MAX ATTACK") || it.contains("MAX GUARD") }
        val dust = findDust(lines, powerUp)

        // Tipos: líneas formadas solo por nombres de tipo ("Fire / Ghost") y POR ENCIMA de los ataques
        // (un ataque puede llamarse igual que un tipo: "Psychic").
        val typeZoneEnd = powerUp?.top ?: 0.62f
        val typeLines = lines.filter { isTypeOnly(it.text) && it.bottom <= typeZoneEnd + 0.005f }
        val shownTypes = typeLines.flatMap { words(it.text) }.toSet()

        // Candidatos de especie: nombre visible, familia por caramelos y todas las formas del mismo Nº.
        val nameMatches = texts.flatMap { idx.findAllByName(it) }.distinctBy { it.id }
        val candySpecies = texts.firstNotNullOfOrNull { t ->
            candyRegex.find(t)?.groupValues?.get(1)?.takeUnless { it.uppercase().endsWith(" XL") }
        }?.let { idx.findAllByName(it) }.orEmpty()
        val candyFamily = candySpecies.flatMap { base -> idx.family(base) + idx.byDex[base.dex].orEmpty() }
            .flatMap { listOf(it) + idx.byDex[it.dex].orEmpty() }
        val pool = (nameMatches + candyFamily).filter { !it.shadow && !it.mega }.distinctBy { it.id }.take(40)

        val allLevels = generateSequence(1.0) { it + 0.5 }.takeWhile { it <= minOf(levelCap, idx.maxLevel) }.toList()
        val levels = dust?.let { d ->
            (PokeMath.levelsForDustCost(d, shadow, false, lucky, idx.maxLevel) +
                PokeMath.levelsForDustCost(d, shadow, true, lucky, idx.maxLevel)).distinct().filter { it <= levelCap }.sorted()
        }?.ifEmpty { null }
        val minIv = if (lucky) 12 else 0

        data class Option(val s: Species, val c: List<PokeMath.IvCandidate>, val score: Int)
        val candyIds = candyFamily.map { it.id }.toSet()
        val options = if (cp == null) emptyList() else pool.mapNotNull { s ->
            var c = PokeMath.solveIvs(s, cp, idx, hp = hp, levels = levels ?: allLevels, minIv = minIv)
            val dustOk = c.isNotEmpty() && levels != null
            // Si el polvo se leyó mal, mejor ignorarlo que descartar la especie.
            if (c.isEmpty() && levels != null) c = PokeMath.solveIvs(s, cp, idx, hp = hp, levels = allLevels, minIv = minIv)
            if (c.isEmpty()) return@mapNotNull null
            val typeScore = when {
                shownTypes.isEmpty() -> 0
                s.types.toSet() == shownTypes -> 100
                else -> s.types.count { it in shownTypes } * 10 - shownTypes.count { it !in s.types } * 10
            }
            val nameScore = if (nameMatches.any { it.id == s.id }) 20 else 0
            // La línea de caramelos no puede ser un apodo; el nombre grande sí (un Machamp apodado "Grapploct").
            val candyScore = when {
                candyIds.isEmpty() -> 0
                s.id in candyIds -> 30
                else -> -40
            }
            Option(s, c, typeScore + nameScore + candyScore + (if (dustOk) 50 else 0))
        }.sortedByDescending { it.score }

        // Formas con mismos stats y tipos (disfraces de Pikachu, Genesect, Burmy…) dan los mismos IV:
        // no cuentan como ambigüedad; se queda la forma "simple" (sin paréntesis) si está.
        fun statKey(s: Species) = "${s.atk}|${s.def}|${s.sta}|${s.types.sorted()}"
        val distinct = options.groupBy { statKey(it.s) }.values
            .map { g -> g.firstOrNull { !it.s.name.contains("(") } ?: g.first() }
            .sortedByDescending { it.score }
        val chosen = distinct.firstOrNull()
        val ambiguous = distinct.size > 1 && distinct[1].score == chosen?.score
        val species = chosen?.s ?: pool.firstOrNull()

        // Movimientos: debajo del botón POWER UP (o del bloque de tipos), sin las líneas de tipo, en orden vertical.
        val movesFrom = powerUp?.bottom ?: typeLines.maxOfOrNull { it.bottom } ?: 0f
        val moveLines = lines.filter { it.top >= movesFrom - 0.005f && it !in typeLines }
        val movePool = species?.let { s ->
            (s.fast + s.charged + listOf("RETURN") + (if (shadow) listOf("FRUSTRATION") else emptyList())).mapNotNull { idx.move(it) }
        }
        val foundMoves = moveLines.mapNotNull { matchMove(it.text, movePool) }.distinctBy { it.id }

        val problem = when {
            species == null -> "No se reconoció la especie: elegila a mano."
            chosen == null -> "Ningún IV da ese PC/PS: revisá la especie o el PC."
            ambiguous -> "Puede ser ${distinct.filter { it.score == chosen.score }.joinToString(" o ") { it.s.name }}: confirmá la forma."
            else -> null
        }

        return ScanResult(
            screen = ScreenType.DETAIL,
            species = species,
            speciesOptions = (distinct.map { it.s } + options.map { it.s } + pool).distinctBy { it.id },
            cp = cp, hp = hp, dust = dust,
            fast = foundMoves.firstOrNull { it.fast }?.id,
            charged = foundMoves.filter { !it.fast }.map { it.id }.take(2),
            shadow = shadow, lucky = lucky, dynamax = dynamax, gigantamax = gigantamax,
            nickname = guessNickname(lines, species),
            candidates = chosen?.c.orEmpty(),
            speciesAmbiguous = ambiguous,
            problem = problem,
        )
    }

    /** Combina una pantalla de detalle con la de valoración (IV exactos). */
    fun withAppraisal(detail: ScanResult, appraisal: Ivs): ScanResult {
        val cp = detail.cp ?: return detail.copy(appraisal = appraisal)
        // Si la forma era ambigua, la valoración puede decidirla: queda la única forma compatible con esos IV.
        if (detail.speciesAmbiguous) {
            val fits = detail.speciesOptions.filter { PokeMath.solveIvs(it, cp, idx, hp = detail.hp, appraisal = appraisal).isNotEmpty() }
            if (fits.map { "${it.atk}|${it.def}|${it.sta}" }.distinct().size == 1) {
                return withAppraisal(detail.copy(species = fits.first(), speciesAmbiguous = false, problem = null), appraisal)
            }
        }
        val s = detail.species ?: return detail.copy(appraisal = appraisal)
        val c = PokeMath.solveIvs(s, cp, idx, hp = detail.hp, appraisal = appraisal)
        // Si las barras se leyeron con un punto de error, probar IV vecinos.
        val fixed = c.ifEmpty {
            neighbors(appraisal).flatMap { PokeMath.solveIvs(s, cp, idx, hp = detail.hp, appraisal = it) }
        }
        return if (fixed.isEmpty()) detail.copy(
            appraisal = appraisal, appraisalConflict = true,
            problem = "La valoración (${appraisal}) no coincide con el PC/PS leídos: revisá los IV.",
        ) else detail.copy(appraisal = appraisal, candidates = fixed)
    }

    private fun neighbors(iv: Ivs): List<Ivs> = buildList {
        for (da in -1..1) for (dd in -1..1) for (ds in -1..1) {
            if (da == 0 && dd == 0 && ds == 0) continue
            val n = Ivs(iv.atk + da, iv.def + dd, iv.sta + ds)
            if (listOf(n.atk, n.def, n.sta).all { it in 0..15 }) add(n)
        }
    }

    private fun findCp(lines: List<OcrLine>): Int? {
        lines.firstNotNullOfOrNull { l -> cpRegex.find(l.text)?.groupValues?.get(1)?.let(::fixDigits)?.toIntOrNull() }
            ?.takeIf { it in 10..9999 }?.let { return it }
        // "CP" y el número en líneas separadas de la misma fila.
        val label = lines.firstOrNull { cpOnlyRegex.matches(it.text) } ?: return null
        return lines.filter { it !== label && abs(it.centerY - label.centerY) < max(label.height, 0.01f) && it.left >= label.left }
            .minByOrNull { it.left }?.text?.let(::fixDigits)?.toIntOrNull()?.takeIf { it in 10..9999 }
    }

    /** Polvo del botón POWER UP: solo números de esa misma fila, a la derecha del texto. */
    private fun findDust(lines: List<OcrLine>, powerUp: OcrLine?): Int? {
        powerUp ?: return null
        // Mismo renglón: "POWER UP 5,000 4"
        powerUpRegex.split(powerUp.text).getOrNull(1)?.let { rest ->
            firstNumberRegex.find(rest)?.value?.let(::parseNumber)?.takeIf { it in validDust }?.let { return it }
        }
        val tolerance = max(powerUp.height * 0.6f, 0.012f)
        return lines.filter { it !== powerUp && abs(it.centerY - powerUp.centerY) < tolerance && it.left >= powerUp.left }
            .sortedBy { it.left }
            .firstNotNullOfOrNull { l ->
                // Una línea que es solo "número" puede traer letras confundidas ("5,OOO").
                val text = if (Regex("""^[$digitLike,.\s]+$""").matches(l.text)) fixDigits(l.text) else l.text
                firstNumberRegex.find(text)?.value?.let(::parseNumber)?.takeIf { it in validDust }
            }
    }

    private fun parseNumber(s: String) = s.replace(Regex("[,.]"), "").toIntOrNull()

    private fun words(text: String) = text.lowercase().split('/', ' ', '·').map { it.trim() }.filter { it.isNotEmpty() }

    private fun isTypeOnly(text: String): Boolean {
        val w = words(text)
        return w.isNotEmpty() && w.size <= 2 && w.all { it in typeNames }
    }

    private fun matchMove(text: String, pool: List<Move>?): Move? {
        val k = key(text.replace(Regex("\\d+\\s*$"), ""))
        if (k.length < 3) return null
        val moves = pool ?: idx.data.moves
        val byKey = moves.groupBy { key(it.name.substringBefore(" (")) }
        byKey[k]?.let { return it.first() }
        if (k.length < 6) return null
        return byKey.entries.filter { abs(it.key.length - k.length) <= 2 }
            .map { it.value.first() to levenshtein(it.key, k) }
            .filter { it.second <= 2 }
            .minByOrNull { it.second }?.first
    }

    /** El nombre grande bajo el modelo: la línea más alta entre el CP y los PS que no sea la especie. */
    private fun guessNickname(lines: List<OcrLine>, s: Species?): String? {
        val cpLine = lines.firstOrNull { cpRegex.containsMatchIn(it.text) || cpOnlyRegex.matches(it.text) } ?: return null
        val hpLine = lines.firstOrNull { hpRegex.containsMatchIn(it.text) } ?: return null
        val candidate = lines.filter { it.top > cpLine.bottom && it.bottom <= hpLine.top + 0.01f }
            .filter { it.text.any(Char::isLetter) && !luckyRegex.containsMatchIn(it.text) && !isTypeOnly(it.text) }
            .maxByOrNull { it.height }?.text?.trim() ?: return null
        if (s == null) return candidate
        val shownNames = (listOf(s) + idx.byDex[s.dex].orEmpty()).map { GameIndex.nameKey(it.name.substringBefore(" (")) }
        return candidate.takeIf { GameIndex.nameKey(it) !in shownNames }
    }

    companion object {
        fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

        /** Corrige confusiones típicas de OCR dentro de un número (O→0, l→1, S→5…) y quita espacios. */
        fun fixDigits(s: String): String = s.map {
            when (it) {
                'O', 'o' -> '0'; 'I', 'l', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'
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
