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
    /** Mostrar en rankings y Pokédex las especies que todavía no salieron en Pokémon GO. */
    val showUnreleased: Boolean = false,
    /** Escaneo en vivo: bajar solo la ficha para leer los ataques (necesita el servicio de accesibilidad). */
    val autoScroll: Boolean = true,
    /** Con Wi-Fi, bajar e instalar las versiones nuevas solo, desde la pantalla de inicio. */
    val autoUpdate: Boolean = true,
    /** Escaneo en vivo: guardar en la caja lo que se leyó completo (especie, PC e IV exactos). */
    val autoSave: Boolean = true,
    /** Posición de la burbuja en píxeles (-1 = la de siempre). */
    val bubbleX: Int = -1,
    val bubbleY: Int = -1,
    /** Estilo del apodo para el juego (nombre de [com.esmeraldago.core.reco.NickStyle]). */
    val nickStyle: String = "CIRCLED",
    /** Escaneo en vivo: copiar el apodo al portapapeles cuando los IV quedan definidos. */
    val autoCopyNick: Boolean = false,
) {
    val nick: com.esmeraldago.core.reco.NickStyle
        get() = com.esmeraldago.core.reco.NickStyle.entries.firstOrNull { it.name == nickStyle } ?: com.esmeraldago.core.reco.NickStyle.CIRCLED

    /**
     * Nivel máximo de un Pokémon al escanear. Ya no se pide el nivel de entrenador: el costo de polvo fija el nivel
     * casi siempre, y el tope (entrenador + 10) solo descartaba algún nivel en entrenadores bajos.
     */
    val pokemonLevelCap: Double get() = 51.0
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
            showUnreleased = prefs.getBoolean("showUnreleased", false),
            autoScroll = prefs.getBoolean("autoScroll", true),
            autoUpdate = prefs.getBoolean("autoUpdate", true),
            autoSave = prefs.getBoolean("autoSave", true),
            bubbleX = prefs.getInt("bubbleX", -1),
            bubbleY = prefs.getInt("bubbleY", -1),
            nickStyle = prefs.getString("nickStyle", null) ?: "CIRCLED",
            autoCopyNick = prefs.getBoolean("autoCopyNick", false),
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
            .putBoolean("showUnreleased", s.showUnreleased)
            .putBoolean("autoScroll", s.autoScroll)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("autoSave", s.autoSave)
            .putInt("bubbleX", s.bubbleX)
            .putInt("bubbleY", s.bubbleY)
            .putString("nickStyle", s.nickStyle)
            .putBoolean("autoCopyNick", s.autoCopyNick)
            .apply()
    }
}
