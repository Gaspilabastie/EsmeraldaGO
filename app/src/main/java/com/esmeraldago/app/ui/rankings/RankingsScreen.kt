package com.esmeraldago.app.ui.rankings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.RankRow
import com.esmeraldago.app.ui.common.RankValue
import com.esmeraldago.app.ui.common.pctOfTop
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.common.fmt1
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.HpBar
import com.esmeraldago.app.ui.theme.LoadingBox
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.TypePicker
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Types

private enum class Tab(val label: String) { PVE("PvE"), PVP("PvP"), MAX("Dinamax") }

/** Convención de DialgaDex: tiers cada 10% respecto del #1. */
/** El tier se mide contra el #1 del ranking COMPLETO (sin filtros): filtrar no sube de tier a nadie. */
private const val TIER_LEGEND = "Tier (% del #1 del ranking completo, sin filtros, estilo GO Hub): S ≥95 · A+ ≥90 · A ≥85 · B+ ≥80 · B ≥75 · C ≥70 · D <70"

/**
 * [focus] ("pve:fire:charizard", "pvp:GREAT:medicham", "max:fire:charizard") abre la pestaña y el tipo o liga
 * indicados, baja hasta ese Pokémon y lo resalta: así se llega desde su entrada de Pokédex.
 */
@Composable
fun RankingsScreen(onBack: () -> Unit, onSpecies: (String) -> Unit, focus: String? = null) {
    val f = focus?.split(":")
    val focusId = f?.getOrNull(2)
    // Todo el estado de filtros vive acá: cambiar de pestaña no lo reinicia.
    var tab by rememberSaveable { mutableStateOf(when (f?.getOrNull(0)) { "pvp" -> Tab.PVP; "max" -> Tab.MAX; else -> Tab.PVE }) }
    var pveType by rememberSaveable { mutableStateOf(f?.takeIf { it[0] == "pve" }?.getOrNull(1) ?: "fighting") }
    var pveShadow by rememberSaveable { mutableStateOf(true) }
    var pveMega by rememberSaveable { mutableStateOf(true) }
    var pveElite by rememberSaveable { mutableStateOf(true) }
    var league by rememberSaveable {
        mutableStateOf(f?.takeIf { it[0] == "pvp" }?.getOrNull(1)?.let { runCatching { League.valueOf(it) }.getOrNull() } ?: League.GREAT)
    }
    var pvpShadow by rememberSaveable { mutableStateOf(true) }
    var pvpAllowElite by rememberSaveable { mutableStateOf(true) }
    var maxType by rememberSaveable { mutableStateOf(f?.takeIf { it[0] == "max" }?.getOrNull(1) ?: "fighting") }
    var maxTanks by rememberSaveable { mutableStateOf(false) }
    // "No Budget": legendarios, singulares y ultraentes. Prendido = se ven (como los demás chips).
    var nonBudget by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Rankings", onBack)
        Spacer(Modifier.height(6.dp))
        PixelTabs(Tab.entries, tab, { it.label }, { tab = it })
        Spacer(Modifier.height(4.dp))
        WithGame { idx, _ ->
            when (tab) {
                Tab.PVE -> PveTab(idx, onSpecies, focusId, pveType, { pveType = it }, pveShadow, { pveShadow = it },
                    pveMega, { pveMega = it }, pveElite, { pveElite = it }, nonBudget, { nonBudget = it })
                Tab.PVP -> PvpTab(idx, onSpecies, focusId, league, { league = it }, pvpShadow, { pvpShadow = it }, pvpAllowElite, { pvpAllowElite = it }, nonBudget, { nonBudget = it })
                Tab.MAX -> MaxTab(idx, onSpecies, focusId, maxType, { maxType = it }, maxTanks, { maxTanks = it }, nonBudget, { nonBudget = it })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PveTab(
    idx: GameIndex, onSpecies: (String) -> Unit, focusId: String?,
    type: String, setType: (String) -> Unit,
    shadow: Boolean, setShadow: (Boolean) -> Unit,
    mega: Boolean, setMega: (Boolean) -> Unit,
    elite: Boolean, setElite: (Boolean) -> Unit,
    nonBudget: Boolean, setNonBudget: (Boolean) -> Unit,
) {
    val settings by LocalContainer.current.settings.state.collectAsState()
    val level = settings.rankLevel
    // Se calcula el ranking completo una vez y los filtros solo ocultan filas: así el tier de cada uno se mide
    // siempre contra el mismo #1 (el del ranking sin filtros) y no "sube" al apagar categorías.
    val (full, busy) = rememberComputed(idx, type, level) {
        PveCalc.topAttackers(type, idx, level, includeShadow = true, includeMega = true, includeElite = true, limit = 400)
    }
    val results = full?.filter { r ->
        (shadow || !r.species.shadow) && (mega || !r.species.mega) && (elite || !r.usesElite) && (nonBudget || !r.species.nonBudget)
    }?.let { takeWithFocus(it, 60, focusId) { r -> r.species.id } }

    val list = results.orEmpty()
    val listState = rememberFocusScroll(list.indexOfFirst { it.species.id == focusId })
    val top = full?.firstOrNull()?.score ?: 1.0
    // El % de DPS sí es contra el #1 que se ve (el #1 de la lista siempre muestra 100%).
    val topDps = list.firstOrNull()?.dps ?: 1.0
    // Los filtros son los primeros ítems de la lista: se van con el scroll (en pantallas chicas no tapan los resultados).
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "filters") {
            Column {
                TypePicker(type, setType)
                FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("Oscuros", shadow, setShadow)
                    ToggleChip("Megas", mega, setMega)
                    ToggleChip("Legado", elite, setElite)
                    ToggleChip("No Budget", nonBudget, setNonBudget)
                }
                GbaText(
                    "Atacantes ${Types.es(type)} · Nv ${fmtLevel(level)} 15/15/15 · contra jefe débil a ${Types.es(type)}" +
                        (if (busy) "  …" else "") + "\n$TIER_LEGEND",
                    size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 10.dp),
                )
                if (results == null) LoadingBox()
            }
        }
        itemsIndexed(list, key = { _, r -> "${r.species.id}|${r.fast.id}|${r.charged.id}" }) { i, r ->
            RankRow(i + 1, r.species, onClick = { onSpecies(r.species.id) }, highlight = r.species.id == focusId,
                trailing = { RankValue(r.score, top, fmt1(r.score)) }) {
                Column {
                    MoveLabel(r.fast, r.species)
                    MoveLabel(r.charged, r.species)
                    GbaText("DPS ${fmt1(r.dps)}${pctOfTop(r.dps, topDps)} · TDO ${r.tdo.toInt()}", size = 12.sp, color = Pal.Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PvpTab(
    idx: GameIndex, onSpecies: (String) -> Unit, focusId: String?,
    league: League, setLeague: (League) -> Unit,
    shadow: Boolean, setShadow: (Boolean) -> Unit,
    allowElite: Boolean, setAllowElite: (Boolean) -> Unit,
    nonBudget: Boolean, setNonBudget: (Boolean) -> Unit,
) {
    val topScore = idx.pvpRanking(league).firstOrNull()?.score ?: 100.0
    val rows = remember(idx, league, shadow, allowElite, nonBudget) {
        idx.pvpRanking(league).asSequence()
            .mapIndexedNotNull { i, r -> idx[r.id]?.let { Triple(i + 1, r, it) } }
            .filter { (_, _, s) -> shadow || !s.shadow }
            .filter { (_, _, s) -> nonBudget || !s.nonBudget }
            .filter { (_, _, s) -> idx.includeUnreleased || s.released }
            // El puntaje de PvPoke está simulado CON su moveset recomendado: si ese moveset necesita una
            // MT Élite y no querés gastarla, el Pokémon se oculta (sin ese ataque su puntaje sería otro).
            .filter { (_, r, s) -> allowElite || r.moveset.none { s.isElite(it) } }
            .toList().let { takeWithFocus(it, 150, focusId) { r -> r.third.id } }
    }
    val listState = rememberFocusScroll(rows.indexOfFirst { it.third.id == focusId })
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "filters") {
            Column {
                PixelTabs(League.entries, league, { it.label }, setLeague)
                FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("Oscuros", shadow, setShadow)
                    ToggleChip("Legado", allowElite, setAllowElite)
                    ToggleChip("No Budget", nonBudget, setNonBudget)
                }
                GbaText(
                    "Ranking PvPoke (general) · ${league.label} (≤${league.cap} PC)" +
                        (if (!allowElite) "\nSin los que dependen de un ataque de legado (MT Élite)." else "") + "\n$TIER_LEGEND",
                    size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }
        itemsIndexed(rows, key = { _, r -> r.second.id }) { _, (rank, r, s) ->
            RankRow(rank, s, onClick = { onSpecies(s.id) }, highlight = s.id == focusId, trailing = { RankValue(r.score, topScore, fmt1(r.score)) }) {
                Column {
                    r.moveset.mapNotNull { idx.move(it) }.forEach { MoveLabel(it, s) }
                    r.nonEliteMoveset(s)?.takeIf { it.isNotEmpty() }?.let { alt ->
                        GbaText("Sin MT Élite: " + alt.mapNotNull { idx.move(it)?.name }.joinToString(" / ") + " (rinde menos)",
                            size = 11.sp, color = Pal.Muted)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaxTab(
    idx: GameIndex, onSpecies: (String) -> Unit, focusId: String?,
    type: String, setType: (String) -> Unit,
    tanks: Boolean, setTanks: (Boolean) -> Unit,
    nonBudget: Boolean, setNonBudget: (Boolean) -> Unit,
) {
    val settings by LocalContainer.current.settings.state.collectAsState()
    val (full, busy) = rememberComputed(idx, type, tanks, settings.rankLevel, settings.maxMoveLevel) {
        if (tanks) MaxCalc.topTanks(idx, settings.rankLevel, limit = 200)
        else MaxCalc.topAttackers(type, idx, settings.rankLevel, settings.maxMoveLevel, limit = 200)
    }
    val results = full?.filter { nonBudget || !it.species.nonBudget }?.let { takeWithFocus(it, 40, focusId) { r -> r.species.id } }
    val list = results.orEmpty()
    val listState = rememberFocusScroll(list.indexOfFirst { it.species.id == focusId })
    // Tier y barra contra el #1 del ranking completo (sin filtros).
    val top = full?.firstOrNull()?.let { if (tanks) it.bulk else it.maxDamage } ?: 1.0
    val topShown = list.firstOrNull()?.let { if (tanks) it.bulk else it.maxDamage } ?: 1.0
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "filters") {
            Column {
                FlowRow(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("Atacantes", !tanks, { setTanks(!it) })
                    ToggleChip("Tanques", tanks, setTanks)
                    ToggleChip("No Budget", nonBudget, setNonBudget)
                }
                if (!tanks) TypePicker(type, setType)
                GbaText(
                    (if (tanks) "Mayor Def × PS a nivel ${fmtLevel(settings.rankLevel)}"
                    else "Max Attack Nv ${settings.maxMoveLevel} · G-Max y Max exclusivos usan siempre su ataque") +
                        (if (busy) "  …" else "") + "\n$TIER_LEGEND",
                    size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
                if (results == null) LoadingBox()
            }
        }
        itemsIndexed(list, key = { _, r -> "${r.species.id}|${r.gigantamax}|${r.fast.id}" }) { i, r ->
            val value = if (tanks) r.bulk else r.maxDamage
            RankRow(i + 1, r.species, onClick = { onSpecies(r.species.id) }, title = r.displayName, highlight = r.species.id == focusId,
                gmax = r.gigantamax, dynamax = !r.gigantamax,
                trailing = { RankValue(value, top, if (tanks) "${(value / 1000).toInt()}k" else value.toInt().toString(), 72.dp) }) {
                Column {
                    MoveLabel(r.fast, r.species)
                    GbaText("→ ${r.maxMoveName}", size = 12.sp, color = Pal.Dynamax)
                    GbaText((if (tanks) "Aguante" else "Daño") + pctOfTop(value, topShown).replace(" (", "").replace(")", ""),
                        size = 11.sp, color = Pal.Muted)
                }
            }
        }
    }
}

/** Los primeros [n], pero si el Pokémon buscado está más abajo, la lista llega hasta él. */
private fun <T> takeWithFocus(list: List<T>, n: Int, focusId: String?, id: (T) -> String): List<T> {
    val i = if (focusId == null) -1 else list.indexOfFirst { id(it) == focusId }
    return list.take(maxOf(n, i + 1))
}

/** Estado de la lista que, la primera vez que aparece el Pokémon buscado, baja hasta su fila (la 0 son los filtros). */
@Composable
private fun rememberFocusScroll(index: Int): androidx.compose.foundation.lazy.LazyListState {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    var done by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(index) {
        if (!done && index >= 0) { state.scrollToItem(index + 1); done = true }
    }
    return state
}