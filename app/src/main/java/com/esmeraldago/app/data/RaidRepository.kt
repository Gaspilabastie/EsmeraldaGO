package com.esmeraldago.app.data

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.Species
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
private data class ScrapedRaid(val name: String, val tier: String)

/** [order]: para listar de mayor a menor (5★/Mega/Oscuras primero). */
data class CurrentRaid(val name: String, val tierLabel: String, val tier: RaidTier, val species: Species?, val order: Int)

/** Jefes de incursión actuales (ScrapedDuck / LeekDuck). */
class RaidRepository(private val scope: CoroutineScope) {
    private val _raids = MutableStateFlow<List<CurrentRaid>?>(null)
    val raids: StateFlow<List<CurrentRaid>?> = _raids.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun refresh(idx: GameIndex) = scope.launch(Dispatchers.IO) {
        if (_loading.value) return@launch
        _loading.value = true
        _error.value = null
        runCatching {
            val text = GameDataSources.open(GameDataSources.CURRENT_RAIDS).use { it.reader().readText() }
            GameDataSources.json.decodeFromString(ListSerializer(ScrapedRaid.serializer()), text).map { r ->
                val shadow = r.tier.contains("Shadow", true) || r.name.startsWith("Shadow ")
                val baseName = r.name.removePrefix("Shadow ").trim()
                val found = idx.findByName(baseName)
                val species = if (shadow && found != null) idx.shadowOf(found) ?: found else found
                val tier = tierOf(r.tier, shadow, species)
                CurrentRaid(displayName(r.name), tierLabel(tier), tier, species, order(tier))
            }.sortedBy { it.order }
        }.onSuccess { _raids.value = it }
            .onFailure { _error.value = "No se pudieron descargar las incursiones: ${it.message}" }
        _loading.value = false
    }

    private fun tierOf(label: String, shadow: Boolean, s: Species?): RaidTier {
        val l = label.lowercase()
        // "1-Star Raids", "Shadow 3-Star Raids"…: buscar el número de estrellas en cualquier parte.
        val stars = Regex("""(\d)\s*-?\s*star""").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 5
        return when {
            shadow -> when (stars) { 1 -> RaidTier.SHADOW_T1; 3 -> RaidTier.SHADOW_T3; else -> RaidTier.SHADOW_T5 }
            l.contains("mega") && (s?.legendary == true || s?.mythical == true) -> RaidTier.MEGA_LEGENDARY
            l.contains("mega") || l.contains("primal") -> RaidTier.MEGA
            stars == 1 -> RaidTier.T1
            stars == 3 -> RaidTier.T3
            else -> RaidTier.T5
        }
    }

    private fun tierLabel(t: RaidTier) = when (t) {
        RaidTier.T1 -> "NIVEL 1"; RaidTier.T3 -> "NIVEL 3"; RaidTier.T5 -> "NIVEL 5"
        RaidTier.MEGA -> "MEGA"; RaidTier.MEGA_LEGENDARY -> "MEGA LEGENDARIAS"
        RaidTier.SHADOW_T1 -> "OSCURAS NIVEL 1"; RaidTier.SHADOW_T3 -> "OSCURAS NIVEL 3"; RaidTier.SHADOW_T5 -> "OSCURAS NIVEL 5"
    }

    private fun order(t: RaidTier) = when (t) {
        RaidTier.T5 -> 0; RaidTier.MEGA_LEGENDARY -> 1; RaidTier.SHADOW_T5 -> 2; RaidTier.MEGA -> 3
        RaidTier.SHADOW_T3 -> 4; RaidTier.T3 -> 5; RaidTier.SHADOW_T1 -> 6; RaidTier.T1 -> 7
    }

    /** "Shadow Gastly" -> "Gastly Oscuro" para no mezclar idiomas. */
    private fun displayName(n: String) = if (n.startsWith("Shadow ")) n.removePrefix("Shadow ") + " Oscuro" else n
}
