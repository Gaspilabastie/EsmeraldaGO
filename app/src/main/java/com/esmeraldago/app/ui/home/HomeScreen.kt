package com.esmeraldago.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.data.CurrentRaid
import com.esmeraldago.app.data.GoEvent
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.SearchField
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.SpriteUrl
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.SmallPixelShape
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.reco.Recommender
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Sprites de objetos del juego (PokeAPI): se bajan y quedan en caché, no están en el repositorio. */
private const val ITEMS = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/items/"

private class Shortcut(val label: String, val route: String, val item: String)

private val MAIN = listOf(
    Shortcut("RANKINGS", "rankings", "vs-seeker"),
    Shortcut("TIPOS", "types", "tm-case"),
    Shortcut("POKéDEX", "pokedex", "poke-ball"),
)

private val MORE = listOf(
    Shortcut("MI CAJA", "collection", "great-ball"),
    Shortcut("INCURSIONES", "raids", "wishing-piece"),
    Shortcut("DINAMAX", "max", "dynamax-band"),
    Shortcut("EQUIPOS", "teams", "vs-recorder"),
    Shortcut("QUÉ ATRAPAR", "advice", "razz-berry"),
    Shortcut("OPCIONES", "settings", "pal-pad"),
)

/**
 * Inicio en forma de tablero. Arriba, lo que más se usa: "¿me sirve este Pokémon?" (buscar o escanear) y los
 * accesos a rankings y tipos. Abajo, lo destacado de hoy: incursiones que valen la pena, eventos importantes y
 * salvajes del evento para atrapar.
 */
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val collectionError by container.collection.error.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TitleBar("EsmeraldaGO") {
            Box(Modifier.size(44.dp).clickable(onClickLabel = "Opciones") { onOpen("settings") }, contentAlignment = Alignment.Center) {
                SpriteUrl("${ITEMS}pal-pad.png", 36.dp, "Opciones")
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            collectionError?.let { DialogBox(it) }
            WithGame { idx, reco ->
                LookupCard(idx, onOpen)
                ShortcutTiles(onOpen)
                RaidsCard(reco, onOpen)
                EventsCard(idx, reco, onOpen)
            }
        }
    }
}

// ---------------------------------------------------------------- ¿me sirve?

/** Buscar un Pokémon por nombre (inglés o español) y abrir su ficha, o escanearlo desde el juego. */
@Composable
private fun LookupCard(idx: GameIndex, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    WindowBox(Modifier.fillMaxWidth()) {
        GbaText("¿TE SIRVE ESTE POKéMON?", size = 14.sp, color = Pal.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            SearchField(query, { query = it }, "Buscá por nombre", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            // Escanear: el botón grande (lo más usado junto con buscar).
            Column(
                Modifier.background(Pal.Gold, SmallPixelShape).border(2.dp, Pal.FrameOuter, SmallPixelShape)
                    .clickable(onClickLabel = "Escanear") { onOpen("scan") }.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SpriteUrl("${ITEMS}devon-scope.png", 32.dp, "Escanear")
                GbaText("ESCANEAR", size = 11.sp)
            }
        }
        val q = query.trim().lowercase()
        if (q.length >= 2) {
            val hits = idx.released.asSequence()
                .filter { !it.shadow && !it.mega && idx.cosmeticOf[it.id] == null && it.id !in GameIndex.BATTLE_FORMS && it.id !in GameIndex.ALIAS_FORMS }
                .filter { it.name.lowercase().contains(q) || it.nameEs?.lowercase()?.contains(q) == true }
                .sortedBy { if (it.name.lowercase().startsWith(q) || it.nameEs?.lowercase()?.startsWith(q) == true) 0 else 1 }
                .take(5).toList()
            if (hits.isEmpty()) GbaText("No encontré \"$query\".", size = 13.sp, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp))
            hits.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp).clickable { onOpen("species/${s.id}") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sprite(s, 40.dp)
                    Spacer(Modifier.width(6.dp))
                    GbaText(s.displayName, size = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    GbaText("▶", size = 13.sp, color = Pal.Muted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- accesos

@Composable
private fun ShortcutTiles(onOpen: (String) -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MAIN.forEach { Tile(it.label, it.item, Modifier.weight(1f)) { onOpen(it.route) } }
        Tile(if (more) "MENOS" else "MÁS", "town-map", Modifier.weight(1f), selected = more) { more = !more }
    }
    if (more) {
        MORE.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { Tile(it.label, it.item, Modifier.weight(1f)) { onOpen(it.route) } }
            }
        }
    }
}

/** Acceso con el sprite de un objeto del juego y su nombre (hasta dos renglones, nunca cortado). */
@Composable
private fun Tile(label: String, item: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    WindowBox(modifier.heightIn(min = 84.dp).clickable(onClickLabel = label, onClick = onClick), padding = 6.dp,
        background = if (selected) Pal.Select else Pal.Window) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            SpriteUrl("$ITEMS$item.png", 36.dp, label)
            GbaText(label, size = 12.sp, maxLines = 2, align = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------- incursiones

private fun shortTier(t: RaidTier) = when (t) {
    RaidTier.T1 -> "1★"; RaidTier.T3 -> "3★"; RaidTier.T5 -> "5★"
    RaidTier.MEGA, RaidTier.MEGA_LEGENDARY -> "MEGA"
    RaidTier.SHADOW_T1 -> "OSC 1★"; RaidTier.SHADOW_T3 -> "OSC 3★"; RaidTier.SHADOW_T5 -> "OSC 5★"
}

/** Jefes de hoy (sin los de nivel 1): ★ los que conviene atrapar, con el porqué del mejor. */
@Composable
private fun RaidsCard(reco: Recommender, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val raids by container.raids.raids.collectAsState()
    val game by container.game.state.collectAsState()
    LaunchedEffect(game) {
        val idx = (game as? com.esmeraldago.app.data.GameState.Ready)?.idx ?: return@LaunchedEffect
        if (raids == null) container.raids.refresh(idx)
    }
    val top = raids.orEmpty().filter { it.species != null && it.tier != RaidTier.T1 && it.tier != RaidTier.SHADOW_T1 }.take(8)
    if (top.isEmpty()) return
    val (highlights, _) = rememberComputed(top.map { it.name }) { top.associate { it.name to reco.raidHighlights(it.species!!, 1) } }
    WindowBox(Modifier.fillMaxWidth()) {
        CardTitle("INCURSIONES DE HOY", "wishing-piece") { onOpen("raids") }
        // Grilla de 4 columnas iguales: 8 jefes quedan en dos filas de 4.
        top.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { r -> RaidCell(r, highlights?.get(r.name).orEmpty().isNotEmpty(), Modifier.weight(1f)) { onOpen("counters/${r.species!!.id}/${r.tier.name}") } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        top.firstOrNull { highlights?.get(it.name).orEmpty().isNotEmpty() }?.let { r ->
            GbaText("★ ${r.name}: ${highlights!!.getValue(r.name).first()}", size = 12.sp, color = Pal.Good, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RaidCell(r: CurrentRaid, star: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Sprite(r.species!!, 60.dp)
        GbaText((if (star) "★ " else "") + shortTier(r.tier), size = 11.sp, color = if (star) Pal.Good else Pal.Muted, maxLines = 1)
    }
}

// ---------------------------------------------------------------- eventos

/** Tipos de evento que se muestran, de más a menos importante (GBL, pases, drops y safaris de ciudad no). */
private val EVENT_PRIORITY = listOf(
    "community-day", "pokemon-go-tour", "raid-day", "raid-hour", "raid-battles", "pokemon-spotlight-hour",
    "max-battles", "max-mondays", "event", "wild-area",
)

/**
 * Los eventos sin lista de Pokémon (Hora de incursiones, Día de incursiones…) lo dicen en el nombre:
 * "Giratina (Origin Forme) Raid Hour", "Chandelure Super Mega Raid Day". Se busca la especie nombrada; entre
 * sus formas, la que el nombre menciona ("Origin"), y "Mega X" si es una mega.
 */
private fun speciesInName(name: String, idx: GameIndex): List<Species> {
    val words = Regex("[A-Za-z'.\\-]+").findAll(name).map { it.value.lowercase() }.toSet()
    val dexes = idx.byDex.values.mapNotNull { forms ->
        val base = forms.first().name.substringBefore(" (")
        forms.takeIf { Regex("\\b${Regex.escape(base)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) }
    }
    return dexes.mapNotNull { forms ->
        val named = forms.firstOrNull { f -> Regex("""\((.+)\)""").find(f.name)?.groupValues?.get(1)?.split(" ")?.first()?.lowercase() in words }
        val main = named ?: forms.minWithOrNull(compareBy({ idx.cosmeticOf[it.id] != null }, { it.id.length }))
        if (main != null && "mega" in words) idx.megasOf(main).firstOrNull { m -> megaSuffix(m).all { it in words } } ?: main else main
    }
}

/** Las palabras que distinguen una mega en su nombre: "Charizard (Mega X)" → [mega, x]. */
private fun megaSuffix(m: Species) = Regex("""\((.+)\)""").find(m.name)?.groupValues?.get(1)?.lowercase()?.split(" ").orEmpty()

private fun headingEs(e: GoEvent) = when (e.type) {
    "community-day" -> "Día de la Comunidad"; "pokemon-go-tour" -> "GO Tour"; "raid-day" -> "Día de incursiones"
    "raid-hour" -> "Hora de incursiones"; "raid-battles" -> "Incursiones"; "pokemon-spotlight-hour" -> "Hora destacada"
    "max-battles" -> "Batallas Max"; "max-mondays" -> "Lunes Max"; "wild-area" -> "Zona salvaje"; else -> "Evento"
}

private class EventView(val event: GoEvent, val species: List<Species>, val reason: String?, val important: Boolean)

/**
 * Eventos activos o de los próximos 10 días. Importante: Día de la Comunidad, GO Tour, o si alguno de sus
 * Pokémon destacados rinde (top 10 de un tipo o top 30 de una liga). Abajo, los salvajes del evento que vale
 * la pena atrapar.
 */
@Composable
private fun EventsCard(idx: GameIndex, reco: Recommender, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val events by container.events.events.collectAsState()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { container.events.refresh() }
    val (views, _) = rememberComputed(events) {
        val now = Instant.now()
        events.orEmpty()
            .filter { it.type in EVENT_PRIORITY && (it.end == null || it.end.isAfter(now)) && (it.start == null || it.start.isBefore(now.plus(10, ChronoUnit.DAYS))) }
            .map { e ->
                val species = (e.featured.mapNotNull { idx.findByName(it) }.ifEmpty { speciesInName(e.name, idx) }).distinctBy { it.id }
                val reason = species.firstNotNullOfOrNull { s -> reco.raidHighlights(s, 1).firstOrNull()?.let { "${s.displayName}: $it" } }
                EventView(e, species, reason, e.type == "community-day" || e.type == "pokemon-go-tour" || reason != null)
            }
            .sortedWith(compareBy<EventView>({ !it.important }, { EVENT_PRIORITY.indexOf(it.event.type) }, { it.event.start }))
            .take(4)
    }
    val list = views ?: return
    if (list.isEmpty()) return
    WindowBox(Modifier.fillMaxWidth()) {
        CardTitle("EVENTOS", "journal", null)
        list.forEach { v -> EventRow(v) { v.species.firstOrNull()?.let { onOpen("species/${it.id}") } ?: v.event.link.takeIf { it.isNotBlank() }?.let(uriHandler::openUri) } }
        // Salvajes destacados: lo que el Día de la Comunidad o la Hora destacada hace aparecer y rinde.
        val wild = list.filter { it.event.type == "community-day" || it.event.type == "pokemon-spotlight-hour" }
            .flatMap { it.species }.distinctBy { it.id }
            .mapNotNull { s -> reco.raidHighlights(s, 1).firstOrNull()?.let { s to it } }
        if (wild.isNotEmpty()) {
            GbaText("SALVAJES PARA ATRAPAR", size = 13.sp, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp))
            wild.forEach { (s, why) ->
                Row(Modifier.fillMaxWidth().clickable { onOpen("species/${s.id}") }, verticalAlignment = Alignment.CenterVertically) {
                    Sprite(s, 40.dp)
                    Spacer(Modifier.width(6.dp))
                    GbaText("${s.displayName} · $why", size = 12.sp, color = Pal.Good, modifier = Modifier.weight(1f))
                }
            }
        }
        GbaText("Datos: LeekDuck · ScrapedDuck", size = 10.sp, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun EventRow(v: EventView, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            v.species.firstOrNull()?.let { Sprite(it, 48.dp) } ?: SpriteUrl("${ITEMS}journal.png", 32.dp, "Evento")
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            GbaText((if (v.important) "★ " else "") + headingEs(v.event) + " · " + whenText(v.event), size = 12.sp,
                color = if (v.important) Pal.Good else Pal.Muted)
            GbaText(v.event.name, size = 13.sp, maxLines = 2)
            v.reason?.let { GbaText(it, size = 11.sp, color = Pal.Muted, maxLines = 2) }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d/M", Locale("es", "AR"))
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

/** "AHORA (hasta 17:00)", "hoy 14:00", "mañana 18:00", "sáb 21/11 14:00". */
private fun whenText(e: GoEvent): String {
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val start = e.start ?: return ""
    if (!start.isAfter(now)) {
        val end = e.end ?: return "AHORA"
        return if (Duration.between(now, end).toHours() < 24) "AHORA (hasta ${HOUR.format(end.atZone(zone))})"
        else "AHORA (hasta el ${DAY.format(end.atZone(zone))})"
    }
    val s = start.atZone(zone)
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), s.toLocalDate())
    return when (days) {
        0L -> "hoy ${HOUR.format(s)}"
        1L -> "mañana ${HOUR.format(s)}"
        else -> "${DAY.format(s)} ${HOUR.format(s)}"
    }
}

/** Título de una tarjeta con su objeto; si [onClick], toda la línea lleva a la sección completa (▶). */
@Composable
private fun CardTitle(text: String, item: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpriteUrl("$ITEMS$item.png", 28.dp, text)
        Spacer(Modifier.width(4.dp))
        GbaText(text, size = 14.sp, color = Pal.Muted, modifier = Modifier.weight(1f))
        if (onClick != null) GbaText("▶", size = 13.sp, color = Pal.Muted)
    }
}

