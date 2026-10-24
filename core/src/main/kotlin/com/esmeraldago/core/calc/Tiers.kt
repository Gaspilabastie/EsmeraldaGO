package com.esmeraldago.core.calc

/**
 * Tiers relativos al #1 del ranking COMPLETO, sin filtros (estilo Pokémon GO Hub, que también los calcula contra
 * el puntaje máximo para que sigan sirviendo cuando cambia el meta). Ni PvPoke ni el Game Master publican tiers.
 *   S ≥ 95% · A+ ≥ 90 · A ≥ 85 · B+ ≥ 80 · B ≥ 75 · C ≥ 70 · D < 70
 * La métrica comparada es la de cada lista: ER (DPS³·TDO)^¼ en PvE, daño del Max Attack (o aguante) en Dinamax y
 * puntaje general de PvPoke en PvP. El tier y el puesto dicen cosas distintas: el puntaje de PvPoke está comprimido
 * (el #387 saca ~80% del #1), así que un B+ lejos en la lista es "rinde parecido, pero hay muchos mejores".
 */
enum class Tier(val label: String, val minRatio: Double) {
    S("S", 0.95), A_PLUS("A+", 0.90), A("A", 0.85), B_PLUS("B+", 0.80), B("B", 0.75), C("C", 0.70), D("D", 0.0);

    companion object {
        fun of(value: Double, best: Double): Tier {
            if (best <= 0.0) return D
            val ratio = value / best
            return entries.first { ratio >= it.minRatio }
        }
    }
}
