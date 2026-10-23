package com.esmeraldago.core

import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.scan.AppraisalBars
import com.esmeraldago.core.scan.OcrLine
import com.esmeraldago.core.scan.ScreenParser
import com.esmeraldago.core.scan.ScreenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Casos reales del escaneo (capturas del cliente en inglés leídas con ML Kit en un moto g54), reproducidos
 * con posiciones sintéticas para no subir las capturas. Cada test es un error que se encontró y se corrigió.
 */
class ScanRobustnessTest {
    companion object {
        val idx: GameIndex by lazy {
            val f = listOf("../app/src/main/assets/gamedata.json", "app/src/main/assets/gamedata.json").map(::File).first { it.exists() }
            GameIndex(GameDataSources.decode(f.readText()))
        }
    }

    private val parser = ScreenParser(idx)
    private fun l(text: String, x0: Float, y: Float, x1: Float, h: Float) = OcrLine(text, x0, y, x1, y + h)
    private fun fmt(n: Int) = String.format(Locale.US, "%,d", n)

    /** Pantalla de detalle con la geometría de un 1080×2400 (fracciones de la pantalla). */
    private fun detail(
        s: Species, level: Double, iv: Ivs,
        types: String, candy: String?,
        name: String = s.name.substringBefore(" ("),
        cpText: String? = "CP${PokeMath.cp(s, level, iv, idx)}",
        shadow: Boolean = false, purified: Boolean = false,
        moves: List<String> = emptyList(),
        extra: List<OcrLine> = emptyList(),
    ): List<OcrLine> = buildList {
        val hp = PokeMath.hp(s, level, iv, idx)
        if (cpText != null) add(l(cpText, 0.34f, 0.055f, 0.59f, 0.03f))
        add(l(name, 0.35f, 0.402f, 0.65f, 0.027f))
        add(l("$hp /$hp HP", 0.42f, 0.455f, 0.58f, 0.010f))
        add(l("Pokedex.", 0.36f, 0.494f, 0.53f, 0.014f))
        add(l("12.3kg", 0.12f, 0.562f, 0.30f, 0.019f)); add(l("WEIGHT", 0.15f, 0.590f, 0.26f, 0.010f))
        add(l(types, 0.39f, 0.590f, 0.64f, 0.010f))
        add(l("1.2m", 0.74f, 0.564f, 0.89f, 0.014f)); add(l("HEIGHT", 0.76f, 0.590f, 0.86f, 0.010f))
        add(l("37,427", 0.15f, 0.662f, 0.29f, 0.017f)); add(l("STARDUST", 0.15f, 0.690f, 0.29f, 0.008f))
        if (candy != null) add(l(candy, 0.42f, 0.690f, 0.62f, 0.009f))
        val pu = if (shadow) 0.823f else 0.758f
        if (shadow) { add(l("PURIFY", 0.20f, 0.758f, 0.34f, 0.012f)); add(l("3,000", 0.55f, 0.756f, 0.65f, 0.017f)) }
        add(l("POWER UP", 0.16f, pu, 0.38f, 0.013f))
        add(l(fmt(PokeMath.shownDust(level, shadow, purified)), 0.57f, pu - 0.002f, 0.66f, 0.017f))
        add(l("GYMS & RAIDS", 0.26f, 0.880f, 0.43f, 0.013f))
        moves.forEachIndexed { i, m -> add(l(m, 0.10f, 0.905f + i * 0.04f, 0.40f, 0.02f)) }
        addAll(extra)
    }

    /** Valoración: el detalle de arriba queda, lo de abajo lo tapan el entrenador y el cuadro de barras. */
    private fun appraisal(s: Species, level: Double, iv: Ivs, name: String, caption: String?, cpText: String? = "CP${PokeMath.cp(s, level, iv, idx)}") = buildList {
        val hp = PokeMath.hp(s, level, iv, idx)
        if (cpText != null) add(l(cpText, 0.34f, 0.054f, 0.59f, 0.031f))
        add(l(name, 0.42f, 0.397f, 0.58f, 0.029f))
        add(l("$hp/$hp HP", 0.43f, 0.453f, 0.58f, 0.014f))
        add(l("Attack.", 0.43f, 0.533f, 0.56f, 0.015f))
        add(l(s.types.joinToString(" / ") { it.uppercase() }, 0.45f, 0.630f, 0.58f, 0.010f))
        add(l("Attack", 0.12f, 0.732f, 0.22f, 0.011f)); add(l("Defense", 0.12f, 0.773f, 0.24f, 0.012f)); add(l("HP", 0.12f, 0.815f, 0.16f, 0.010f))
        if (caption != null) add(l(caption, 0.07f, 0.903f, 0.69f, 0.018f))
    }

    private fun bars(iv: Ivs) = AppraisalBars(iv.atk / 15f, iv.def / 15f, iv.sta / 15f)

    // ------------------------------------------------------------------ polvo

    @Test
    fun shadowDust_isShownRoundedUp() {
        // Un oscuro de Rocket a nivel 8 muestra 961 (no 960); a nivel 17, 2,640 (sin +1).
        assertEquals(961, PokeMath.shownDust(8.0, shadow = true))
        assertEquals(3601, PokeMath.shownDust(21.0, shadow = true))
        assertEquals(2640, PokeMath.shownDust(17.0, shadow = true))
        assertEquals(3600, PokeMath.shownDust(25.0, purified = true))
        val s = idx["krabby"]!!
        val r = parser.parse(detail(s, 8.0, Ivs(10, 5, 12), "WATER", "KRABBY CANDY", shadow = true))
        assertEquals(961, r.dust)
        // 800 de polvo (961 para oscuro) cubre los niveles 7 a 8.5.
        assertTrue(r.candidates.isNotEmpty() && r.candidates.all { it.level in 7.0..8.5 }, "el polvo fija el nivel: ${r.candidates.map { it.level }.distinct()}")
    }

    @Test
    fun purified_byDustOrReturn() {
        val s = idx["machamp"]!!
        val r = parser.parse(detail(s, 25.0, Ivs(4, 9, 15), "FIGHTING", "MACHOP CANDY", purified = true, moves = listOf("Counter", "@Retum")))
        assertTrue(r.purified)
        assertEquals("RETURN", r.charged.firstOrNull(), "Retum (rn→m) es Return")
        assertTrue(r.candidates.any { it.ivs == Ivs(4, 9, 15) })
        assertTrue(r.candidates.all { minOf(it.ivs.atk, it.ivs.def, it.ivs.sta) >= 2 }, "purificar suma 2 a cada IV")
    }

    // ------------------------------------------------------------------ PC

    @Test
    fun occludedCp_isNotInvented() {
        // El cuello del Exeggutor de Alola tapa un dígito: ML Kit lee "CP245" (es 2445).
        val s = idx["exeggutor_alolan"]!!
        val lv = 30.0; val iv = Ivs(13, 14, 12)
        val real = PokeMath.cp(s, lv, iv, idx)
        val cut = real.toString().removeRange(2, 3)
        val r = parser.parse(detail(s, lv, iv, "GRASS / DRAGON", "EXEGGCUTE CANDY", cpText = "CP$cut"))
        assertEquals("exeggutor_alolan", r.species?.id)
        assertNotEquals(cut.toInt(), r.cp, "no se queda con el número cortado")
        if (r.cp != null) assertEquals(real, r.cp) else assertNotNull(r.problem)
    }

    @Test
    fun cpWithoutLabel_isReadByPosition() {
        // El rótulo "CP" leído como letra ("Q2455") o tapado: el número grande centrado arriba.
        val s = idx["gyarados"]!!; val lv = 30.0; val iv = Ivs(12, 15, 15)
        val cp = PokeMath.cp(s, lv, iv, idx)
        assertEquals(cp, parser.parse(detail(s, lv, iv, "WATER / FLYING", "MAGIKARP CANDY", cpText = "Q$cp")).cp)
    }

    @Test
    fun cpHidden_isDerivedFromAppraisal() {
        // Mewtwo con el rótulo del PC tapado por el planeta: PS + IV de la valoración dan el PC.
        val s = idx["mewtwo"]!!; val lv = 20.5; val iv = Ivs(15, 15, 15)
        val r = parser.parse(appraisal(s, lv, iv, "Mewtwo", "This Mewtwo was caught on 8/22/2026", cpText = null), bars(iv))
        assertEquals("mewtwo", r.species?.id)
        assertEquals(PokeMath.cp(s, lv, iv, idx), r.cp)
        assertTrue(r.cpInferred)
    }

    // ------------------------------------------------------------------ especie / forma

    @Test
    fun appraisal_nicknamedPokemon_usesCaption() {
        // "Kira⁸⁹" se lee "Kira9"; la frase de abajo dice la especie.
        val s = idx["sylveon"]!!; val lv = 30.0; val iv = Ivs(15, 14, 11)
        val r = parser.parse(appraisal(s, lv, iv, "Kira9", "This Sylveon was caught on 9/5/2026"), bars(iv))
        assertEquals(ScreenType.APPRAISAL, r.screen)
        assertEquals("sylveon", r.species?.id)
        assertEquals(iv, r.appraisal)
        assertTrue(r.ivExact)
        assertEquals("Kira9", r.nickname)
    }

    @Test
    fun superscriptIvNickname_isNotANickname() {
        // "Gengar⁸⁹" (apodo de Poke Genie/Calcy) llega como "Gengar9": es Gengar sin apodo.
        val s = idx["gengar"]!!
        val r = parser.parse(detail(s, 30.0, Ivs(14, 12, 14), "GHOST / POISON", null, name = "Gengar9"))
        assertEquals("gengar", r.species?.id)
        assertNull(r.nickname)
    }

    @Test
    fun regionalForm_withoutCp_comesFromTypes() {
        val s = idx["moltres_galarian"]!!
        val r = parser.parse(detail(s, 20.0, Ivs(10, 10, 10), "DARK / FLYING", "MOLTRES CANDY", cpText = null, moves = listOf("Wing Attack", "Brave Bird")))
        assertEquals("moltres_galarian", r.species?.id)
        assertEquals("BRAVE_BIRD", r.charged.firstOrNull())
        assertNull(r.cp)
        assertNotNull(r.problem, "sin PC tiene que avisar")
    }

    @Test
    fun formsWithSameStats_withoutTypes_areAmbiguous() {
        // Muk y Muk de Alola tienen las mismas stats: sin tipos visibles no se puede saber cuál es.
        val s = idx["muk_alolan"]!!
        val r = parser.parse(detail(s, 25.0, Ivs(10, 10, 10), "", "GRIMER CANDY"))
        assertTrue(r.speciesAmbiguous)
    }

    @Test
    fun types_joinedRow_andCutByOverlay() {
        val s = idx["muk_alolan"]!!
        val joined = detail(s, 25.0, Ivs(10, 10, 10), "", "GRIMER CANDY").filterNot { it.text in setOf("WEIGHT", "HEIGHT", "") } +
            l("WEIGHT POISON / DARK HEIGHT", 0.15f, 0.590f, 0.86f, 0.010f)
        assertEquals(setOf("poison", "dark"), parser.read(joined).types)
        val aggron = idx["aggron"]!!
        val cut = detail(aggron, 30.0, Ivs(14, 14, 12), "STEEL / RO", "ARON CANDY")
        assertEquals(setOf("steel", "rock"), parser.read(cut).types)
    }

    @Test
    fun noNameNoCandy_guessIsNeverSilent() {
        val s = idx["fennekin"]!!
        val r = parser.parse(detail(s, 15.0, Ivs(14, 15, 15), "FIRE", null, name = "Bobby"))
        assertTrue(r.speciesAmbiguous && r.problem != null, "una conjetura por tipo no se da por buena: ${r.species?.id} ${r.problem}")
    }

    // ------------------------------------------------------------------ Dinamax / etiquetas / ruido

    @Test
    fun dynamaxRow_isDetected_userTagIsNot() {
        val s = idx["gyarados"]!!; val lv = 30.0; val iv = Ivs(12, 15, 15)
        val tagOnly = detail(s, lv, iv, "WATER / FLYING", "MAGIKARP CANDY", extra = listOf(l("Dynamax.", 0.30f, 0.534f, 0.47f, 0.016f)))
        assertFalse(parser.parse(tagOnly).dynamax, "la etiqueta del usuario no es la fila Dinamax")
        val misread = tagOnly + l("Dvnamnax", 0.44f, 0.630f, 0.63f, 0.018f)
        assertTrue(parser.parse(misread).dynamax)
        val cinder = idx["cinderace"]!!
        val gmax = parser.parse(detail(cinder, 30.0, Ivs(15, 13, 3), "FIRE", "SCORBUNNY CANDY", extra = listOf(l("Giganta", 0.44f, 0.630f, 0.60f, 0.018f))))
        assertTrue(gmax.gigantamax && gmax.dynamax)
    }

    @Test
    fun userTags_doNotLookLikeAppraisal() {
        val s = idx["gyarados"]!!
        val lines = detail(s, 30.0, Ivs(12, 15, 15), "WATER / FLYING", "MAGIKARP CANDY",
            extra = listOf(l("Attack.", 0.58f, 0.534f, 0.70f, 0.014f), l("Defense.", 0.30f, 0.534f, 0.45f, 0.014f)))
        assertFalse(parser.looksLikeAppraisal(lines))
    }

    @Test
    fun genderSymbol_andJunk_areNotNicknames() {
        val s = idx["axew"]!!
        val r = parser.parse(detail(s, 8.0, Ivs(5, 5, 5), "DRAGON", "AXEW CANDY", shadow = true,
            extra = listOf(l("O+", 0.87f, 0.428f, 0.90f, 0.023f))))
        assertEquals("axew", r.species?.id)
        assertNull(r.nickname)
    }

    @Test
    fun storageList_isNotAPokemonScreen() {
        // La lista del almacenamiento: muchos nombres y PC, ningún "x / y HP". Antes daba "Puede ser Delphox o
        // Dragonite o…" (10 especies) con ~200 combinaciones de IV.
        val names = listOf("Delphox", "Dragonite", "Gyarados", "Chesnaught", "Heracross", "Aggron", "Cinderace", "Snorlax", "Rhydon")
        val lines = names.flatMapIndexed { i, n ->
            val x = 0.05f + (i % 3) * 0.32f; val y = 0.20f + (i / 3) * 0.22f
            listOf(l("CP3126", x, y, x + 0.2f, 0.02f), l(n, x, y + 0.15f, x + 0.25f, 0.02f))
        }
        val r = parser.parse(lines)
        assertEquals(ScreenType.UNKNOWN, r.screen)
        assertNull(r.species)
        assertTrue(r.candidates.isEmpty())
    }

    @Test
    fun lowerHalfOfDetail_addsTheMoves() {
        // Modo en vivo: la ficha arriba (sin ataques visibles) + la misma ficha deslizada (sin PC ni PS).
        val s = idx["exeggutor_alolan"]!!; val lv = 30.0; val iv = Ivs(13, 14, 12)
        val top = parser.parse(detail(s, lv, iv, "GRASS / DRAGON", "EXEGGCUTE CANDY"))
        assertNull(top.fast)
        val lower = parser.parse(listOf(
            l("GYMS & RAIDS", 0.26f, 0.30f, 0.43f, 0.013f), l("TRAINER BATTLES", 0.55f, 0.30f, 0.78f, 0.013f),
            l("Dragon Tail", 0.17f, 0.35f, 0.37f, 0.025f), l("14", 0.88f, 0.35f, 0.92f, 0.015f),
            l("O Solar Beam", 0.08f, 0.40f, 0.36f, 0.021f), l("NEW ATTACK", 0.13f, 0.46f, 0.41f, 0.013f),
        ))
        assertEquals(ScreenType.UNKNOWN, lower.screen)
        val merged = parser.combine(top, lower)
        assertEquals("exeggutor_alolan", merged.species?.id)
        assertEquals("DRAGON_TAIL", merged.fast)
        assertEquals(listOf("SOLAR_BEAM"), merged.charged)
        assertEquals(top.cp, merged.cp)
        assertEquals(top.candidates.toSet(), merged.candidates.toSet())
    }

    @Test
    fun truncatedMove_byNavigationBar() {
        val s = idx["zekrom"]!!
        val r = parser.parse(detail(s, 21.0, Ivs(13, 14, 12), "DRAGON / ELECTRIC", "ZEKROM CANDY", shadow = true, moves = listOf("Charge")))
        assertEquals("CHARGE_BEAM", r.fast)
    }
}
