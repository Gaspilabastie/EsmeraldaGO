package com.esmeraldago.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** Nivel con el que se calculan los rankings PvE / Dinamax (40 o 50). */
    val rankLevel: Double = 40.0,
    /** Nivel máximo para PvP: 50, o 51 con mejor compañero. */
    val pvpMaxLevel: Double = 50.0,
    /** Nivel de Max Attack para los rankings Dinamax (1..3). */
    val maxMoveLevel: Int = 3,
    /** Escaneo en vivo: modo continuo (lee solo mientras deslizás por la colección). */
    val liveContinuous: Boolean = false,
    /** Nivel de entrenador (0 = no indicado). Acota el nivel máximo de los Pokémon al escanear. */
    val trainerLevel: Int = 0,
) {
    /** Nivel máximo que puede tener un Pokémon tuyo: entrenador + 10, tope 51 (Mejor Compañero). */
    val pokemonLevelCap: Double get() = if (trainerLevel <= 0) 51.0 else minOf(51.0, trainerLevel + 10.0)
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        Settings(
            rankLevel = prefs.getFloat("rankLevel", 40f).toDouble(),
            pvpMaxLevel = prefs.getFloat("pvpMaxLevel", 50f).toDouble(),
            maxMoveLevel = prefs.getInt("maxMoveLevel", 3),
            liveContinuous = prefs.getBoolean("liveContinuous", false),
            trainerLevel = prefs.getInt("trainerLevel", 0),
        )
    )
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun update(f: (Settings) -> Settings) {
        val s = f(_state.value)
        _state.value = s
        prefs.edit()
            .putFloat("rankLevel", s.rankLevel.toFloat())
            .putFloat("pvpMaxLevel", s.pvpMaxLevel.toFloat())
            .putInt("maxMoveLevel", s.maxMoveLevel)
            .putBoolean("liveContinuous", s.liveContinuous)
            .putInt("trainerLevel", s.trainerLevel)
            .apply()
    }
}
