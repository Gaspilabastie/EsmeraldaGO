package com.esmeraldago.app.data

import android.content.Context
import com.esmeraldago.core.data.GameDataSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Un evento de Pokémon GO (LeekDuck, vía ScrapedDuck). [featured]: los Pokémon que el evento destaca (los del Día
 * de la Comunidad, la Hora Destacada o los jefes de las incursiones), por nombre en inglés.
 */
data class GoEvent(
    val id: String, val name: String, val type: String, val heading: String, val link: String,
    val start: Instant?, val end: Instant?, val featured: List<String>,
)

/**
 * Eventos de ScrapedDuck (scraper de LeekDuck, actualizado varias veces por día). Se guarda la última copia: si un
 * día no hay red o la fuente falla, se ve lo último que se bajó. Créditos: LeekDuck y ScrapedDuck (en Opciones).
 */
class EventsRepository(context: Context, private val scope: CoroutineScope) {
    private val cache = File(context.filesDir, "events.json")
    private val _events = MutableStateFlow<List<GoEvent>?>(null)
    val events: StateFlow<List<GoEvent>?> = _events.asStateFlow()
    private var refreshing = false

    init {
        scope.launch(Dispatchers.IO) { runCatching { if (cache.exists()) _events.value = parse(cache.readText()) } }
    }

    fun refresh() = scope.launch(Dispatchers.IO) {
        if (refreshing) return@launch
        refreshing = true
        runCatching {
            val text = GameDataSources.open(URL).use { it.reader().readText() }
            val parsed = parse(text)
            cache.writeText(text)
            _events.value = parsed
        }
        refreshing = false
    }

    companion object {
        const val URL = "https://raw.githubusercontent.com/bigfoott/ScrapedDuck/data/events.json"

        fun parse(text: String): List<GoEvent> =
            GameDataSources.json.parseToJsonElement(text).jsonArray.mapNotNull { runCatching { event(it.jsonObject) }.getOrNull() }

        private fun JsonObject.str(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

        private fun event(o: JsonObject): GoEvent {
            val extra = o["extraData"] as? JsonObject
            fun names(e: JsonElement?) = (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }
            val featured = buildList {
                (extra?.get("communityday") as? JsonObject)?.let { addAll(names(it["spawns"])) }
                (extra?.get("spotlight") as? JsonObject)?.let { s -> addAll(names(s["list"]).ifEmpty { listOfNotNull(s.str("name")) }) }
                (extra?.get("raidbattles") as? JsonObject)?.let { addAll(names(it["bosses"])) }
            }.distinct()
            return GoEvent(
                id = o.str("eventID") ?: o.str("name").orEmpty(), name = o.str("name").orEmpty(),
                type = o.str("eventType").orEmpty(), heading = o.str("heading").orEmpty(), link = o.str("link").orEmpty(),
                start = time(o.str("start")), end = time(o.str("end")), featured = featured,
            )
        }

        /** "…Z" es hora UTC; sin zona es hora local de cada jugador (los eventos de GO son "a las 14 h locales"). */
        fun time(s: String?): Instant? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                if (s.endsWith("Z")) Instant.parse(s) else LocalDateTime.parse(s.removeSuffix(".000")).atZone(ZoneId.systemDefault()).toInstant()
            }.getOrNull()
        }
    }
}
