package com.esmeraldago.core.calc

/**
 * Tiers relativos al #1 del ranking COMPLETO, sin filtros (estilo Pokémon GO Hub, que también los calcula contra
 * el puntaje máximo para que sigan sirviendo cuando cambia el meta). Ni PvPoke ni el Game Master publican tiers.
 *   S ≥ 95% · A+ ≥ 90 · A ≥ 85 · B+ ≥ 80 · B ≥ 75 · C ≥ 70 · D < 70
 * La métrica comparada es la de cada lista: ER (DPS³·TDO)^¼ en PvE y daño del Max Attack (o aguante) en Dinamax.
 * En PvP se usa el puesto ([pvpOf]): el puntaje de PvPoke está comprimido (el #387 saca ~80% del #1) y con
 * porcentajes un Pokémon fuera del meta salía B+.
 */
enum class Tier(val label: String, val minRatio: Double, val maxPvpRank: Int) {
    S("S", 0.95, 10), A_PLUS("A+", 0.90, 25), A("A", 0.85, 50), B_PLUS("B+", 0.80, 100),
    B("B", 0.75, 200), C("C", 0.70, 400), D("D", 0.0, Int.MAX_VALUE);

    companion object {
        fun of(value: Double, best: Double): Tier {
            if (best <= 0.0) return D
            val ratio = value / best
            return entries.first { ratio >= it.minRatio }
        }

        /** Tier PvP por puesto en el ranking de la liga: top 10 S · 25 A+ · 50 A · 100 B+ · 200 B · 400 C. */
        fun pvpOf(rank: Int): Tier = entries.first { rank <= it.maxPvpRank }

        const val PVP_LEGEND = "Tier PvP por puesto: S top 10 · A+ 25 · A 50 · B+ 100 · B 200 · C 400 · D resto"
    }
}
