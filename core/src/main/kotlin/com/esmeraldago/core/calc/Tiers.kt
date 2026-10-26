package com.esmeraldago.core.calc

/**
 * Tiers relativos al #1 de la lista que se está mirando, en escalones de 10% (como el "ESpace" de DialgaDex):
 *   S ≥ 90% · A ≥ 80 · B ≥ 70 · C ≥ 60 · D < 60
 * Con el filtro "en su clase" el #1 es el mejor de esa clase, así que el tier dice qué tan cerca está del mejor
 * comparable. Ni PvPoke ni el Game Master publican tiers.
 * La métrica comparada es la que ordena la lista: ER (DPS³·TDO)^¼ u otra en PvE, daño del Max Attack (o aguante)
 * en Dinamax y puntaje general de PvPoke en PvP. El puntaje de PvPoke está comprimido (el #387 saca ~80% del #1),
 * así que en PvP el puesto dice más que el tier.
 */
enum class Tier(val label: String, val minRatio: Double) {
    S("S", 0.90), A("A", 0.80), B("B", 0.70), C("C", 0.60), D("D", 0.0);

    companion object {
        fun of(value: Double, best: Double): Tier {
            if (best <= 0.0) return D
            val ratio = value / best
            return entries.first { ratio >= it.minRatio }
        }
    }
}
