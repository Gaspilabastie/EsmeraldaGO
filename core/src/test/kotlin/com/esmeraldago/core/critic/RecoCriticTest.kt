package com.esmeraldago.core.critic

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.calc.PowerUpCost
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.calc.PveTarget
import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.model.Types
import com.esmeraldago.core.reco.Advice
import com.esmeraldago.core.reco.AdviceKind
import com.esmeraldago.core.reco.Recommender
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Auditoría de calidad de las recomendaciones (Recommender). Imprime escenarios realistas y
 * contiene asserts sobre bugs claros. Los tests que fallan documentan bugs reales.
 */
class RecoCriticTest {
    companion object {
        val idx: GameIndex by lazy {
            val f = listOf("../app/src/main/assets/gamedata.json", "app/src/main/assets/gamedata.json").map(::File).first { it.exists() }
            GameIndex(GameDataSources.decode(f.readText()))
        }
        private var n = 0

        fun mk(
            id: String, level: Double, a: Int, d: Int, s: Int,
            fast: String? = null, c1: String? = null, c2: String? = null,
            shadow: Boolean = false, purified: Boolean = false, lucky: Boolean = false,
            dmax: Boolean = false, gmax: Boolean = false, ivExact: Boolean = true,
        ): OwnedPokemon? {
            val sp = idx[id] ?: run { println("!! especie inexistente: $id"); return null }
            for (m in listOfNotNull(fast, c1, c2)) {
                if (idx.move(m) == null) println("!! movimiento inexistente: $m")
                else if (m !in sp.fast && m !in sp.charged) println("!! $id no aprende $m")
            }
            return OwnedPokemon(
                "u${++n}-$id${if (shadow) "-sh" else ""}", id, PokeMath.cp(sp, level, Ivs(a, d, s), idx), null, level,
                a, d, s, ivExact, fast, c1, c2, shadow, purified, lucky, dmax, gmax,
            )
        }

        /** Jugador casual: ~55 Pokémon, niveles 10-30, sin XL, movimientos a veces desconocidos. */
        val casual: List<OwnedPokemon> by lazy {
            listOfNotNull(
                mk("machamp", 25.0, 10, 12, 11, "COUNTER", "CROSS_CHOP"),
                mk("machamp", 20.0, 0, 2, 1),                             // basura con movimientos desconocidos
                mk("machop", 12.0, 14, 13, 15),
                mk("dragonite", 30.0, 10, 12, 11, "DRAGON_TAIL", "OUTRAGE"),
                mk("gyarados", 28.0, 8, 9, 10, "BITE", "CRUNCH"),
                mk("snorlax", 26.0, 12, 10, 9),
                mk("blissey", 25.0, 5, 6, 7),
                mk("rhydon", 22.0, 13, 11, 12, "MUD_SLAP", "EARTHQUAKE"),
                mk("rhyhorn", 20.0, 15, 15, 15),
                mk("tyranitar", 20.0, 12, 10, 14, "BITE", "CRUNCH"),
                mk("metagross", 20.0, 11, 13, 12),
                mk("garchomp", 20.0, 13, 14, 10, "MUD_SHOT", "EARTHQUAKE"),
                mk("excadrill", 27.0, 9, 9, 9, "MUD_SLAP", "DRILL_RUN"),
                mk("mamoswine", 25.0, 12, 12, 12),
                mk("lucario", 25.0, 14, 11, 13, "COUNTER", "AURA_SPHERE"),
                mk("gengar", 24.0, 9, 13, 12, "HEX", "SLUDGE_BOMB"),
                mk("alakazam", 22.0, 6, 14, 8),
                mk("gardevoir", 25.0, 11, 11, 11, "CHARM", "DAZZLING_GLEAM"),
                mk("togekiss", 23.0, 12, 13, 14),
                mk("salamence", 20.0, 10, 10, 15),
                mk("rayquaza", 20.0, 12, 11, 13, "DRAGON_TAIL", "OUTRAGE"),
                mk("groudon", 20.0, 10, 14, 15),
                mk("kyogre", 20.0, 13, 13, 12),
                mk("mewtwo", 20.0, 12, 14, 11, "CONFUSION", "PSYCHIC"),
                mk("dialga", 20.0, 11, 10, 12),
                mk("giratina_altered", 20.0, 10, 15, 13),
                mk("registeel", 20.0, 13, 12, 14, "LOCK_ON", "FLASH_CANNON"),
                mk("azumarill", 22.0, 5, 14, 13, "BUBBLE", "PLAY_ROUGH", "ICE_BEAM"),
                mk("azumarill", 40.0, 15, 15, 15),                       // ya superó el tope de Liga Super
                mk("marill", 15.0, 1, 15, 14),
                mk("medicham", 30.0, 8, 12, 10, "COUNTER", "ICE_PUNCH"),
                mk("meditite", 10.0, 0, 14, 15),
                mk("venusaur", 25.0, 12, 11, 10),
                mk("charizard", 30.0, 13, 12, 11, "FIRE_SPIN", "BLAST_BURN"),
                mk("lapras", 22.0, 9, 9, 9),
                mk("vaporeon", 25.0, 14, 10, 8),
                mk("jolteon", 18.0, 7, 7, 7),
                mk("eevee", 15.0, 1, 15, 15),
                mk("eevee", 10.0, 15, 0, 0),
                mk("pikachu", 15.0, 10, 10, 10),
                mk("pidgeot", 20.0, 3, 5, 9),
                mk("golbat", 18.0, 2, 14, 15),
                mk("swampert", 25.0, 4, 13, 14, "MUD_SHOT", "EARTHQUAKE", "SLUDGE_WAVE"),
                mk("skarmory", 15.0, 1, 14, 13),
                mk("altaria", 20.0, 2, 15, 14, "DRAGON_BREATH", "SKY_ATTACK"),
                mk("trevenant", 18.0, 3, 15, 12),
                mk("lickitung", 12.0, 0, 15, 15),
                mk("stunfisk_galarian", 18.0, 1, 15, 15),
                mk("sableye", 20.0, 5, 13, 14),
                mk("dewgong", 16.0, 2, 12, 15),
                mk("magnezone", 22.0, 11, 9, 13),
                mk("roserade", 22.0, 12, 8, 10),
                mk("chandelure", 20.0, 13, 10, 11),
                mk("conkeldurr", 20.0, 11, 12, 10),
                mk("zapdos", 20.0, 11, 13, 12),
                mk("articuno", 20.0, 8, 12, 14),
            )
        }

        /** Jugador avanzado: shadows nivel 40-50, hundos, XL, megas, Dinamax. */
        val advanced: List<OwnedPokemon> by lazy {
            listOfNotNull(
                mk("machamp", 40.0, 15, 14, 13, "COUNTER", "DYNAMIC_PUNCH", shadow = true),
                mk("machamp", 40.0, 15, 15, 12, "COUNTER", "DYNAMIC_PUNCH", shadow = true),
                mk("machamp", 40.0, 14, 13, 15, "COUNTER", "DYNAMIC_PUNCH", shadow = true),
                mk("mamoswine", 40.0, 15, 13, 14, "POWDER_SNOW", "AVALANCHE", shadow = true),
                mk("metagross", 50.0, 15, 15, 15, "BULLET_PUNCH", "METEOR_MASH", shadow = true),
                mk("dragonite", 40.0, 15, 12, 14, "DRAGON_TAIL", "OUTRAGE", shadow = true),
                mk("garchomp", 45.0, 15, 15, 15, "MUD_SHOT", "EARTH_POWER", shadow = true),
                mk("rhyperior", 40.0, 15, 14, 14, "MUD_SLAP", "ROCK_WRECKER", shadow = true),
                mk("gengar", 40.0, 15, 13, 14, "LICK", "SHADOW_BALL"),
                mk("charizard", 40.0, 14, 15, 15, "FIRE_SPIN", "BLAST_BURN"),
                mk("lucario", 40.0, 15, 15, 14, "FORCE_PALM", "AURA_SPHERE"),
                mk("garchomp", 40.0, 15, 14, 15, "DRAGON_TAIL", "OUTRAGE"),
                mk("rayquaza", 50.0, 15, 15, 15, "DRAGON_TAIL", "BREAKING_SWIPE"),
                mk("kyogre", 50.0, 15, 14, 15, "WATERFALL", "ORIGIN_PULSE"),
                mk("groudon", 50.0, 15, 15, 14, "MUD_SHOT", "PRECIPICE_BLADES"),
                mk("mewtwo", 40.0, 15, 15, 15, "PSYCHO_CUT", "PSYSTRIKE", purified = true),
                mk("mewtwo", 25.0, 13, 14, 12, shadow = true),
                mk("dialga", 50.0, 15, 15, 15),
                mk("zacian_crowned_sword", 50.0, 15, 15, 15),
                mk("medicham", 50.0, 0, 15, 15, "COUNTER", "ICE_PUNCH", "PSYCHIC"),
                mk("azumarill", 50.0, 8, 15, 15, "BUBBLE", "PLAY_ROUGH", "ICE_BEAM"),
                mk("swampert", 30.0, 1, 15, 15, "MUD_SHOT", "HYDRO_CANNON", "EARTHQUAKE", shadow = true),
                mk("registeel", 30.0, 0, 15, 13, "LOCK_ON", "FOCUS_BLAST", "ZAP_CANNON"),
                mk("stunfisk_galarian", 40.0, 0, 15, 15, "MUD_SHOT", "ROCK_SLIDE", "EARTHQUAKE"),
                mk("lickitung", 50.0, 0, 15, 15, "LICK", "BODY_SLAM", "POWER_WHIP"),
                mk("dewgong", 50.0, 1, 15, 15),
                mk("machamp", 30.0, 12, 12, 12, "COUNTER", dmax = true),
                mk("charizard", 40.0, 15, 15, 15, "FIRE_SPIN", gmax = true),
                mk("blissey", 40.0, 10, 14, 15, dmax = true),
                mk("metagross", 40.0, 15, 15, 14, "BULLET_PUNCH", dmax = true),
                mk("excadrill", 40.0, 15, 15, 15, "MUD_SHOT", dmax = true),
                mk("gengar", 40.0, 14, 14, 15, "LICK", gmax = true),
                mk("snorlax", 40.0, 13, 15, 15, "LICK", gmax = true),
            ) + casual.take(25)
        }

        fun costStr(c: PowerUpCost?) = c?.let { "polvo=${it.stardust} car=${it.candy} XL=${it.xlCandy}" } ?: "-"

        fun printAdvice(tag: String, list: List<Advice>, max: Int = 200) {
            println("== $tag: ${list.size} consejos ==")
            list.take(max).forEach {
                println("  [%-12s] p=%5.1f %s | %s | %s | uid=%s".format(
                    it.kind.label, it.priority, it.title, it.detail.replace('\n', ' '), costStr(it.cost), it.owned?.uid))
            }
            val dust = list.sumOf { it.cost?.stardust ?: 0 }
            val xl = list.sumOf { it.cost?.xlCandy ?: 0 }
            println("  TOTAL polvo=%,d  XL=%d  (#con XL=%d, #'ya está listo'=%d)".format(
                dust, xl, list.count { (it.cost?.xlCandy ?: 0) > 0 }, list.count { "ya está listo" in it.detail.lowercase() }))
        }

        private val levelRe = Regex("nivel (\\d+(?:\\.\\d)?)")
        fun targetLevels(a: Advice): Set<Double> =
            if (a.kind == AdviceKind.MAX) emptySet()
            // (Ronda 2) las alternativas van después de "También sirve para:" y no son instrucciones.
            else levelRe.findAll(a.title + " " + a.detail.substringBefore("\nTambién sirve para").substringBefore("\nO bien"))
                .map { it.groupValues[1].toDouble() }.toSet()
    }

    // ------------------------------------------------------------------ Reportes generales

    @Test
    fun casualPowerUp() {
        val r = Recommender(idx)
        val adv = r.powerUpAdvice(casual)
        printAdvice("CASUAL powerUpAdvice", adv)
        val top5Xl = adv.take(5).filter { (it.cost?.xlCandy ?: 0) >= 100 }
        assertTrue(top5Xl.isEmpty(), "Jugador casual sin XL: ${top5Xl.size} de los 5 primeros consejos exigen ≥100 XL: ${top5Xl.map { it.title + " " + costStr(it.cost) }}")
    }

    /** El mismo Pokémon (moves desconocidos) aparece N veces, una por tipo, cada una con un TM distinto. */
    @Test
    fun pveAdviceRepeatedPerType() {
        val r = Recommender(idx)
        val m = listOf(mk("mewtwo", 25.0, 13, 14, 12, shadow = true)!!)
        val pve = r.powerUpAdvice(m).filter { it.kind == AdviceKind.PVE }
        pve.forEach { println("  ${it.title} | ${it.detail}") }
        assertTrue(pve.size <= 1, "Mewtwo shadow con movimientos desconocidos recibe ${pve.size} consejos 'subí a 40' (uno por tipo, cada uno asumiendo otro ataque cargado)")
    }

    /** Metagross con Meteor Mash (élite): el toMap() le asigna el puesto de su moveset sin élite (#32) y queda fuera del corte 25. */
    @Test
    fun metagrossMeteorMashGetsNoSteelAdvice() {
        val r = Recommender(idx)
        val mg = listOf(mk("metagross", 20.0, 15, 15, 15, "BULLET_PUNCH", "METEOR_MASH")!!)
        val adv = r.powerUpAdvice(mg).filter { it.kind == AdviceKind.PVE }
        adv.forEach { println("  ${it.title} | ${it.detail}") }
        assertTrue(adv.any { "Acero" in it.detail }, "Metagross hundo con Meteor Mash no recibe consejo de atacante Acero")
    }

    @Test
    fun advancedPowerUp() {
        val r = Recommender(idx)
        printAdvice("AVANZADO powerUpAdvice", r.powerUpAdvice(advanced))
    }

    @Test
    fun pvpTeams() {
        val r = Recommender(idx)
        for ((name, col) in listOf("CASUAL" to casual, "AVANZADO" to advanced)) for (lg in League.entries) {
            val team = r.pvpTeam(lg, col)
            println("== $name ${lg.label} equipo ==")
            team.forEach { c ->
                val known = (listOfNotNull(c.owned.fast) + c.owned.chargedMoves)
                val missing = c.moveset.filter { known.isNotEmpty() && it !in known }
                val needsEtm = c.moveset.filter { c.species.isElite(it) }
                println("  %-28s tipos=%-18s meta#%d iv#%d (%.1f%%) L%s CP%d evo=%s costo[%s] moveset=%s faltan=%s élite/legado=%s".format(
                    c.species.name, c.species.types, c.metaRank, c.iv.rank, c.iv.percentOfBest, c.iv.level, c.iv.cp,
                    c.needsEvolve, costStr(c.cost), c.moveset, missing, needsEtm))
            }
            val types = team.flatMap { it.species.types }
            val dupType = types.groupingBy { it }.eachCount().filter { it.value >= 2 }
            val sharedWeak = team.flatMap { Types.weaknesses(it.species.types) }.groupingBy { it }.eachCount().filter { it.value >= 2 }
            println("  tipos repetidos=$dupType  debilidades compartidas=$sharedWeak")
        }
    }

    @Test
    fun raidTeams() {
        val r = Recommender(idx)
        val bosses = listOf("tyranitar" to RaidTier.T3, "rayquaza" to RaidTier.T5, "kyogre" to RaidTier.T5, "dialga" to RaidTier.T5, "mewtwo" to RaidTier.T5, "zekrom" to RaidTier.T5)
        for ((name, col) in listOf("CASUAL" to casual, "AVANZADO" to advanced)) for ((b, tier) in bosses) {
            val boss = idx[b] ?: continue
            val team = r.raidTeam(boss, tier, col, size = 6)
            println("== $name raid vs ${boss.name} ==")
            team.forEach { o ->
                val assumed = o.owned.fast == null || o.owned.chargedMoves.isEmpty()
                println("  %-26s%s L%s IV %d/%d/%d %s/%s score=%.1f%s".format(
                    o.result.species.name, if (o.asMega) " [MEGA]" else "", o.owned.level, o.owned.ivAtk, o.owned.ivDef, o.owned.ivSta,
                    o.result.fast.name, o.result.charged.name, o.result.score,
                    if (assumed) "  <-- movimientos ASUMIDOS (desconocidos)" else ""))
            }
            assertTrue(team.count { it.asMega } <= 1, "más de un Mega")
            assertTrue(team.all { it.result.species.released }, "eligió especie no liberada: ${team.filter { !it.result.species.released }.map { it.result.species.id }}")
        }
    }

    @Test
    fun catchReport() {
        val r = Recommender(idx)
        for ((name, col) in listOf("CASUAL" to casual, "AVANZADO" to advanced)) {
            val list = r.catchAdvice(col)
            println("== $name catchAdvice: ${list.size} ==")
            list.take(60).forEach { a ->
                val s = a.species
                val flags = listOfNotNull(
                    "LEGENDARIO".takeIf { s.legendary }, "MÍTICO".takeIf { s.mythical }, "UB".takeIf { s.ultraBeast },
                    "SHADOW".takeIf { s.shadow }, "MEGA".takeIf { s.mega }, "NO-LIBERADO".takeIf { !s.released },
                )
                println("  p=%5.1f %-30s %s | %s".format(a.priority, s.name + " (${s.id})", flags, a.detail.replace('\n', ' ')))
            }
            val top = list.take(60)
            println("  legendarios/míticos/UB en top60: ${top.count { it.species.legendary || it.species.mythical || it.species.ultraBeast }}")
            println("  shadows en top60: ${top.count { it.species.shadow }}")
            println("  títulos duplicados: ${top.groupingBy { it.title }.eachCount().filter { it.value > 1 }}")
        }
    }

    @Test
    fun maxReport() {
        val r = Recommender(idx)
        for (b in listOf("charizard", "machamp", "zacian_hero")) {
            val boss = idx[b] ?: continue
            val (att, tanks) = r.maxOptions(advanced, boss)
            println("== Dinamax vs ${boss.name} ==")
            println("  atacantes: " + att.map { "${it.result.displayName}(${it.owned.uid}) ${it.result.fast.name}->${it.result.maxMoveName} dmg=${it.result.maxDamage.toInt()}" })
            println("  tanques:   " + tanks.map { "${it.result.displayName}(${it.owned.uid}) bulk/mult=${(it.result.bulk / it.result.damageTakenMult).toInt()}" })
            val both = att.take(3).map { it.owned.uid }.intersect(tanks.take(3).map { it.owned.uid }.toSet())
            println("  mismos Pokémon en top3 atacantes y top3 tanques: $both")
        }
    }

    // ------------------------------------------------------------------ Bugs con asserts

    /** Un Pokémon ya por encima del tope de PC no debe aparecer (comportamiento correcto esperado). */
    @Test
    fun overCapIsExcluded() {
        val r = Recommender(idx)
        val azu40 = casual.first { it.speciesId == "azumarill" && it.level == 40.0 }
        println("Azumarill L40 15/15/15 CP=${azu40.cp}")
        val cands = r.pvpCandidates(League.GREAT, listOf(azu40))
        println("candidatos GL: ${cands.map { it.species.id }}")
        assertTrue(cands.isEmpty(), "Azumarill CP ${azu40.cp} > 1500 no puede jugar Liga Super")
    }

    /** PvP: el moveset de PvPoke requiere Hydro Cannon (élite) que el usuario no tiene, y nada lo penaliza ni avisa. */
    @Test
    fun pvpEliteMoveNotFlagged() {
        val r = Recommender(idx)
        val sw = listOfNotNull(
            mk("swampert", 25.0, 2, 15, 13, "MUD_SHOT", "EARTHQUAKE", "SLUDGE_WAVE"),          // sin Hydro Cannon
            mk("swampert", 25.0, 2, 15, 13, "MUD_SHOT", "HYDRO_CANNON", "EARTHQUAKE"),         // con Hydro Cannon
        )
        val cands = r.pvpCandidates(League.ULTRA, sw)
        cands.forEach { println("  ${it.owned.uid} ${it.species.id} value=%.2f moveset=${it.moveset} conocidos=${it.owned.chargedMoves}".format(it.value)) }
        val adv = r.powerUpAdvice(sw).filter { it.kind == AdviceKind.PVP }
        adv.forEach { println("  consejo ${it.owned?.uid}: ${it.title} | ${it.detail}") }
        val without = cands.first { "HYDRO_CANNON" !in it.owned.chargedMoves }
        val with = cands.first { "HYDRO_CANNON" in it.owned.chargedMoves }
        assertTrue(without.value < with.value, "Swampert SIN Hydro Cannon vale lo mismo (%.2f) que CON Hydro Cannon (%.2f); el consejo no menciona MT Élite".format(without.value, with.value))
    }

    /** Movimientos desconocidos: se asume el mejor moveset y supera a uno real con mismos IV/nivel. */
    @Test
    fun unknownMovesOutrankKnown() {
        val r = Recommender(idx)
        val known = mk("machamp", 30.0, 12, 12, 12, "KARATE_CHOP", "HEAVY_SLAM")!!
        val unknown = mk("machamp", 30.0, 12, 12, 12)!!
        val team = r.raidTeam(idx["tyranitar"]!!, RaidTier.T3, listOf(known, unknown))
        team.forEach { println("  ${it.owned.uid} ${it.result.fast.name}/${it.result.charged.name} score=%.1f".format(it.result.score)) }
        val sUnknown = team.first { it.owned.uid == unknown.uid }.result.score
        val sKnown = team.first { it.owned.uid == known.uid }.result.score
        println("  diferencia: %.1f%%".format((sUnknown / sKnown - 1) * 100))
        // (Ronda 2) se acepta que el desconocido puntúe más si se asume un moveset NORMAL (sin MT Élite) y se marca.
        val u = team.first { it.owned.uid == unknown.uid }
        assertTrue(u.assumedMoves, "El Machamp sin ataques debe venir marcado como 'ataques asumidos'")
        assertTrue(!u.result.usesElite, "No debe asumirse un moveset de MT Élite: ${u.result.fast.id}/${u.result.charged.id}")
    }

    /** rankOf usa toMap() -> si una especie aparece 2 veces (con y sin legado) gana el ÚLTIMO puesto. */
    @Test
    fun pveRankUsesLastDuplicate() {
        val r = Recommender(idx)
        var found = 0
        for (type in Types.ALL) {
            val top = r.topAttackers(type)
            val firstIdx = LinkedHashMap<String, Int>()
            top.forEachIndexed { i, x -> firstIdx.putIfAbsent(x.species.id, i + 1) }
            val lastIdx = top.mapIndexed { i, x -> x.species.id to i + 1 }.toMap()
            // (Ronda 2) el consejo ya no usa el índice de la lista sino rankAmong(score): se valida eso.
            // Empates exactos (ej. las 4 formas de Genesect) comparten puesto.
            fun tieRank(f: Int) = top.indexOfFirst { kotlin.math.abs(it.score - top[f - 1].score) < 1e-9 } + 1
            firstIdx.filter { (_, f) -> r.rankAmong(type, top[f - 1].score) != tieRank(f) && f <= 25 }.forEach { (id, f) ->
                found++
                println("  $type: $id primer puesto #$f (moveset ${top[f - 1].fast.id}/${top[f - 1].charged.id}) pero rankOf=#${lastIdx[id]}${if (lastIdx[id]!! > 25) " -> QUEDA FUERA del corte 25" else ""}")
            }
        }
        // Mewtwo purificado con Psystrike: el consejo dice "Top #N" usando el peor puesto.
        val m2 = listOf(mk("mewtwo", 30.0, 15, 15, 15, "PSYCHO_CUT", "PSYSTRIKE")!!)
        r.powerUpAdvice(m2).filter { it.kind == AdviceKind.PVE }.forEach { println("  consejo: ${it.title} | ${it.detail}") }
        assertTrue(found == 0, "$found especies muestran el puesto de su moveset alternativo (sin legado) en vez del mejor")
    }

    /** El mismo Pokémon recibe consejos incompatibles (dos evoluciones distintas, o tope de liga < otro objetivo). */
    @Test
    fun conflictingTargets() {
        val r = Recommender(idx)
        var total = 0
        val extra = listOfNotNull(mk("tyranitar", 18.0, 1, 15, 14, "SMACK_DOWN", "CRUNCH"), mk("dragonite", 15.0, 2, 14, 15), mk("eevee", 12.0, 2, 15, 14))
        for ((name, col) in listOf("CASUAL" to casual, "AVANZADO" to advanced, "EXTRA" to extra)) {
            val adv = r.powerUpAdvice(col)
            val byUid = adv.filter { it.owned != null }.groupBy { it.owned!!.uid }
            val conflicts = byUid.filter { (_, l) ->
                val evos = l.filter { it.kind == AdviceKind.EVOLVE || it.kind == AdviceKind.PVP }.map { it.species.id }.toSet()
                val lv = l.flatMap { targetLevels(it) }.toSet()
                evos.size > 1 || (lv.size > 1 && lv.any { it < 40 } && l.any { it.kind != AdviceKind.PVP || "Master" !in it.title })
            }
            total += conflicts.size
            println("== $name: Pokémon con >1 consejo: ${byUid.count { it.value.size > 1 }}; incompatibles: ${conflicts.size}")
            byUid.filter { it.value.size > 1 }.forEach { (uid, l) ->
                println("  ${if (uid in conflicts) "CONFLICTO" else "repetido "} $uid -> " + l.map { "${it.title} [${targetLevels(it)}]" })
            }
        }
        assertTrue(total == 0, "$total Pokémon con consejos contradictorios (ej. un Eevee -> Umbreon GL y Sylveon UL)")
    }

    /** Liga Master: consejos para subir a nivel 50 (cientos de XL) Pokémon con IV mediocres. */
    @Test
    fun masterLeagueMediocre() {
        val r = Recommender(idx)
        val mediocre = listOfNotNull(
            mk("dragonite", 30.0, 10, 12, 11, "DRAGON_TAIL", "OUTRAGE"),
            mk("dialga", 20.0, 11, 10, 12),
            mk("groudon", 20.0, 12, 11, 10),
            mk("garchomp", 25.0, 12, 13, 11),
        )
        for (p in mediocre) {
            val s = idx[p.speciesId]!!
            val rk = PokeMath.pvpRankOf(s, Ivs(p.ivAtk, p.ivDef, p.ivSta), 10000, 50.0, idx)
            println("  ${p.speciesId} ${p.ivAtk}/${p.ivDef}/${p.ivSta}: ML IV rank #${rk?.rank} (${"%.1f".format(rk?.percentOfBest)}%)")
        }
        val adv = r.powerUpAdvice(mediocre).filter { "Master" in it.title }
        printAdvice("Liga Master con IV mediocres", adv)
        val bad = adv.filter { (it.owned?.ivPercent ?: 100) < 90 }
        assertTrue(bad.isEmpty(), "Recomienda llevar a nivel 50 para Liga Master Pokémon <90% IV: ${bad.map { "${it.title} ${costStr(it.cost)}" }}")
    }

    /** Medicham / Azumarill: el rank 1 necesita nivel 50 (XL). Jugador sin XL no tiene alternativa. */
    @Test
    fun medichamXlCase() {
        for (maxL in listOf(40.0, 50.0, 51.0)) {
            val t = PokeMath.pvpTable(idx["medicham"]!!, 1500, maxL, idx)
            val mine = t.first { it.ivs == Ivs(8, 12, 10) }
            println("Medicham GL maxLevel=$maxL: #1=${t.first().ivs} L${t.first().level} CP${t.first().cp}; 8/12/10 -> #${mine.rank} L${mine.level} (${"%.1f".format(mine.percentOfBest)}%)")
        }
        val r = Recommender(idx)
        val med = casual.filter { it.speciesId == "medicham" || it.speciesId == "meditite" || it.speciesId == "marill" }
        r.pvpCandidates(League.GREAT, med).forEach {
            println("  ${it.owned.uid} -> ${it.species.id} iv#${it.iv.rank} L${it.iv.level} CP${it.iv.cp} costo[${costStr(it.cost)}]")
        }
        val adv = r.powerUpAdvice(med)
        printAdvice("Medicham/Marill casual (sin XL)", adv)
        // Con tope 40 (sin XL) el mismo Medicham cae de #141 a #783: la evaluación asume XL siempre.
        val r40 = Recommender(idx, 40.0)
        r40.pvpCandidates(League.GREAT, med).forEach { println("  [maxLevel 40] ${it.owned.uid} -> ${it.species.id} iv#${it.iv.rank} (${"%.1f".format(it.iv.percentOfBest)}%) L${it.iv.level} CP${it.iv.cp} costo[${costStr(it.cost)}]") }
    }

    /** Equipo PvP de puro agua/hada: ¿evita repetir tipos? */
    @Test
    fun pvpTeamTypeStacking() {
        val r = Recommender(idx)
        val col = listOfNotNull(
            mk("azumarill", 25.0, 4, 15, 15, "BUBBLE", "PLAY_ROUGH", "ICE_BEAM"),
            mk("swampert", 18.0, 2, 15, 13, "MUD_SHOT", "HYDRO_CANNON", "EARTHQUAKE"),
            mk("lanturn", 18.0, 1, 14, 15),
            mk("whiscash", 18.0, 0, 15, 14),
            mk("quagsire", 18.0, 2, 15, 15),
            mk("registeel", 18.0, 1, 15, 14, "LOCK_ON", "FOCUS_BLAST", "ZAP_CANNON"),
            mk("trevenant", 15.0, 3, 15, 12),
        )
        val team = r.pvpTeam(League.GREAT, col)
        println("Equipo: " + team.map { "${it.species.id} ${it.species.types} value=%.1f".format(it.value) })
        r.pvpCandidates(League.GREAT, col).forEach { println("  cand ${it.species.id} meta#${it.metaRank} value=%.1f".format(it.value)) }
        val sameTyping = team.groupBy { it.species.types.sorted() }.filter { it.value.size > 1 }
        assertTrue(sameTyping.isEmpty(), "Equipo con tipado idéntico repetido (doble debilidad x2.56 a Planta compartida): ${sameTyping.mapValues { v -> v.value.map { it.species.id } }}")
    }

    /** Catch: recomienda atrapar Pokémon que solo existen por fusión/cambio de forma o no se pueden atrapar. */
    @Test
    fun catchNonCatchable() {
        val r = Recommender(idx)
        val list = r.catchAdvice(advanced)
        val formIds = listOf("_crowned", "necrozma_", "kyurem_", "_eternamax", "_armored", "_origin", "_therian", "_primal", "_complete")
        val odd = list.filter { a -> formIds.any { it in a.species.id } || a.species.mythical }
        odd.forEach { println("  ${a(it)}") }
        val noWhere = list.take(40).count { a -> listOf("incursiones", "Rocket", "Max", "evoluc", "huevo", "salvaje").none { it in a.detail } }
        println("  de top40, sin ninguna pista de dónde conseguirlo: $noWhere")
        assertTrue(odd.none { "_crowned" in it.species.id || "necrozma_" in it.species.id || "kyurem_" in it.species.id },
            "Recomienda 'atrapar' formas que solo se obtienen por fusión/objeto: ${odd.map { it.species.id }}")
    }
    private fun a(x: Advice) = "${x.species.id} leg=${x.species.legendary} myth=${x.species.mythical} | ${x.detail.replace('\n', ' ')}"

    /** Catch: tener un Rhyhorn 15/15/15 no cuenta; sugiere atrapar Rhyperior en vez de evolucionar. */
    @Test
    fun catchIgnoresPreEvolutions() {
        val r = Recommender(idx)
        val col = listOfNotNull(mk("rhyhorn", 20.0, 15, 15, 15), mk("machop", 20.0, 15, 15, 15), mk("beldum", 20.0, 15, 15, 15))
        val list = r.catchAdvice(col)
        val evolvable = list.filter { a ->
            a.kind == AdviceKind.CATCH && a.detail.startsWith("Atacante") &&
                col.any { p -> idx[p.speciesId]?.family == a.species.family && a.species.family != null && !a.species.shadow && !a.species.mega }
        }
        evolvable.forEach { println("  ${it.species.id}: ${it.detail.replace('\n', ' ')}") }
        assertTrue(evolvable.isEmpty(), "Sugiere ATRAPAR ${evolvable.map { it.species.id }} aunque ya tiene la pre-evolución 15/15/15 (debería decir 'evolucioná')")
    }

    /** Catch: tener un ejemplar basura (0/2/1) basta para que no lo recomiende para incursiones. */
    @Test
    fun catchOwnedTrashCounts() {
        val r = Recommender(idx)
        val trash = listOfNotNull(mk("machamp", 10.0, 0, 2, 1), mk("rhyperior", 10.0, 0, 1, 0), mk("mamoswine", 10.0, 1, 0, 0))
        val withTrash = r.catchAdvice(trash).map { it.species.id }.toSet()
        val empty = r.catchAdvice(emptyList()).map { it.species.id }.toSet()
        val lost = listOf("machamp", "rhyperior", "mamoswine").filter { it in empty && it !in withTrash }
        println("  dejan de recomendarse por tener un ejemplar basura nivel 10: $lost")
    }

    /** Mega no liberada / megasOf sin filtrar released. */
    @Test
    fun megasReleased() {
        val megas = idx.data.species.filter { it.mega }
        println("Megas totales=${megas.size}; no liberadas=${megas.filter { !it.released }.map { it.id }}")
        val unreleasedInRaid = mutableListOf<String>()
        val r = Recommender(idx)
        for (p in (casual + advanced).distinctBy { it.speciesId }) {
            val s = r.battleSpecies(p) ?: continue
            idx.megasOf(s).filter { !it.released }.forEach { unreleasedInRaid += it.id }
        }
        println("Megas NO liberadas que raidTeam evaluaría: $unreleasedInRaid")
        assertTrue(unreleasedInRaid.isEmpty(), "raidTeam considera megas no liberadas (GameIndex.megasOf no filtra released): $unreleasedInRaid")
    }

    /** Pokémon con IV estimados (ivExact=false) se tratan como exactos en PvP. */
    @Test
    fun estimatedIvsTreatedAsExact() {
        val r = Recommender(idx)
        // (Ronda 2) Marill 1/15/14 -> Azumarill rank ~12: sí genera consejo PvP.
        val p = mk("marill", 15.0, 1, 15, 14, ivExact = false)!!
        val adv = r.powerUpAdvice(listOf(p)).filter { it.kind == AdviceKind.PVP || it.kind == AdviceKind.EVOLVE }
        adv.forEach { println("  ${it.title} | ${it.detail}") }
        println("  menciona estimación: ${adv.any { "estim" in it.detail.lowercase() }}")
        assertTrue(adv.isNotEmpty() && adv.all { "estim" in it.detail.lowercase() }, "Consejo PvP con IV estimados sin avisarlo")
    }

    /** (Ronda 2) Un movimiento de LEGADO faltante también requiere MT Élite; isElite() excluye legado. */
    @Test
    fun missingLegacyMoveCountsAsEliteTm() {
        val r = Recommender(idx)
        val bad = mutableListOf<String>()
        for (lg in listOf(League.GREAT, League.ULTRA)) for (rank in idx.pvpRanking(lg).take(150)) {
            val s = idx[rank.id] ?: continue
            val legacy = rank.moveset.filter { s.isElite(it) }
            if (legacy.isEmpty() || s.shadow) continue
            val fast = rank.moveset.first()
            val charged = rank.moveset.drop(1).filter { it !in legacy } + s.charged.filter { it !in rank.moveset && !s.isElite(it) }.take(1)
            val p = OwnedPokemon("leg-${s.id}", s.id, 10, null, 1.0, 0, 15, 15, true,
                if (fast in legacy) s.fast.firstOrNull { it != fast } else fast, charged.getOrNull(0), charged.getOrNull(1))
            val c = r.pvpCandidates(lg, listOf(p)).firstOrNull { it.species.id == s.id } ?: continue
            if (c.missingMoves.any { it in legacy } && !c.needsEliteTm) bad += "${lg.key}:${s.id} falta ${legacy} (legado) -> needsEliteTm=false"
        }
        bad.forEach { println("  $it") }
        assertTrue(bad.isEmpty(), "${bad.size} casos donde falta un movimiento de LEGADO y no se marca 'Requiere MT Élite' (se dice 'Enseñale')")
    }

    /** (Ronda 2) G-Max/Dinamax no se obtiene evolucionando un ejemplar común. */
    @Test
    fun gmaxReasonNotSatisfiedByEvolving() {
        val r = Recommender(idx)
        val list = r.catchAdvice(listOfNotNull(mk("machop", 20.0, 15, 15, 15)))
        val wrong = list.filter { it.kind == AdviceKind.EVOLVE && it.detail.startsWith("Dinamax") }
        wrong.forEach { println("  ${it.species.id}: ${it.detail.replace('\n', ' ')}") }
        assertTrue(wrong.isEmpty(), "Dice 'evolucionalo' para una razón exclusivamente Dinamax/G-Max: ${wrong.map { it.species.id }}")
    }

    /** (Ronda 2) Las formas mapeadas a la base pierden el texto de cómo se obtienen (formOrigin se evalúa sobre la base). */
    @Test
    fun formOriginTextShown() {
        val r = Recommender(idx)
        val list = r.catchAdvice(emptyList())
        val ky = list.firstOrNull { it.species.id == "kyurem" }
        val za = list.firstOrNull { it.species.baseId.startsWith("zacian") }
        println("  kyurem: ${ky?.detail?.replace('\n', ' ')}")
        println("  zacian: ${za?.species?.id} ${za?.detail?.replace('\n', ' ')}")
        println("  dobles puntos '..': ${list.count { ".." in it.detail }} de ${list.size}")
        assertTrue(list.none { "fusionando" in it.detail || "objeto" in it.detail || "Cambio de forma" in it.detail }.not(),
            "Ningún consejo muestra 'cómo se obtiene' para formas por fusión/objeto: formOrigin(base) siempre es null")
    }

    /** (Ronda 2) Rendimiento en caliente de pvpTeam tras catchAdvice (memo de 24 entradas + LRU 400 tablas). */
    @Test
    fun pvpTeamAfterCatchPerf() {
        val col = bigCollection(500, seed = 7)
        val r = Recommender(idx)
        r.powerUpAdvice(col)
        var t = System.nanoTime(); League.entries.forEach { r.pvpTeam(it, col) }; val a = (System.nanoTime() - t) / 1e6
        r.catchAdvice(col)
        t = System.nanoTime(); League.entries.forEach { r.pvpTeam(it, col) }; val b = (System.nanoTime() - t) / 1e6
        t = System.nanoTime(); League.entries.forEach { r.pvpTeam(it, col) }; val c = (System.nanoTime() - t) / 1e6
        println("== pvpTeam x3 tras powerUp: %.0fms | tras catchAdvice: %.0fms | otra vez: %.0fms".format(a, b, c))
    }

    // ------------------------------------------------------------------ Rendimiento

    /** (Ronda 2) la caché vive en GameIndex: devuelve "entradas" equivalentes (tablas × 4096) y la vacía. */
    private fun clearPvpCache(): Int {
        val entries = idx.statProductCacheSize() * 4096
        idx.clearStatProductCache()
        return entries
    }

    private fun bigCollection(size: Int, seed: Int = 42): List<OwnedPokemon> {
        val rnd = Random(seed)
        val pool = idx.released.filter { !it.shadow && !it.mega }
        return List(size) {
            val s = pool[rnd.nextInt(pool.size)]
            val shadow = rnd.nextInt(6) == 0 && idx["${s.id}_shadow"] != null
            val lvl = (rnd.nextInt(10, 70)) / 2.0
            val iv = Ivs(rnd.nextInt(16), rnd.nextInt(16), rnd.nextInt(16))
            OwnedPokemon("big$it", s.id, PokeMath.cp(s, lvl, iv, idx), null, lvl, iv.atk, iv.def, iv.sta,
                shadow = shadow, dynamax = s.dynamax && rnd.nextInt(10) == 0)
        }
    }

    @Test
    fun performance() {
        idx.species.size // carga
        for (size in listOf(500, 1000)) {
            val col = bigCollection(size)
            clearPvpCache()
            val r = Recommender(idx)
            val rt = Runtime.getRuntime(); System.gc()
            val mem0 = rt.totalMemory() - rt.freeMemory()
            var t = System.nanoTime()
            val pu = r.powerUpAdvice(col)
            val tPu = (System.nanoTime() - t) / 1e6
            t = System.nanoTime(); r.powerUpAdvice(col); val tPu2 = (System.nanoTime() - t) / 1e6
            t = System.nanoTime(); val ca = r.catchAdvice(col); val tCa = (System.nanoTime() - t) / 1e6
            t = System.nanoTime(); r.catchAdvice(col); val tCa2 = (System.nanoTime() - t) / 1e6
            t = System.nanoTime(); for (lg in League.entries) r.pvpTeam(lg, col); val tTeam = (System.nanoTime() - t) / 1e6
            System.gc()
            val mem1 = rt.totalMemory() - rt.freeMemory()
            val entries = clearPvpCache()
            println("== PERF n=$size: powerUp frío=%.0fms tibio=%.0fms (%d consejos) | catch=%.0fms / %.0fms (%d) | pvpTeam x3=%.0fms | pvpTables entradas=%,d (~%d tablas) heap Δ≈%dMB".format(
                tPu, tPu2, pu.size, tCa, tCa2, ca.size, tTeam, entries, entries / 4096, (mem1 - mem0) / 1_000_000))
            printAdvice("big$size (primeros 15)", pu, 15)
        }
        // Costo del primer topAttackers (18 tipos) en un Recommender nuevo
        val r = Recommender(idx)
        val t = System.nanoTime(); Types.ALL.forEach { r.topAttackers(it) }; println("topAttackers 18 tipos frío: %.0fms".format((System.nanoTime() - t) / 1e6))
        val t2 = System.nanoTime(); Types.ALL.forEach { r.maxTop(it) }; println("maxTop 18 tipos frío: %.0fms".format((System.nanoTime() - t2) / 1e6))
    }

    /** Sanity del PvE: PveTarget genérico vs. tipo forzado. */
    @Test
    fun pveSanity() {
        val r = Recommender(idx)
        listOf("fighting", "dragon", "ground", "psychic").forEach { type ->
            println("== top $type (primeros 8) ==")
            r.topAttackers(type).take(8).forEachIndexed { i, x ->
                println("  #${i + 1} ${x.species.id} ${x.fast.id}/${x.charged.id} score=%.1f%s".format(x.score, if (x.usesElite) " [élite]" else ""))
            }
        }
        val m = PveCalc.best(idx["machamp_shadow"]!!, PveTarget(assumeWeakTo = "fighting"), idx, 40.0, Ivs.PERFECT, chargedType = "fighting")
        println("Machamp shadow best: ${m?.fast?.id}/${m?.charged?.id}")
    }
}
