package com.esmeraldago.app.ui.raids

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.RankRow
import com.esmeraldago.app.ui.common.SpeciesPickerDialog
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.common.fmt1
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.HpBar
import com.esmeraldago.app.ui.theme.LoadingBox
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.Tag
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.calc.PveResult
import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.model.Types

@Composable
fun RaidsScreen(onBack: () -> Unit, onBoss: (String, String) -> Unit) {
    val container = LocalContainer.current
    val raids by container.raids.raids.collectAsState()
    val error by container.raids.error.collectAsState()
    val loading by container.raids.loading.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var pickTier by rememberSaveable { mutableStateOf(RaidTier.T5) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Incursiones", onBack)
        WithGame { idx, reco ->
            LaunchedEffect(idx) { if (raids == null) container.raids.refresh(idx) }
            // En qué rankings destaca cada jefe actual (él o sus evoluciones).
            val (highlights, _) = rememberComputed(raids, reco) {
                raids.orEmpty().mapNotNull { r -> r.species?.let { r.name to reco.raidHighlights(it) } }.toMap()
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                // Lo principal primero: las incursiones de hoy. "Otro jefe" va al final.
                error?.let { item { DialogBox(it, Modifier.padding(8.dp)) } }
                if (raids == null && error == null) item { LoadingBox("Buscando incursiones") }
                raids?.groupBy { it.tierLabel }?.forEach { (tier, bosses) ->
                    item(key = "h$tier") { SectionTitle(tier, Modifier.padding(horizontal = 8.dp)) }
                    items(bosses, key = { "${it.tierLabel}|${it.name}" }) { r ->
                        val s = r.species
                        if (s == null) {
                            WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                                GbaText("${r.name} (no encontrado en los datos)", size = 13.sp)
                            }
                        } else {
                            RankRow(0, s, onClick = { onBoss(s.id, r.tier.name) }, title = r.name) {
                                GbaText("Débil a: " + Types.weaknesses(s.types).joinToString(", ") { Types.es(it) },
                                    size = 12.sp, color = Pal.Muted)
                                highlights?.get(r.name)?.forEach { h -> GbaText("★ $h", size = 12.sp, color = Pal.Good) }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.padding(8.dp)) {
                        PixelButton(if (loading) "ACTUALIZANDO…" else "ACTUALIZAR", { container.raids.refresh(idx) }, enabled = !loading)
                    }
                }
                item {
                    WindowBox(Modifier.fillMaxWidth().padding(8.dp)) {
                        GbaText("Otro jefe", size = 16.sp)
                        GbaText("Elegí cualquier Pokémon como jefe y su nivel de incursión.", size = 12.sp, color = Pal.Muted)
                        Spacer(Modifier.height(4.dp))
                        PixelTabs(RaidTier.entries, pickTier, { it.label }, { pickTier = it })
                        Spacer(Modifier.height(6.dp))
                        PixelButton("ELEGIR JEFE", { picking = true })
                    }
                }
            }
            if (picking) SpeciesPickerDialog(idx, "Jefe de incursión", filter = { !it.mega || pickTier.name.startsWith("MEGA") },
                onDismiss = { picking = false }) {
                picking = false
                onBoss(it.id, pickTier.name)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CountersScreen(bossId: String, tierName: String, onBack: () -> Unit, onSpecies: (String) -> Unit, onScan: () -> Unit = {}) {
    val container = LocalContainer.current
    val settings by container.settings.state.collectAsState()
    val owned by container.collection.items.collectAsState()
    val tier = runCatching { RaidTier.valueOf(tierName) }.getOrDefault(RaidTier.T5)
    var mine by rememberSaveable { mutableStateOf(false) }
    var shadow by rememberSaveable { mutableStateOf(true) }
    var mega by rememberSaveable { mutableStateOf(true) }
    var elite by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        WithGame { idx, reco ->
            val boss = idx[bossId]
            if (boss == null) {
                TitleBar("Counters", onBack)
                GbaText("Jefe desconocido.", modifier = Modifier.padding(12.dp))
                return@WithGame
            }
            TitleBar("vs ${boss.displayName}", onBack)
            // Tocar al jefe abre su entrada de Pokédex.
            WindowBox(Modifier.fillMaxWidth().padding(8.dp).clickable { onSpecies(boss.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Sprite(boss, 64.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        GbaText("${tier.label} · ${boss.displayName}", size = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { boss.types.forEach { TypeBadge(it, small = true) } }
                        GbaText("Débil a: " + Types.weaknesses(boss.types).joinToString(", ") { Types.es(it) }, size = 12.sp, color = Pal.Muted)
                    }
                    GbaText("▶", size = 14.sp, color = Pal.Muted)
                }
            }
            PixelTabs(listOf(false, true), mine, { if (it) "MIS POKéMON" else "TODOS" }, { mine = it })
            if (!mine) FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip("Oscuros", shadow, { shadow = it })
                ToggleChip("Megas", mega, { mega = it })
                ToggleChip("Legado", elite, { elite = it })
            }
            if (mine) {
                val (team, _) = rememberComputed(boss.id, tier, owned) { reco.raidTeam(boss, tier, owned, size = 12) }
                val list = team ?: return@WithGame LoadingBox()
                if (list.isEmpty()) {
                    DialogBox("Todavía no cargaste Pokémon para armar tu equipo.", Modifier.padding(8.dp))
                    Row(Modifier.padding(horizontal = 8.dp)) { PixelButton("ESCANEAR", onScan) }
                    return@WithGame
                }
                val top = list.first().result.score
                LazyColumn(Modifier.fillMaxSize()) {
                    item { GbaText("Tu mejor equipo de 6 (máx. un Mega), según su nivel e IV reales:", size = 12.sp,
                        color = Pal.Muted, modifier = Modifier.padding(horizontal = 10.dp)) }
                    itemsIndexed(list, key = { _, o -> o.owned.uid + o.asMega }) { i, o ->
                        val p = o.owned
                        CounterRow(i + 1, o.result, top, onSpecies, topDps = list.first().result.dps,
                            title = (p.nickname ?: o.result.species.displayName) + if (o.asMega) " → Mega" else "",
                            extra = "PC ${p.cp} · Nv ${fmtLevel(p.level)} · IV ${p.ivAtk}/${p.ivDef}/${p.ivSta}" +
                                if (i >= 6) " (suplente)" else "",
                            assumed = o.assumedMoves)
                    }
                }
            } else {
                val (counters, busy) = rememberComputed(boss.id, tier, shadow, mega, elite, settings.rankLevel) {
                    PveCalc.counters(boss, tier, idx, settings.rankLevel, shadow, mega, elite, limit = 40)
                }
                val list = counters ?: return@WithGame LoadingBox()
                val top = list.firstOrNull()?.score ?: 1.0
                if (busy) GbaText("Recalculando…", size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 10.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(list, key = { _, r -> "${r.species.id}|${r.fast.id}|${r.charged.id}" }) { i, r ->
                        CounterRow(i + 1, r, top, onSpecies, topDps = list.first().dps, extra = "Nv ${fmtLevel(r.level)} 15/15/15")
                    }
                }
            }
        }
    }
}

@Composable
private fun CounterRow(
    rank: Int, r: PveResult, top: Double, onSpecies: (String) -> Unit, topDps: Double = 0.0,
    title: String = r.species.displayName, extra: String = "", assumed: Boolean = false,
) {
    RankRow(rank, r.species, onClick = { onSpecies(r.species.id) }, title = title,
        trailing = { com.esmeraldago.app.ui.common.RankValue(r.score, top, fmt1(r.score)) }) {
        Column {
            MoveLabel(r.fast, r.species)
            MoveLabel(r.charged, r.species)
            if (assumed) Tag("ATAQUES ESTIMADOS", Pal.Gold)
            GbaText("DPS ${fmt1(r.dps)}${com.esmeraldago.app.ui.common.pctOfTop(r.dps, topDps)} · TDO ${r.tdo.toInt()}" + if (extra.isNotEmpty()) "\n$extra" else "", size = 11.sp, color = Pal.Muted)
        }
    }
}
