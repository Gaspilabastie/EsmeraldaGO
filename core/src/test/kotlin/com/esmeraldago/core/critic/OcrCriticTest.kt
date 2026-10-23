package com.esmeraldago.core.critic

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.scan.AppraisalBars
import com.esmeraldago.core.scan.OcrLine
import com.esmeraldago.core.scan.ScanResult
import com.esmeraldago.core.scan.ScreenParser
import com.esmeraldago.core.scan.ScreenType
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Casos sintéticos que imitan el layout real de la pantalla de detalle de Pokémon GO (cliente en inglés,
 * captura vertical ~1080x2400). Un test que FALLA es evidencia de un problema de robustez del escaneo.
 */
class OcrCriticTest {
    companion object {
        val idx: GameIndex by lazy {
            val f = listOf("../app/src/main/assets/gamedata.json", "app/src/main/assets/gamedata.json").map(::File).first { it.exists() }
            GameIndex(GameDataSources.decode(f.readText()))
        }
        val parser by lazy { ScreenParser(idx) }
    }

    private fun l(t: String, x0: Float, y: Float, x1: Float, h: Float = 0.025f) = OcrLine(t, x0, y, x1, y + h)
    private fun fmt(n: Int) = String.format(Locale.US, "%,d", n)

    /** Pantalla de detalle con posiciones aproximadas a las reales. */
    private fun detail(
        s: Species, level: Double, iv: Ivs,
        types: String,
        candy: String,
        moves: List<String>,
        name: String = s.name.substringBefore(" ("),
        shadow: Boolean = false, lucky: Boolean = false, purified: Boolean = false,
        cpText: (Int) -> String = { "CP$it" },
        hpText: (Int) -> String = { "$it / $it HP" },
        candyCount: String = "245",
        powerUpText: String = "POWER UP",
        dustText: ((Int) -> String)? = { fmt(it) },
        extra: List<OcrLine> = emptyList(),
    ): List<OcrLine> {
        val cp = PokeMath.cp(s, level, iv, idx)
        val hp = PokeMath.hp(s, level, iv, idx)
        val mult = (if (shadow) 1.2 else if (purified) 0.9 else 1.0) * (if (lucky) 0.5 else 1.0)
        val dust = (PokeMath.dustFor(level) * mult).roundToInt()
        val out = mutableListOf(
            l(cpText(cp), 0.33f, 0.075f, 0.67f, 0.035f),
            l(name, 0.30f, 0.420f, 0.70f, 0.035f),
            l(hpText(hp), 0.38f, 0.470f, 0.62f, 0.018f),
            l("130.0kg", 0.08f, 0.520f, 0.28f, 0.02f), l("WEIGHT", 0.10f, 0.545f, 0.26f, 0.015f),
            l(types, 0.36f, 0.520f, 0.64f, 0.02f),
            l("1.6m", 0.72f, 0.520f, 0.90f, 0.02f), l("HEIGHT", 0.74f, 0.545f, 0.88f, 0.015f),
            l("187,345", 0.10f, 0.600f, 0.35f, 0.025f), l("STARDUST", 0.12f, 0.628f, 0.33f, 0.015f),
            l(candyCount, 0.58f, 0.600f, 0.70f, 0.025f), l(candy, 0.50f, 0.628f, 0.85f, 0.015f),
            l(powerUpText, 0.10f, 0.665f, 0.42f, 0.03f),
        )
        if (dustText != null) out += l(dustText(dust), 0.58f, 0.668f, 0.72f, 0.025f)
        out += l(PokeMath.candyFor(level).first.coerceAtLeast(1).toString(), 0.83f, 0.668f, 0.88f, 0.025f)
        if (lucky) out += l("LUCKY POKÉMON", 0.35f, 0.395f, 0.65f, 0.015f)
        if (shadow) { out += l("PURIFY", 0.10f, 0.715f, 0.38f, 0.03f); out += l("3,000", 0.58f, 0.718f, 0.72f, 0.025f) }
        moves.forEachIndexed { i, mv ->
            val y = 0.78f + i * 0.06f
            out += l(mv, 0.20f, y, 0.60f, 0.025f)
            val power = idx.data.moves.firstOrNull { ScreenParser.key(it.name) == ScreenParser.key(mv) }?.power?.toInt() ?: 50
            out += l(power.toString(), 0.80f, y, 0.88f, 0.025f)
        }
        return out + extra
    }

    private fun ScanResult.has(iv: Ivs, level: Double) = candidates.any { it.ivs == iv && it.level == level }

    // ---------------------------------------------------------------- Línea base (debe pasar)

    @Test
    fun baseline_cleanMachamp() {
        val s = idx["machamp"]!!; val iv = Ivs(15, 14, 12); val lv = 30.0
        val r = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter", "Dynamic Punch", "Cross Chop")))
        assertEquals(ScreenType.DETAIL, r.screen)
        assertEquals("machamp", r.species?.id)
        assertEquals("COUNTER", r.fast)
        assertEquals(listOf("DYNAMIC_PUNCH", "CROSS_CHOP"), r.charged)
        assertTrue(r.has(iv, lv), "candidatos ${r.candidates}")
        assertNull(r.nickname)
    }

    @Test
    fun works_cpVariantsDamagedHpShadowLucky() {
        val s = idx["machamp"]!!; val iv = Ivs(12, 13, 14); val lv = 25.0
        for (cpFmt in listOf<(Int) -> String>({ "cP $it" }, { "CP:$it" }, { "CP $it" }, { "cp$it" })) {
            val r = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), cpText = cpFmt))
            assertEquals(PokeMath.cp(s, lv, iv, idx), r.cp, cpFmt(1234))
        }
        val hp = PokeMath.hp(s, lv, iv, idx)
        val dmg = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), hpText = { "57/${it}HP" }))
        assertEquals(hp, dmg.hp)
        val sh = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), shadow = true))
        assertTrue(sh.shadow && sh.has(iv, lv), "shadow: ${sh.candidates}")
        val lk = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), lucky = true))
        assertTrue(lk.lucky && lk.has(iv, lv), "lucky: ${lk.candidates}")
        assertEquals(Ivs(15, 0, 8), AppraisalBars(1f, 0f, 0.5f).toIvs())
    }

    // ---------------------------------------------------------------- CP / HP con ruido de OCR

    @Test
    fun cp_splitIntoTwoOcrLines() {
        // "CP" se dibuja chico y el número grande: ML Kit a veces los devuelve como líneas distintas.
        val s = idx["machamp"]!!; val iv = Ivs(15, 14, 12); val lv = 30.0
        val lines = detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter")).toMutableList()
        val cpLine = lines.removeAt(0)
        val num = cpLine.text.removePrefix("CP")
        lines += l("CP", 0.33f, 0.085f, 0.40f, 0.02f)
        lines += l(num, 0.41f, 0.075f, 0.67f, 0.035f)
        val r = parser.parse(lines)
        assertEquals(PokeMath.cp(s, lv, iv, idx), r.cp, "CP perdido al separarse 'CP' del número")
    }

    @Test
    fun cp_ocrConfusesDigits() {
        // Mewtwo L40 15/15/15 = CP 4178 -> forzamos un CP con '1' y '0'. Usamos Machamp L20 -> CP ~1900-2000.
        val s = idx["machamp"]!!; val iv = Ivs(10, 10, 10); val lv = 20.0
        val cp = PokeMath.cp(s, lv, iv, idx)
        assertTrue(cp.toString().contains('1') || cp.toString().contains('0'), "precondición: cp=$cp")
        val noisy = { c: Int -> "CP" + c.toString().replace('1', 'l').replace('0', 'O') }
        val r1 = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), cpText = noisy))
        val kerned = { c: Int -> "CP" + c.toString().let { it.take(1) + " " + it.drop(1) } } // "CP1 862"
        val r2 = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), cpText = kerned))
        assertEquals(listOf(cp, cp), listOf(r1.cp, r2.cp), "O/0, l/1 o espacio de kerning rompen el CP")
    }

    @Test
    fun hp_ocrConfusesDigitsOrSlash() {
        val s = idx["machamp"]!!; val iv = Ivs(10, 10, 10); val lv = 20.0
        val hp = PokeMath.hp(s, lv, iv, idx)
        val a = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"),
            hpText = { "$it / ${it.toString().replace('1', 'l')} HP" }))
        val b = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), hpText = { "$it I $it HP" }))
        assertEquals(listOf(hp, hp), listOf(a.hp, b.hp), "HP perdido (l/1 o '/' leído como 'I')")
    }

    // ---------------------------------------------------------------- Polvo estelar

    @Test
    fun dust_costMergedWithCandyCost_picksCandyCount() {
        // ML Kit suele unir "5,000" y "4" (mismo renglón, a la derecha del botón) en una sola línea.
        val s = idx["machamp"]!!; val iv = Ivs(15, 14, 12); val lv = 30.0
        val lines = detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter", "Dynamic Punch"),
            candyCount = "600", dustText = { "${fmt(it)} 4" })
        val r = parser.parse(lines)
        assertTrue(r.dust == null || r.dust == 5000, "polvo leído=${r.dust} (debería ser 5000 o null)")
        assertTrue(r.has(iv, lv), "IV reales descartados por polvo erróneo: ${r.candidates.size} candidatos")
    }

    @Test
    fun dust_powerUpReadWithoutSpace_picksNewAttackCost() {
        // "POWERUP" (espacio perdido) -> fallback "número más abajo" = costo de NEW ATTACK (10,000).
        val s = idx["machamp"]!!; val iv = Ivs(15, 14, 12); val lv = 30.0
        val lines = detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter", "Dynamic Punch"),
            powerUpText = "POWERUP",
            extra = listOf(l("NEW ATTACK", 0.10f, 0.92f, 0.42f, 0.03f), l("10,000", 0.58f, 0.923f, 0.72f, 0.025f), l("25", 0.83f, 0.923f, 0.88f, 0.025f)))
        val r = parser.parse(lines)
        assertTrue(r.dust == null || r.dust == 5000, "polvo leído=${r.dust}")
        assertTrue(r.has(iv, lv), "candidatos=${r.candidates.size}")
    }

    @Test
    fun dust_purifiedCollidesWithBaseTable() {
        // Purificado: costo x0.9. 5000*0.9 = 4500 = costo base de nivel 27-28.5 -> niveles equivocados.
        val s = idx["machamp"]!!; val iv = Ivs(15, 14, 12); val lv = 29.5
        val r = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), purified = true))
        assertEquals(4500, r.dust)
        assertTrue(r.has(iv, lv), "purificado L$lv descartado: ${r.candidates}")
    }

    @Test
    fun lucky_falsePositiveFromNickname() {
        val s = idx["machamp"]!!; val iv = Ivs(15, 14, 12); val lv = 30.0
        val r = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), name = "Lucky"))
        assertTrue(!r.lucky, "apodo 'Lucky' marcó lucky=true (polvo x0.5 y minIv 12)")
        assertTrue(r.has(iv, lv), "candidatos=${r.candidates}")
    }

    // ---------------------------------------------------------------- Especie / formas

    @Test
    fun regionalForm_alolanRaichu_ignoresVisibleTypes() {
        // Raichu de Alola (201/154/155) con Atk<=7 y Def<=12 tiene exactamente el mismo CP/PS que un Raichu de Kanto.
        val s = idx["raichu_alolan"]!!; val iv = Ivs(3, 5, 10); val lv = 25.0
        val r = parser.parse(detail(s, lv, iv, "Electric / Psychic", "PIKACHU CANDY", listOf("Volt Switch", "Wild Charge", "Psychic")))
        assertEquals("raichu_alolan", r.species?.id, "tipos visibles Electric/Psychic ignorados")
    }

    @Test
    fun regionalForm_alolanMarowak_sameStats_wrongSpeciesAndNoMoves() {
        val s = idx["marowak_alolan"]!!; val iv = Ivs(10, 12, 14); val lv = 30.0
        val r = parser.parse(detail(s, lv, iv, "Fire / Ghost", "CUBONE CANDY", listOf("Fire Spin", "Shadow Bone", "Bone Club")))
        assertEquals("marowak_alolan", r.species?.id)
        assertEquals("FIRE_SPIN", r.fast)
    }

    @Test
    fun regionalForm_speciesWithoutFamily_galarianFarfetchdUnreachable() {
        // "farfetchd" no tiene family en gamedata (206 especies sin family) -> la forma de Galar nunca entra al pool.
        val s = idx["farfetchd_galarian"]!!; val iv = Ivs(10, 10, 10); val lv = 25.0
        val r = parser.parse(detail(s, lv, iv, "Fighting", "FARFETCH'D CANDY", listOf("Fury Cutter", "Leaf Blade")))
        assertEquals("farfetchd_galarian", r.species?.id, "pool=${r.speciesOptions.map { it.id }} candidatos=${r.candidates.size}")
    }

    @Test
    fun regionalForm_galarianZapdos_unreachable() {
        val s = idx["zapdos_galarian"]!!; val iv = Ivs(13, 14, 15); val lv = 20.0
        val r = parser.parse(detail(s, lv, iv, "Fighting / Flying", "ZAPDOS CANDY", listOf("Counter", "Brave Bird")))
        assertEquals("zapdos_galarian", r.species?.id, "pool=${r.speciesOptions.map { it.id }} candidatos=${r.candidates.size}")
    }

    @Test
    fun regionalForm_galarianMrMime_spuriousNickname() {
        val s = idx["mr_mime_galarian"]!!; val iv = Ivs(10, 10, 10); val lv = 25.0
        val r = parser.parse(detail(s, lv, iv, "Ice / Psychic", "MR. MIME CANDY", listOf("Confusion", "Ice Punch")))
        assertEquals("mr_mime_galarian", r.species?.id)
        assertNull(r.nickname, "el nombre 'Mr. Mime' se toma como apodo de la forma de Galar")
    }

    @Test
    fun formSpecies_giratinaNotFound() {
        val s = idx["giratina_altered"]!!; val iv = Ivs(15, 15, 15); val lv = 20.0
        val r = parser.parse(detail(s, lv, iv, "Ghost / Dragon", "GIRATINA CANDY", listOf("Shadow Claw", "Dragon Claw")))
        assertNotNull(r.species, "'Giratina' / 'GIRATINA CANDY' no resuelven")
    }

    @Test
    fun displayedNamesThatDoNotResolve() {
        // Nombre que muestra el juego = nombre sin el paréntesis de forma.
        val bad = idx.data.species.filter { it.released && !it.shadow && !it.mega && it.id != "mime_jr" } // "Mime (Jr)" ya resuelve "Mime Jr."
            .map { it.name.substringBefore(" (") to it.dex }.distinct()
            .filter { (n, dex) -> idx.findByName(n)?.dex != dex }
            .map { it.first }
        assertTrue(bad.isEmpty(), "${bad.size} nombres mostrados por el juego no se encuentran: $bad")
    }

    @Test
    fun nidoran_genderSymbolDroppedByOcr() {
        val s = idx["nidoran_female"]!!; val iv = Ivs(10, 10, 10); val lv = 20.0
        val r = parser.parse(detail(s, lv, iv, "Poison", "NIDORAN CANDY", listOf("Bite"), name = "Nidoran"))
        assertEquals("nidoran_female", r.species?.id, "sin '♀' (ML Kit latin no lo reconoce) no hay especie")
    }

    // ---------------------------------------------------------------- Movimientos

    @Test
    fun moves_typeLinePsychicCountedAsChargedMove() {
        val s = idx["alakazam"]!!; val iv = Ivs(10, 10, 10); val lv = 30.0
        val r = parser.parse(detail(s, lv, iv, "Psychic", "ABRA CANDY", listOf("Psycho Cut", "Fire Punch", "Focus Blast")))
        assertEquals("PSYCHO_CUT", r.fast)
        assertEquals(setOf("FIRE_PUNCH", "FOCUS_BLAST"), r.charged.toSet(), "la línea de tipo 'Psychic' desplaza un ataque real")
    }

    @Test
    fun moves_frustrationOnShadowNotDetected() {
        val s = idx["machamp"]!!; val iv = Ivs(10, 10, 10); val lv = 25.0
        val r = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter", "Frustration", "Cross Chop"), shadow = true))
        assertTrue("FRUSTRATION" in r.charged, "charged=${r.charged}")
    }

    @Test
    fun moves_hiddenPowerNotDetected() {
        val s = idx["ho_oh"]!!; val iv = Ivs(15, 15, 15); val lv = 20.0
        val r = parser.parse(detail(s, lv, iv, "Fire / Flying", "HO-OH CANDY", listOf("Hidden Power", "Brave Bird")))
        assertEquals("ho_oh", r.species?.id)
        assertTrue(r.fast?.startsWith("HIDDEN_POWER") == true, "fast=${r.fast}")
    }

    // ---------------------------------------------------------------- Valoración

    private fun appraisalScreen(s: Species, lv: Double, iv: Ivs): List<OcrLine> {
        val cp = PokeMath.cp(s, lv, iv, idx); val hp = PokeMath.hp(s, lv, iv, idx)
        return listOf(
            l("CP$cp", 0.33f, 0.075f, 0.67f, 0.035f), l(s.name, 0.30f, 0.42f, 0.70f, 0.035f), l("$hp / $hp HP", 0.38f, 0.47f, 0.62f, 0.018f),
            l("Overall, your ${s.name} is a wonder! What a breathtaking Pokémon!", 0.08f, 0.62f, 0.92f, 0.05f),
            l("Attack", 0.10f, 0.78f, 0.25f, 0.02f), l("Defense", 0.10f, 0.84f, 0.27f, 0.02f), l("HP", 0.10f, 0.90f, 0.16f, 0.02f),
        )
    }

    @Test
    fun appraisal_barsUnmeasured_becomesDuplicateDetail() {
        // Si measureBars devuelve null (un rótulo mal leído, barra < 15% del ancho), la captura de valoración
        // se clasifica como DETAIL -> ScanScreen no la empareja y crea un borrador duplicado.
        val s = idx["machamp"]!!; val iv = Ivs(15, 14, 12); val lv = 30.0
        val r = parser.parse(appraisalScreen(s, lv, iv), bars = null)
        assertTrue(parser.looksLikeAppraisal(appraisalScreen(s, lv, iv)))
        assertEquals(ScreenType.APPRAISAL, r.screen, "valoración sin barras tratada como ${r.screen}")
    }

    // ================================================================ Ronda 2: casos contra el parser reescrito

    @Test
    fun r2_moves_realPsychicMoveDroppedAsTypeLine() {
        // El fix de OCR-6 excluye toda línea "solo tipos": el ataque cargado "Psychic" ES una línea así.
        val s = idx["alakazam"]!!; val iv = Ivs(10, 10, 10); val lv = 30.0
        val r = parser.parse(detail(s, lv, iv, "Psychic", "ABRA CANDY", listOf("Psycho Cut", "Psychic", "Focus Blast")))
        assertEquals(setOf("PSYCHIC", "FOCUS_BLAST"), r.charged.toSet(), "charged=${r.charged}")
    }

    @Test
    fun r2_moves_psychicMovePollutesShownTypes() {
        // Clefable (Fairy) con "Psychic": la línea del ataque entra en shownTypes = {fairy, psychic}.
        val s = idx["clefable"]!!; val iv = Ivs(10, 10, 10); val lv = 30.0
        val r = parser.parse(detail(s, lv, iv, "Fairy", "CLEFAIRY CANDY", listOf("Charm", "Psychic", "Moonblast")))
        assertEquals("clefable", r.species?.id)
        assertTrue("PSYCHIC" in r.charged, "charged=${r.charged}")
    }

    @Test
    fun r2_identicalForms_alwaysAmbiguous_evenWithAppraisal() {
        val s = idx["pikachu"]!!; val iv = Ivs(15, 14, 13); val lv = 25.0
        val r = parser.parse(detail(s, lv, iv, "Electric", "PIKACHU CANDY", listOf("Thunder Shock", "Wild Charge")))
        val m = parser.withAppraisal(r, iv)
        assertTrue(m.ivExact, "Pikachu normal: ambiguous=${m.speciesAmbiguous} options=${r.speciesOptions.map { it.id }} problem=${m.problem}")
    }

    @Test
    fun r2_formsWithIdenticalStatsAndTypes() {
        // Formas del mismo Nº con stats y tipos idénticos: el parser no puede distinguirlas -> nunca IV exacto.
        val groups = idx.byDex.values.mapNotNull { forms ->
            val g = forms.groupBy { Triple(it.atk to it.def, it.sta, it.types.toSet()) }.values.filter { it.size > 1 }
            g.takeIf { it.isNotEmpty() }?.flatten()
        }
        // (Ronda 2) las formas indistinguibles existen en los datos; lo que importa es que el parser no las trate
        // como ambiguas (dan los mismos IV): se verifica con una captura sintética de cada grupo.
        val stillAmbiguous = groups.mapNotNull { forms ->
            val s = forms.first(); val iv = Ivs(12, 13, 14); val lv = 25.0
            val r = parser.parse(detail(s, lv, iv, s.types.joinToString(" / ") { it.replaceFirstChar(Char::uppercase) },
                "${s.name.substringBefore(" (").uppercase()} CANDY", emptyList(), name = s.name.substringBefore(" (")))
            s.id.takeIf { r.speciesAmbiguous || r.species == null }
        }
        println("Grupos de formas idénticas: ${groups.size}; siguen ambiguos en el parser: $stillAmbiguous")
        assertTrue(stillAmbiguous.isEmpty(), "formas idénticas marcadas como ambiguas: $stillAmbiguous")
    }

    @Test
    fun r2_nicknameEqualToOtherSpecies_retryWithoutLevelsHijacks() {
        // Apodo = otra especie del mismo tipo: obtiene +20 por nombre y, si no cuadra con el polvo, se reintenta SIN niveles.
        val truth = idx["machamp"]!!
        val bad = mutableListOf<String>()
        for ((iv, lv) in listOf(Ivs(15, 14, 12) to 30.0, Ivs(5, 8, 3) to 22.0, Ivs(10, 15, 15) to 35.0)) {
            for (other in idx.byDex.values.flatten().filter { it.types == listOf("fighting") && it.dex != truth.dex }) {
                val r = parser.parse(detail(truth, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), name = other.name.substringBefore(" (")))
                if (r.species?.id != "machamp" || r.speciesAmbiguous) bad += "${other.id}@L$lv->${r.species?.id}${if (r.speciesAmbiguous) "(amb)" else ""}"
            }
        }
        assertTrue(bad.isEmpty(), "apodos que desvían la especie: $bad")
    }

    @Test
    fun r2_cpWithThousandsSeparator() {
        val s = idx["machamp"]!!; val iv = Ivs(10, 10, 10); val lv = 20.0
        val cp = PokeMath.cp(s, lv, iv, idx)
        val r = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"),
            cpText = { "CP " + it.toString().let { t -> t.take(1) + "," + t.drop(1) } }))
        assertEquals(cp, r.cp, "\"CP 1,667\" (coma/punto insertado por el OCR)")
    }

    @Test
    fun r2_dustDigitConfusion() {
        // fixDigits no se aplica al polvo: "5,OOO" se pierde (no rompe, pero quita la restricción de nivel).
        val s = idx["machamp"]!!; val iv = Ivs(15, 14, 12); val lv = 30.0
        val r = parser.parse(detail(s, lv, iv, "Fighting", "MACHOP CANDY", listOf("Counter"), dustText = { fmt(it).replace('0', 'O') }))
        assertEquals(5000, r.dust)
    }

    // ---------------------------------------------------------------- BarMeasure con filas sintéticas

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val WHITE = rgb(255, 255, 255)
    private val ORANGE = rgb(243, 164, 62)     // parcial (aprox.)
    private val RED = rgb(231, 98, 92)         // 15 (aprox.)
    private val TRACK = rgb(224, 224, 224)     // pista vacía (aprox.)

    /** Fila: margen de fondo + 3 segmentos de 198 px con separadores de 4 px + margen (+ zona opcional fuera de la tarjeta). */
    private fun barRow(iv: Int, track: Int = TRACK, bg: Int = WHITE, outside: Int? = null, noise: Int = 0, seed: Int = iv): IntArray {
        val seg = 198; val gap = 4; val pad = 40
        val colored = seg * 3
        val filled = (iv / 15.0 * colored).roundToInt()
        val fill = if (iv == 15) RED else ORANGE
        val out = mutableListOf<Int>()
        repeat(pad) { out += bg }
        for (p in 0 until colored) {
            if (p == seg || p == 2 * seg) repeat(gap) { out += bg }
            out += if (p < filled) fill else track
        }
        repeat(pad) { out += bg }
        if (outside != null) repeat(80) { out += outside }
        val rnd = kotlin.random.Random(seed)
        return out.map { c ->
            if (noise == 0) c else {
                fun ch(v: Int) = (v + rnd.nextInt(-noise, noise + 1)).coerceIn(0, 255)
                rgb(ch((c shr 16) and 0xFF), ch((c shr 8) and 0xFF), ch(c and 0xFF))
            }
        }.toIntArray()
    }

    private fun ivOf(row: IntArray, bg: Int = WHITE): Int {
        val f = com.esmeraldago.core.scan.BarMeasure.measure(row, bg).fraction
        return AppraisalBars(f, f, f).toIvs().atk
    }

    @Test
    fun bars_cleanColors_allValues() {
        val got = (0..15).map { ivOf(barRow(it)) }
        assertEquals((0..15).toList(), got)
    }

    @Test
    fun bars_jpegNoise() {
        // Capturas re-comprimidas (WhatsApp/Telegram): ruido ±8 por canal, también en el fondo muestreado.
        val got = (0..15).map { iv -> val row = barRow(iv, noise = 8); ivOf(row, bg = row[2]) }
        assertEquals((0..15).toList(), got)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Ronda 2: pendiente de calibrar con una captura real de la valoración (color real de la pista vacía)")
    fun bars_veryLightTrack() {
        // Si la pista vacía real fuera muy clara (≥ 242), queda dentro de la tolerancia de fondo (14) -> todo "15".
        val got = (0..15).map { ivOf(barRow(it, track = rgb(243, 243, 243))) }
        assertEquals((0..15).toList(), got)
    }

    @Test
    fun bars_rowRunsPastCardIntoDimmedBackground() {
        // xEnd = 0.92·ancho: si la tarjeta termina antes, el fondo oscurecido gris (v≈0.6) cuenta como pista vacía.
        val got = (0..15).map { ivOf(barRow(it, outside = rgb(150, 152, 150))) }
        assertEquals((0..15).toList(), got)
    }

    @Test
    fun appraisal_bars_perfectAndZeroAndHalf() {
        val s = idx["machamp"]!!; val iv = Ivs(15, 0, 8); val lv = 30.0
        val r = parser.parse(appraisalScreen(s, lv, iv), AppraisalBars(1f, 0f, 0.53f))
        assertEquals(ScreenType.APPRAISAL, r.screen)
        assertTrue(r.ivExact && r.candidates.single().ivs == iv, "${r.candidates}")
    }
}
