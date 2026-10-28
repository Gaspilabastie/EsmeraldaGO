package com.esmeraldago.core.model

import kotlinx.serialization.Serializable

/** Un Pokémon de la colección del usuario. */
@Serializable
data class OwnedPokemon(
    val uid: String,
    /** Id de la especie base (sin "_shadow"). */
    val speciesId: String,
    val cp: Int,
    val hp: Int? = null,
    val level: Double,
    val ivAtk: Int,
    val ivDef: Int,
    val ivSta: Int,
    /** false si los IV son una estimación (varias combinaciones posibles). */
    val ivExact: Boolean = true,
    val fast: String? = null,
    val charged1: String? = null,
    val charged2: String? = null,
    val shadow: Boolean = false,
    val purified: Boolean = false,
    val lucky: Boolean = false,
    val shiny: Boolean = false,
    val dynamax: Boolean = false,
    val gigantamax: Boolean = false,
    val nickname: String? = null,
    val addedAt: Long = 0,
    /** Peso y altura tal como los muestra el juego: con la especie, identifican al Pokémon al volver a escanearlo. */
    val weightKg: Double? = null,
    val heightM: Double? = null,
) {
    /** Id de PvPoke para combates (la variante shadow es otra entrada). */
    val battleId: String get() = if (shadow) "${speciesId}_shadow" else speciesId
    val ivPercent: Int get() = ((ivAtk + ivDef + ivSta) * 100.0 / 45).toInt()
    val chargedMoves: List<String> get() = listOfNotNull(charged1, charged2)
}
