package com.esmeraldago.core.model

import kotlinx.serialization.Serializable

/** Datos del juego ya procesados: todo lo que la app necesita para rankings y cálculos. */
@Serializable
data class GameData(
    val generatedAt: Long,
    val sourceVersion: String,
    /** Multiplicador de CP para niveles 1.0..51.0 en pasos de 0.5 (índice = (nivel - 1) * 2). */
    val cpm: List<Double>,
    val species: List<Species>,
    val moves: List<Move>,
    /** Nombre del Max Move genérico por tipo (ej. "fire" -> "Max Flare"). */
    val maxMoveNames: Map<String, String> = emptyMap(),
    /** Rankings de PvPoke por liga: "great", "ultra", "master". */
    val pvp: Map<String, List<PvpRank>> = emptyMap(),
)

@Serializable
data class Species(
    /** Id de PvPoke, ej. "machamp", "machamp_shadow", "charizard_mega_x". */
    val id: String,
    val dex: Int,
    val name: String,
    /** Id de la forma base (sin shadow/mega), para agrupar. */
    val baseId: String,
    val atk: Int,
    val def: Int,
    val sta: Int,
    val types: List<String>,
    val fast: List<String>,
    val charged: List<String>,
    /** Movimientos de legado / evento: solo se enseñan con MT Élite ("eliteMoves" de PvPoke). */
    val elite: List<String> = emptyList(),
    /**
     * "legacyMoves" de PvPoke: en la práctica son movimientos de firma o de forma (Behemoth Blade,
     * Roar of Time de Dialga Origen…), NO legado. No se excluyen de los rankings.
     */
    val legacy: List<String> = emptyList(),
    val shadow: Boolean = false,
    val mega: Boolean = false,
    val shadowEligible: Boolean = false,
    val legendary: Boolean = false,
    val mythical: Boolean = false,
    val ultraBeast: Boolean = false,
    val dynamax: Boolean = false,
    val gigantamax: Boolean = false,
    /** Nombre del movimiento G-Max (si es Gigamax). */
    val gmaxMove: String? = null,
    /** Tipo del movimiento G-Max. */
    val gmaxType: String? = null,
    /** Max Attack exclusivo de la especie (Behemoth Blade, Dynamax Cannon…), reemplaza al genérico. */
    val specialMaxMove: String? = null,
    val specialMaxType: String? = null,
    val family: String? = null,
    val parent: String? = null,
    val evolutions: List<String> = emptyList(),
    val released: Boolean = true,
    val buddyKm: Int = 3,
    /** Nombre en el juego en español, solo cuando difiere ("Great Tusk" → "Colmilargo"). */
    val nameEs: String? = null,
) {
    /** Requiere MT Élite (legado / evento). */
    fun isElite(moveId: String) = moveId in elite
    /** Movimiento de firma o de forma (no se pierde ni requiere MT Élite). */
    fun isSignature(moveId: String) = moveId in legacy && moveId !in elite

    /**
     * Fuera de lo "budget" (lo que se consigue sin incursiones raras): legendarios, singulares y ultraentes,
     * la misma lista que prohíbe la Premier Cup. Megas y oscuros tienen su propio filtro.
     */
    val nonBudget: Boolean get() = legendary || mythical || ultraBeast

    /**
     * Zacian y Zamazenta Coronados entran a los combates Max sin poder Dinamaxizar (anuncio oficial de GO,
     * 2025): cuentan para los rankings Max pero no llevan la marca Dinamax. Las formas Héroe no participan.
     */
    val maxWithoutDynamax: Boolean get() = id == "zacian_crowned_sword" || id == "zamazenta_crowned_shield"
    /** Puede Dinamaxizar (la marca roja); [dynamax] además incluye a quienes solo entran a combates Max. */
    val canDynamax: Boolean get() = dynamax && !maxWithoutDynamax
}

@Serializable
data class Move(
    val id: String,
    val name: String,
    val type: String,
    val fast: Boolean,
    // PvE (gimnasios, incursiones, Max Battles)
    val power: Double,
    /** Rápido: energía ganada. Cargado: energía consumida (positivo). */
    val energy: Int,
    val durationMs: Int,
    // PvP (Liga de Combates GO)
    val pvpPower: Double,
    val pvpEnergy: Int,
    val pvpTurns: Int,
    /** Nombre en el juego en español ("Wrap" → "Constricción"), para leer el cliente en español. */
    val nameEs: String? = null,
)

@Serializable
data class PvpRank(
    val id: String,
    val score: Double,
    val moveset: List<String>,
    /** Rivales contra los que gana (ids). */
    val wins: List<String> = emptyList(),
    /** Rivales que lo vencen (ids). */
    val losses: List<String> = emptyList(),
    /** Ataques rápidos y cargados ordenados por uso en las simulaciones de PvPoke (para alternativas sin MT Élite). */
    val fastByUse: List<String> = emptyList(),
    val chargedByUse: List<String> = emptyList(),
) {
    /** Mejor moveset que no requiere MT Élite (según uso en PvPoke), o null si el recomendado ya no requiere. */
    fun nonEliteMoveset(s: Species): List<String>? {
        if (moveset.none { s.isElite(it) }) return null
        val fast = (listOfNotNull(moveset.firstOrNull()) + fastByUse).firstOrNull { !s.isElite(it) } ?: return emptyList()
        val charged = (moveset.drop(1) + chargedByUse).filter { !s.isElite(it) }.distinct().take(2)
        return listOf(fast) + charged
    }
}

enum class League(val key: String, val cap: Int, val label: String) {
    GREAT("great", 1500, "Liga Super"),
    ULTRA("ultra", 2500, "Liga Ultra"),
    MASTER("master", 10000, "Liga Master");

    val pvpokeCp: Int get() = cap
}
