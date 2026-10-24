package com.esmeraldago.core.model

/** Tabla de tipos de Pokémon GO (supereficaz 1.6, poco eficaz 0.625, "inmune" 0.390625). */
object Types {
    val ALL = listOf(
        "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
        "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy",
    )

    val SPANISH = mapOf(
        "normal" to "Normal", "fire" to "Fuego", "water" to "Agua", "electric" to "Eléctrico",
        "grass" to "Planta", "ice" to "Hielo", "fighting" to "Lucha", "poison" to "Veneno",
        "ground" to "Tierra", "flying" to "Volador", "psychic" to "Psíquico", "bug" to "Bicho",
        "rock" to "Roca", "ghost" to "Fantasma", "dragon" to "Dragón", "dark" to "Siniestro",
        "steel" to "Acero", "fairy" to "Hada",
    )

    const val SE = 1.6
    const val NVE = 0.625
    const val IMMUNE = 0.390625

    // atacante -> (defensor -> multiplicador)
    private val chart: Map<String, Map<String, Double>> = buildMap {
        fun def(atk: String, superEff: String, notVery: String, immune: String = "") {
            val m = mutableMapOf<String, Double>()
            superEff.split(' ').filter { it.isNotBlank() }.forEach { m[it] = SE }
            notVery.split(' ').filter { it.isNotBlank() }.forEach { m[it] = NVE }
            immune.split(' ').filter { it.isNotBlank() }.forEach { m[it] = IMMUNE }
            put(atk, m)
        }
        def("normal", "", "rock steel", "ghost")
        def("fire", "grass ice bug steel", "fire water rock dragon")
        def("water", "fire ground rock", "water grass dragon")
        def("electric", "water flying", "electric grass dragon", "ground")
        def("grass", "water ground rock", "fire grass poison flying bug dragon steel")
        def("ice", "grass ground flying dragon", "fire water ice steel")
        def("fighting", "normal ice rock dark steel", "poison flying psychic bug fairy", "ghost")
        def("poison", "grass fairy", "poison ground rock ghost", "steel")
        def("ground", "fire electric poison rock steel", "grass bug", "flying")
        def("flying", "grass fighting bug", "electric rock steel")
        def("psychic", "fighting poison", "psychic steel", "dark")
        def("bug", "grass psychic dark", "fire fighting poison flying ghost steel fairy")
        def("rock", "fire ice flying bug", "fighting ground steel")
        def("ghost", "psychic ghost", "dark", "normal")
        def("dragon", "dragon", "steel", "fairy")
        def("dark", "psychic ghost", "fighting dark fairy")
        def("steel", "ice rock fairy", "fire water electric steel")
        def("fairy", "fighting dragon dark", "fire poison steel")
    }

    fun effectiveness(moveType: String, defenderTypes: List<String>): Double {
        val row = chart[moveType] ?: return 1.0
        var m = 1.0
        for (t in defenderTypes) if (t != "none") m *= row[t] ?: 1.0
        return m
    }

    /** Tipos que le hacen daño supereficaz (multiplicador > 1) a esta combinación. */
    fun weaknesses(defenderTypes: List<String>): List<String> =
        ALL.filter { effectiveness(it, defenderTypes) > 1.0 }

    fun resistances(defenderTypes: List<String>): List<String> =
        ALL.filter { effectiveness(it, defenderTypes) < 1.0 }

    /** Tipos que le pegan normal (×1). */
    fun neutrals(defenderTypes: List<String>): List<String> =
        ALL.filter { effectiveness(it, defenderTypes) == 1.0 }

    /** Al atacar con [moveType]: multiplicador contra cada tipo defensor (un solo tipo). */
    fun attackChart(moveType: String): Map<String, Double> = ALL.associateWith { effectiveness(moveType, listOf(it)) }

    fun es(type: String) = SPANISH[type] ?: type.replaceFirstChar { it.uppercase() }
}
