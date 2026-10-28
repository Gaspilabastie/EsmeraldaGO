package com.esmeraldago.core.reco

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.model.Types

/** Un integrante del equipo a analizar: especie (la variante oscura es otra especie) y sus ataques. */
data class TeamMember(val species: Species, val fast: String?, val charged: List<String>)

/** Un rival del meta que le gana a varios del equipo y al que ninguno le gana. */
data class TeamThreat(val species: Species, val rank: Int, val beats: List<Int>)

data class TeamAnalysis(
    /** Puesto de cada integrante en el ranking de la liga (null = fuera del ranking). */
    val ranks: List<Int?>,
    /** Tipos que les pegan supereficaz a dos o más y que nadie del equipo resiste. */
    val sharedWeaknesses: List<String>,
    /** Por tipo atacante, el multiplicador contra cada integrante (en orden). */
    val defense: Map<String, List<Double>>,
    /** Tipos (defensores de un solo tipo) a los que ningún ataque del equipo les pega supereficaz. */
    val uncovered: List<String>,
    /** Rivales del meta (según las partidas clave de PvPoke) que le ganan a dos o más y a los que nadie le gana. */
    val threats: List<TeamThreat>,
    /** Cuántos rivales del meta se miraron. */
    val metaSize: Int,
)

/**
 * Análisis de un equipo PvP con datos establecidos, sin simulaciones propias:
 * - la tabla de tipos de Pokémon GO (debilidades compartidas y cobertura de ataques);
 * - las partidas clave que publica PvPoke en sus rankings: para cada Pokémon, a quiénes les gana ("matchups") y
 *   quiénes le ganan ("counters"). Son pocas por Pokémon, así que las amenazas son las conocidas, no todas.
 */
object TeamAnalyzer {
    fun analyze(idx: GameIndex, league: League, team: List<TeamMember>, metaSize: Int = 100): TeamAnalysis {
        val ranks = team.map { idx.pvpRank(league, it.species.id)?.first }

        val defense = Types.ALL.associateWith { t -> team.map { Types.effectiveness(t, it.species.types) } }
        val shared = defense.filter { (_, m) -> m.count { it > 1.0 } >= 2 && m.none { it < 1.0 } }.keys.toList()
            .sortedByDescending { t -> defense[t]!!.count { it > 1.0 } }

        val moveTypes = team.flatMap { listOfNotNull(it.fast) + it.charged }.mapNotNull { idx.move(it)?.type }.distinct()
        val uncovered = Types.ALL.filter { d -> moveTypes.none { Types.effectiveness(it, listOf(d)) > 1.0 } }

        val mine = team.map { idx.pvpRank(league, it.species.id)?.second }
        val meta = idx.pvpRanking(league).take(metaSize)
        val threats = meta.mapIndexedNotNull { i, rival ->
            val s = idx[rival.id] ?: return@mapIndexedNotNull null
            if (team.any { it.species.id == rival.id }) return@mapIndexedNotNull null
            val beats = team.indices.filter { m -> mine[m]?.losses?.contains(rival.id) == true || team[m].species.id in rival.wins }
            val answered = team.indices.any { m -> mine[m]?.wins?.contains(rival.id) == true || team[m].species.id in rival.losses }
            if (beats.size >= 2 && !answered) TeamThreat(s, i + 1, beats) else null
        }
        return TeamAnalysis(ranks, shared, defense, uncovered, threats, meta.size)
    }

    /** Ataques de un Pokémon sin datos propios: el moveset recomendado por PvPoke para la liga. */
    fun recommended(idx: GameIndex, league: League, s: Species): TeamMember {
        val ms = idx.pvpRank(league, s.id)?.second?.moveset.orEmpty()
        return TeamMember(s, ms.firstOrNull() ?: s.fast.firstOrNull(), ms.drop(1).ifEmpty { s.charged.take(1) })
    }
}
