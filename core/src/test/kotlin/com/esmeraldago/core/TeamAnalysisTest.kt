package com.esmeraldago.core

import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Types
import com.esmeraldago.core.reco.TeamAnalyzer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TeamAnalysisTest {
    private val idx by lazy {
        val f = listOf("../app/src/main/assets/gamedata.json", "app/src/main/assets/gamedata.json").map(::File).first { it.exists() }
        GameIndex(GameDataSources.decode(f.readText()))
    }

    @Test
    fun sharedWeaknessNeedsTwoWeakAndNoResist() {
        // Dos de tipo agua y un planta: Eléctrico les pega a los dos de agua y el planta lo resiste → no es compartida;
        // Planta les pega a los de agua y el de planta lo resiste → tampoco.
        val team = listOf("azumarill", "lanturn", "venusaur").map { TeamAnalyzer.recommended(idx, League.GREAT, idx[it]!!) }
        val a = TeamAnalyzer.analyze(idx, League.GREAT, team)
        a.sharedWeaknesses.forEach { t ->
            val m = a.defense[t]!!
            assertTrue(m.count { it > 1.0 } >= 2 && m.none { it < 1.0 }, t)
        }
        assertFalse("grass" in a.sharedWeaknesses)
        println("compartidas ${a.sharedWeaknesses.map(Types::es)}, sin cubrir ${a.uncovered.map(Types::es)}, " +
            "amenazas ${a.threats.map { "#${it.rank} ${it.species.name} → ${it.beats}" }}, puestos ${a.ranks}")
    }

    @Test
    fun threatsBeatTwoAndAreUnanswered() {
        val team = listOf("medicham", "registeel", "galvantula").mapNotNull { idx[it] }.map { TeamAnalyzer.recommended(idx, League.GREAT, it) }
        val a = TeamAnalyzer.analyze(idx, League.GREAT, team)
        a.threats.forEach { assertTrue(it.beats.size >= 2) }
        println("amenazas ${a.threats.map { "#${it.rank} ${it.species.name} → ${it.beats.map { b -> team[b].species.name }}" }}")    }
}
