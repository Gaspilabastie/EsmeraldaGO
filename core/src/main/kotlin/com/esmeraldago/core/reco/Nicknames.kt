package com.esmeraldago.core.reco

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.OwnedPokemon

/** Estilos de apodo con los datos del Pokémon, como los que arman Calcy IV o Poke Genie. */
enum class NickStyle(val label: String) {
    /** "93 ⑮⑭⑫": IV % y cada IV con un número en círculo (la forma más difundida, ocupa poco). */
    CIRCLED("IV % y cada IV en círculo"),
    /** "93 15/14/12". */
    SLASH("IV % y cada IV"),
    /** "S12 U230": puesto de sus IV en Liga Super y Ultra (de la evolución que compite). */
    PVP("Puesto PvP de sus IV"),
}

/**
 * Apodos con los datos del Pokémon para escribir en el juego (se copian y se pegan al renombrarlo). Pokémon GO admite
 * hasta 12 caracteres; los números en círculo (⓪–⑮) son los que usan las apps conocidas para cada IV.
 */
object Nicknames {
    const val MAX = 12
    private const val CIRCLED = "⓪①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮"

    private fun circled(n: Int) = CIRCLED[n.coerceIn(0, 15)].toString()

    fun make(p: OwnedPokemon, style: NickStyle, idx: GameIndex, maxLevel: Double = 50.0): String {
        val pct = p.ivPercent.toString()
        return when (style) {
            NickStyle.CIRCLED -> "$pct ${circled(p.ivAtk)}${circled(p.ivDef)}${circled(p.ivSta)}"
            NickStyle.SLASH -> "$pct ${p.ivAtk}/${p.ivDef}/${p.ivSta}"
            NickStyle.PVP -> listOf(League.GREAT to "S", League.ULTRA to "U").mapNotNull { (lg, letter) ->
                pvpIvRank(p, lg, idx, maxLevel)?.let { "$letter$it" }
            }.joinToString(" ").ifEmpty { pct }
        }.take(MAX)
    }

    /**
     * Puesto de los IV en la liga para la especie de su línea que mejor rinde ahí según PvPoke (Marill → Azumarill).
     * null si ya se pasa del PC de la liga o si ninguna de su línea figura en el ranking.
     */
    fun pvpIvRank(p: OwnedPokemon, league: League, idx: GameIndex, maxLevel: Double): Int? {
        if (league != League.MASTER && p.cp > league.cap) return null
        val s = idx[p.battleId] ?: idx[p.speciesId] ?: return null
        val line = (listOf(s) + idx.descendants(s).map { d -> if (p.shadow) idx.shadowOf(d) ?: d else d })
        val best = line.mapNotNull { c -> idx.pvpRank(league, c.id)?.let { c to it.first } }.minByOrNull { it.second }?.first ?: return null
        val r = PokeMath.pvpRankOf(best, Ivs(p.ivAtk, p.ivDef, p.ivSta), league.cap, maxLevel, idx) ?: return null
        // Ya subido por encima del nivel con el que entra en la liga: no juega ahí.
        return if (league != League.MASTER && p.level > r.level) null else r.rank
    }
}
