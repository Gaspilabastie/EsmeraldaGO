package com.esmeraldago.core

import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.reco.Recommender
import com.esmeraldago.core.scan.OcrLine
import com.esmeraldago.core.scan.ScreenParser
import com.esmeraldago.core.scan.ScreenType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreTest {
    companion object {
        val idx: GameIndex by lazy {
            val f = listOf("../app/src/main/assets/gamedata.json", "app/src/main/assets/gamedata.json").map(::File).first { it.exists() }
            GameIndex(GameDataSources.decode(f.readText()))
        }
    }

    @Test
    fun cpFormulaMatchesKnownValues() {
        assertEquals(4178, PokeMath.cp(idx["mewtwo"]!!, 40.0, Ivs.PERFECT, idx))
        assertEquals(3056, PokeMath.cp(idx["machamp"]!!, 40.0, Ivs.PERFECT, idx))
    }

    @Test
    fun ivSolverFindsOriginal() {
        val s = idx["machamp"]!!
        val iv = Ivs(13, 7, 11)
        val cp = PokeMath.cp(s, 27.5, iv, idx)
        val hp = PokeMath.hp(s, 27.5, iv, idx)
        val levels = PokeMath.levelsForDustCost(PokeMath.dustFor(27.5), false, false, false, idx.maxLevel)
        val c = PokeMath.solveIvs(s, cp, idx, hp = hp, levels = levels)
        assertTrue(c.any { it.ivs == iv && it.level == 27.5 }, "candidatos: $c")
    }

    @Test
    fun pvpRankTable() {
        val s = idx["azumarill"]!!
        val t = PokeMath.pvpTable(s, 1500, 50.0, idx)
        assertEquals(1, t.first().rank)
        assertTrue(t.first().cp <= 1500)
        println("Azumarill GL #1: ${t.first()}")
    }

    @Test
    fun printRankings() {
        for (type in listOf("fighting", "dragon", "fire")) {
            println("== PvE top $type ==")
            PveCalc.topAttackers(type, idx, 40.0, limit = 10).forEach {
                println("  ${it.species.name.padEnd(28)} ${it.fast.name}/${it.charged.name}  dps=%.1f tdo=%.0f score=%.1f%s".format(
                    it.dps, it.tdo, it.score, if (it.usesElite) " [élite]" else ""))
            }
        }
        println("== Counters vs Kyogre T5 ==")
        PveCalc.counters(idx["kyogre"]!!, RaidTier.T5, idx, limit = 8).forEach {
            println("  ${it.species.name.padEnd(28)} ${it.fast.name}/${it.charged.name} score=%.1f".format(it.score))
        }
        println("== Dynamax top fighting ==")
        MaxCalc.topAttackers("fighting", idx, limit = 6).forEach {
            println("  ${it.displayName.padEnd(28)} ${it.fast.name} -> ${it.maxMoveName} dmg=${it.maxDamage}")
        }
        println("== Dynamax tanks ==")
        MaxCalc.topTanks(idx, limit = 6).forEach { println("  ${it.displayName.padEnd(28)} bulk=%.0f".format(it.bulk)) }
        val top = PveCalc.topAttackers("fighting", idx, 40.0, limit = 10)
        assertTrue(top.isNotEmpty())
    }

    @Test
    fun recommenderBuildsTeams() {
        val col = listOf(
            OwnedPokemon("1", "machamp", 3000, null, 40.0, 15, 14, 13, shadow = true, fast = "COUNTER", charged1 = "DYNAMIC_PUNCH"),
            OwnedPokemon("2", "medicham", 1450, null, 49.0, 0, 15, 15, fast = "COUNTER", charged1 = "ICE_PUNCH", charged2 = "PSYCHIC"),
            OwnedPokemon("3", "marill", 500, null, 20.0, 0, 15, 15),
            OwnedPokemon("4", "registeel", 1400, null, 25.0, 1, 15, 14),
            OwnedPokemon("5", "metagross", 3500, null, 35.0, 15, 15, 15, fast = "BULLET_PUNCH", charged1 = "METEOR_MASH"),
            OwnedPokemon("6", "machamp", 2500, null, 30.0, 12, 12, 12, dynamax = true, fast = "COUNTER"),
        )
        val r = Recommender(idx)
        val raid = r.raidTeam(idx["tyranitar"]!!, RaidTier.T3, col)
        println("Raid vs Tyranitar: " + raid.map { "${it.result.species.name} %.1f".format(it.result.score) })
        assertTrue(raid.first().result.species.id.startsWith("machamp"))
        val team = r.pvpTeam(League.GREAT, col)
        println("GL team: " + team.map { "${it.species.name} meta#${it.metaRank} iv#${it.iv.rank} L${it.iv.level}" })
        assertTrue(team.isNotEmpty())
        r.powerUpAdvice(col).take(8).forEach { println("SUBIR: ${it.title} — ${it.detail} ${it.cost}") }
        r.catchAdvice(col).take(8).forEach { println("ATRAPAR: ${it.title} — ${it.detail}") }
    }

    @Test
    fun parsesDetailScreen() {
        val s = idx["machamp"]!!
        val iv = Ivs(15, 14, 12)
        val cp = PokeMath.cp(s, 30.0, iv, idx)
        val hp = PokeMath.hp(s, 30.0, iv, idx)
        fun line(t: String, y: Float) = OcrLine(t, 0.1f, y, 0.9f, y + 0.03f)
        val lines = listOf(
            line("CP$cp", 0.08f), line("Machamp", 0.45f), line("$hp / $hp HP", 0.50f),
            line("Fighting", 0.55f), line("MACHOP CANDY", 0.62f), line("POWER UP", 0.70f), line("5,000", 0.70f),
            line("Counter", 0.80f), line("Dynamic Punch", 0.85f), line("Cross Chop", 0.90f),
        )
        val r = ScreenParser(idx).parse(lines)
        assertEquals(ScreenType.DETAIL, r.screen)
        assertNotNull(r.species)
        assertEquals("machamp", r.species!!.id)
        assertEquals("COUNTER", r.fast)
        assertTrue(r.candidates.any { it.ivs == iv }, "candidatos ${r.candidates}")
        val exact = ScreenParser(idx).withAppraisal(r, iv)
        assertTrue(exact.ivExact, "con valoración: ${exact.candidates}")
    }
}
