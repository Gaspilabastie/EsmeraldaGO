package com.esmeraldago.core.critic

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.calc.PveTarget
import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.data.GameDataSources
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Crítico de mecánicas (MEC-n). Los tests que FALLAN son evidencia de bugs; los que pasan
 * documentan valores de referencia verificados.
 */
class MechanicsCriticTest {
    companion object {
        private fun file(rel: String) = listOf("../$rel", rel).map(::File).first { it.exists() }
        val idx: GameIndex by lazy { GameIndex(GameDataSources.decode(file("app/src/main/assets/gamedata.json").readText())) }
    }

    // ------------------------------------------------------------ OK: referencias que deben pasar

    @Test
    fun ok_cpmReferenceValues() {
        assertEquals(0.094, idx.cpm(1.0), 1e-7)
        assertEquals(0.1351374318, idx.cpm(1.5), 1e-7)
        assertEquals(0.78747358, idx.cpm(39.5), 1e-6)
        assertEquals(0.7903, idx.cpm(40.0), 1e-7)
        assertEquals(0.84029999, idx.cpm(50.0), 1e-6)
        assertEquals(0.84529999, idx.cpm(51.0), 1e-6)
    }

    @Test
    fun ok_knownMaxCp() {
        assertEquals(4724, PokeMath.cp(idx["mewtwo"]!!, 50.0, Ivs.PERFECT, idx))
        assertEquals(2757, PokeMath.cp(idx["blissey"]!!, 40.0, Ivs.PERFECT, idx))
    }

    @Test
    fun ok_powerUpTotals() {
        // Tabla oficial: 1→40 = 270.000 polvo / 304 caramelos; 40→50 = 250.000 polvo / 296 XL.
        assertEquals(com.esmeraldago.core.calc.PowerUpCost(270_000, 304, 0), PokeMath.powerUpCost(1.0, 40.0))
        assertEquals(com.esmeraldago.core.calc.PowerUpCost(250_000, 0, 296), PokeMath.powerUpCost(40.0, 50.0))
        assertEquals(324_000, PokeMath.powerUpCost(1.0, 40.0, shadow = true).stardust)
        assertEquals(135_000, PokeMath.powerUpCost(1.0, 40.0, lucky = true).stardust)
    }

    @Test
    fun ok_bestLevelUnderCapMatchesBruteForce() {
        for (id in listOf("azumarill", "medicham", "registeel", "mewtwo", "bastiodon")) {
            val s = idx[id]!!
            for (iv in Ivs.ALL.filterIndexed { i, _ -> i % 37 == 0 }) for (cap in listOf(1500, 2500)) {
                var brute: Double? = null
                var l = 1.0
                while (l <= 50.0) { if (PokeMath.cp(s, l, iv, idx) <= cap) brute = l; l += 0.5 }
                assertEquals(brute, PokeMath.bestLevelUnderCap(s, iv, cap, 50.0, idx), "$id $iv $cap")
            }
        }
    }

    // ------------------------------------------------------------ MEC-1: movimientos sin stats PvE

    @Test
    fun mec1_pyroBallUsesPvpFallback() {
        val m = idx.move("PYRO_BALL")!!
        println("PYRO_BALL en app: power=${m.power} energy=${m.energy} dur=${m.durationMs}  (PokeMiners V0493_MOVE_PYROBALL: 150/100/2000)")
        // Impacto en el ranking de atacantes fuego
        val fixedData = idx.data.copy(moves = idx.data.moves.map {
            if (it.id == "PYRO_BALL") it.copy(power = 150.0, energy = 100, durationMs = 2000) else it
        })
        val idx2 = GameIndex(fixedData)
        fun rankOf(i: GameIndex) = PveCalc.topAttackers("fire", i, limit = 200).indexOfFirst { it.species.id == "cinderace" } + 1
        val c1 = PveCalc.best(idx["cinderace"]!!, PveTarget(assumeWeakTo = "fire"), idx, chargedType = "fire")!!
        val c2 = PveCalc.best(idx2["cinderace"]!!, PveTarget(assumeWeakTo = "fire"), idx2, chargedType = "fire")!!
        println("Cinderace fuego: app=${c1.charged.id} dps=%.2f rank=${rankOf(idx)} | corregido=${c2.charged.id} dps=%.2f rank=${rankOf(idx2)}".format(c1.dps, c2.dps))
        assertEquals(150.0, m.power)
        assertEquals(100, m.energy)
        assertEquals(2000, m.durationMs)
    }

    @Test
    fun mec1_otherAliasedMoves() {
        // PvPoke id -> (PokeMiners id, power, energy, durationMs) según data-cache/pokeminers-gamemaster.json
        val expected = mapOf(
            "FUTURE_SIGHT" to Triple(115.0, 100, 2500),       // V0275_MOVE_FUTURESIGHT
            "TECHNO_BLAST_DOUSE" to Triple(120.0, 100, 2000), // V0339_MOVE_TECHNO_BLAST_WATER
            "HIDDEN_POWER_FIRE" to Triple(15.0, 15, 1500),    // V0281_MOVE_HIDDEN_POWER_FAST
        )
        val bad = expected.filter { (id, e) ->
            val m = idx.move(id)!!
            Triple(m.power, m.energy, m.durationMs) != e
        }.map { (id, e) -> val m = idx.move(id)!!; "$id app=${m.power}/${m.energy}/${m.durationMs} esperado=${e.first}/${e.second}/${e.third}" }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    // ------------------------------------------------------------ MEC-2: el jefe no recibe STAB

    @Test
    fun mec2_bossIncomingDamageIgnoresBossStab() {
        val boss = idx["kyogre"]!!
        val target = PveCalc.bossTarget(boss, RaidTier.T5, idx)
        val attacker = idx["raikou"]!!
        val st = PveCalc.stats(attacker, 40.0, Ivs.PERFECT, idx)
        val asTarget = PveTarget(types = attacker.types)
        var sum = 0.0; var n = 0
        for (f in target.fastMoves) for (c in target.chargedMoves) {
            val fd = PveCalc.damage(f.power, f.type, target.atk!!, st.def, boss.types, asTarget)
            val cd = PveCalc.damage(c.power, c.type, target.atk!!, st.def, boss.types, asTarget)
            val k = ceil(c.energy.toDouble() / f.energy)
            sum += (fd * k + cd) / (k * (f.durationMs / 1000.0 + 2.0) + (c.durationMs / 1000.0 + 2.0)); n++
        }
        val expected = sum / n
        val got = PveCalc.incomingDps(attacker, st, target)
        println("DPS recibido por Raikou vs Kyogre: app=%.2f con STAB del jefe=%.2f".format(got, expected))
        assertEquals(expected, got, 0.01)
    }

    // ------------------------------------------------------------ MEC-3: G-Max / movimientos Max especiales

    @Test
    fun mec3_gmaxMoveIndependentOfFastType() {
        val snorlax = idx["snorlax"]!!
        val lick = idx.move("LICK")!!
        val r = MaxCalc.evaluate(snorlax, true, lick, idx)
        println("G-Max Snorlax + Lick -> ${r.maxMoveName} (${r.maxMoveType})")
        val inNormal = MaxCalc.topAttackers("normal", idx, limit = 500).any { it.species.id == "snorlax" && it.gigantamax }
        println("G-Max Snorlax aparece en top Max normal: $inNormal")
        assertEquals("G-Max Replenish", r.maxMoveName)
        assertEquals("normal", r.maxMoveType)
        assertTrue(inNormal)
    }

    @Test
    fun mec3_crownedZacianMaxAttackIsBehemothBlade() {
        val z = idx["zacian_crowned_sword"]!!
        val r = MaxCalc.evaluate(z, false, idx.move("AIR_SLASH")!!, idx)
        println("Zacian Crowned + Air Slash -> ${r.maxMoveName} (${r.maxMoveType})")
        assertEquals("steel", r.maxMoveType)
        assertTrue(r.maxMoveName.contains("Behemoth"), r.maxMoveName)
    }

    // ------------------------------------------------------------ MEC-4: legacyMoves de PvPoke != legado

    @Test
    fun mec4_signatureFormMovesAreNotLegacy() {
        val flagged = listOf(
            "zacian_crowned_sword" to "BEHEMOTH_BLADE", "zamazenta_crowned_shield" to "BEHEMOTH_BASH",
            "dialga_origin" to "ROAR_OF_TIME", "palkia_origin" to "SPACIAL_REND",
            "kyurem_black" to "FREEZE_SHOCK", "kyurem_white" to "ICE_BURN",
        ).filter { (id, m) -> idx[id]?.isElite(m) == true }
        val z = idx["zacian_crowned_sword"]!!
        val noSpecial = PveCalc.best(z, PveTarget(assumeWeakTo = "steel"), idx, chargedType = "steel", allowElite = false)
        assertTrue(noSpecial != null, "Zacian-C sin MT Élite debe conservar Behemoth Blade")
        println("Marcados LEGADO (son movimientos de forma, no legado): $flagged; Zacian-C sin especiales (acero): ${noSpecial?.charged?.id}")
        assertTrue(flagged.isEmpty(), "$flagged")
    }

    @Test
    fun info_impactOfAliasedMovesOnPsychicRanking() {
        val fixes = mapOf(
            "FUTURE_SIGHT" to Triple(115.0, 100, 2500), "PYRO_BALL" to Triple(150.0, 100, 2000),
            "TECHNO_BLAST_DOUSE" to Triple(120.0, 100, 2000),
        )
        val idx2 = GameIndex(idx.data.copy(moves = idx.data.moves.map { m ->
            fixes[m.id]?.let { m.copy(power = it.first, energy = it.second, durationMs = it.third) }
                ?: if (m.id.startsWith("HIDDEN_POWER_")) m.copy(power = 15.0, energy = 15, durationMs = 1500) else m
        }))
        fun top(i: GameIndex) = PveCalc.topAttackers("psychic", i, limit = 15).map { "${it.species.id}:${it.charged.id}" }
        println("psychic app      : ${top(idx)}")
        println("psychic corregido: ${top(idx2)}")
    }

    @Test
    fun info_impactOfBossStabOnCounters() {
        for ((bossId, tier) in listOf("kyogre" to RaidTier.T5, "rayquaza" to RaidTier.T5)) {
            val boss = idx[bossId]!!
            val t = PveCalc.bossTarget(boss, tier, idx)
            // Aproximación del fix: potencia ×1.2 en los ataques del jefe que coinciden con su tipo.
            val stab = { ms: List<com.esmeraldago.core.model.Move> -> ms.map { if (it.type in boss.types) it.copy(power = it.power * 1.2) else it } }
            val t2 = t.copy(fastMoves = stab(t.fastMoves), chargedMoves = stab(t.chargedMoves))
            fun top(tt: PveTarget) = idx.released.mapNotNull { PveCalc.best(it, tt, idx) }.sortedByDescending { it.score }.take(12).map { it.species.id }
            println("$bossId app      : ${top(t)}")
            println("$bossId con STAB : ${top(t2)}")
        }
    }

    @Test
    fun mec7_gmaxNotInAllowedSourdoughList() {
        // BREAD_SHARED_SETTINGS.allowedSourdoughPokemon (data-cache/pokeminers-gamemaster.json ~l.57309) NO incluye DURALUDON,
        // pero SOURDOUGH_MOVE_MAPPING_SETTINGS sí lo mapea (G-Max Depletion).
        val top = MaxCalc.topAttackers("dragon", idx, limit = 10).map { it.displayName }
        println("Top Max dragón: $top")
        assertTrue(idx["duraludon"]?.gigantamax != true, "Duraludon marcado Gigamax sin estar en allowedSourdoughPokemon")
    }

    @Test
    fun mec7b_urshifuRapidStrikeGmaxStillAllowed() {
        // allowedSourdoughPokemon lista URSHIFU_RAPID_STRIKE con breadMode "BREAD_DOUGH_MODE_2" (no "BREAD_DOUGH_MODE").
        val r = idx["urshifu_rapid_strike"]!!
        println("urshifu_rapid_strike gigantamax=${r.gigantamax} gmaxMove=${r.gmaxMove}")
        assertTrue(r.gigantamax, "Regresión: G-Max Urshifu (Rapid Strike) perdido por BREAD_DOUGH_MODE_2")
    }

    // ------------------------------------------------------------ MEC-5: levelsForDustCost incluye niveles no subibles

    @Test
    fun mec5_levelsForDustCostExcludesUnpowerableLevels() {
        val l = PokeMath.levelsForDustCost(15000, false, false, false, idx.maxLevel)
        println("niveles para 15000 polvo: $l")
        assertEquals(listOf(49.0, 49.5), l)
    }

    // ------------------------------------------------------------ info: CPM de jefes por tier
    @Test
    fun info_raidTierCpm() {
        // Referencia comunitaria (Pokebattler/GamePress): T1 = 0.5974 (CPM de nivel 20). Confianza media.
        println("RaidTier.T1.cpm=${RaidTier.T1.cpm} (ref 0.5974)")
        assertTrue(abs(RaidTier.T3.cpm - 0.73) < 1e-9 && abs(RaidTier.T5.cpm - 0.79) < 1e-9)
    }
}
