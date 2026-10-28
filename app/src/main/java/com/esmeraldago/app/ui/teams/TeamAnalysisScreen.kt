package com.esmeraldago.app.ui.teams

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.RankRow
import com.esmeraldago.app.ui.common.SearchField
import com.esmeraldago.app.ui.common.SpeciesPickerDialog
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.reco.TeamAnalyzer
import com.esmeraldago.core.reco.TeamMember

/**
 * Lugar del equipo: un Pokémon de la caja ("o:uid", o "o:uid@especie" si juega evolucionado) o una especie de la
 * Pokédex ("s:id").
 */
private fun memberOf(token: String?, idx: GameIndex, league: League, owned: List<OwnedPokemon>): TeamMember? {
    token ?: return null
    return when {
        token.startsWith("o:") -> {
            val uid = token.drop(2).substringBefore('@')
            val p = owned.firstOrNull { it.uid == uid } ?: return null
            val evolved = token.substringAfter('@', "").takeIf { it.isNotEmpty() }?.let { idx[it] }
            val s = evolved ?: idx[p.battleId] ?: idx[p.speciesId] ?: return null
            val rec = TeamAnalyzer.recommended(idx, league, s)
            // Evolucionado, los ataques de la caja ya no valen: los recomendados.
            if (evolved != null && evolved.id != p.battleId) rec
            else TeamMember(s, p.fast ?: rec.fast, p.chargedMoves.ifEmpty { rec.charged })
        }
        token.startsWith("s:") -> idx[token.drop(2)]?.let { TeamAnalyzer.recommended(idx, league, it) }
        else -> null
    }
}

/**
 * Analizar un equipo de 3 para una liga: debilidades que comparten, tipos que no cubren con sus ataques y rivales
 * del meta que les ganan a dos o más sin que nadie les gane (según las partidas clave de PvPoke).
 * [initial]: lugares separados por coma ("o:uid,s:azumarill"), por ejemplo el equipo sugerido en Equipos.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamAnalysisScreen(onBack: () -> Unit, initial: String? = null, initialLeague: String? = null) {
    val owned by LocalContainer.current.collection.items.collectAsState()
    var league by rememberSaveable { mutableStateOf(League.entries.firstOrNull { it.key == initialLeague } ?: League.GREAT) }
    var slots by rememberSaveable { mutableStateOf(initial?.split(',')?.take(3)?.plus(List(3) { "" })?.take(3) ?: List(3) { "" }) }
    var picking by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Analizar equipo", onBack)
        PixelTabs(League.entries, league, { it.label }, { league = it }, Modifier.padding(vertical = 6.dp))
        WithGame { idx, _ ->
            val members = slots.map { memberOf(it.ifEmpty { null }, idx, league, owned) }
            val team = members.filterNotNull()
            val analysis = remember(team, league, idx) { if (team.size >= 2) TeamAnalyzer.analyze(idx, league, team) else null }
            LazyColumn(Modifier.fillMaxSize()) {
                items(3) { i ->
                    val m = members[i]
                    if (m == null) {
                        WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).heightIn(min = 64.dp).clickable { picking = i }, padding = 6.dp) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
                                GbaText("Elegir Pokémon ${i + 1}", size = 15.sp, color = Pal.Muted)
                            }
                        }
                    } else {
                        val rank = analysis?.ranks?.getOrNull(team.indexOf(m)) ?: idx.pvpRank(league, m.species.id)?.first
                        RankRow(rank ?: 0, m.species, onClick = { picking = i },
                            title = slots[i].takeIf { it.startsWith("o:") }?.let { t -> owned.firstOrNull { it.uid == t.drop(2).substringBefore('@') }?.nickname }
                                ?: m.species.displayName,
                            trailing = {
                                Box(Modifier.heightIn(min = 44.dp).clickable { slots = slots.toMutableList().also { it[i] = "" } }.padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center) { GbaText("×", size = 18.sp, color = Pal.Muted) }
                            }) {
                            Column {
                                if (rank == null) GbaText("Fuera del ranking de la ${league.label}.", size = 11.sp, color = Pal.Muted)
                                (listOfNotNull(m.fast) + m.charged).mapNotNull { idx.move(it) }.forEach { MoveLabel(it, m.species, 12) }
                            }
                        }
                    }
                }
                if (analysis == null) {
                    item { GbaText("Elegir al menos dos Pokémon.", size = 13.sp, color = Pal.Muted, modifier = Modifier.padding(12.dp)) }
                    return@LazyColumn
                }
                item {
                    WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        SectionTitle("Debilidades compartidas")
                        if (analysis.sharedWeaknesses.isEmpty()) GbaText("Ningún tipo les pega supereficaz a dos sin que otro lo resista.", size = 12.sp)
                        else {
                            GbaText("Les pegan supereficaz a dos o más y nadie del equipo los resiste:", size = 12.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp)) {
                                analysis.sharedWeaknesses.forEach { TypeBadge(it) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        SectionTitle("Sin ataque supereficaz")
                        if (analysis.uncovered.isEmpty()) GbaText("Los ataques del equipo les pegan supereficaz a todos los tipos.", size = 12.sp)
                        else {
                            GbaText("Ningún ataque del equipo les pega supereficaz a estos tipos:", size = 12.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp)) {
                                analysis.uncovered.forEach { TypeBadge(it) }
                            }
                        }
                    }
                }
                item {
                    SectionTitle("Amenazas", Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    GbaText(
                        "Rivales del top ${analysis.metaSize} que, según las partidas clave de PvPoke, les ganan a dos o más y a los que nadie del equipo les gana.",
                        size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    if (analysis.threats.isEmpty()) GbaText("Ninguno.", size = 13.sp, modifier = Modifier.padding(12.dp))
                }
                items(analysis.threats, key = { it.species.id }) { t ->
                    RankRow(t.rank, t.species) {
                        GbaText("Les gana a: " + t.beats.joinToString(", ") { team[it].species.displayName }, size = 12.sp)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }

            picking?.let { i ->
                MemberPicker(idx, league, owned,
                    onDismiss = { picking = null },
                    onPick = { token -> slots = slots.toMutableList().also { it[i] = token }; picking = null })
            }
        }
    }
}

/** Elegir de la caja o de la Pokédex. */
@Composable
private fun MemberPicker(idx: GameIndex, league: League, owned: List<OwnedPokemon>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var fromDex by remember { mutableStateOf(owned.isEmpty()) }
    if (fromDex) {
        SpeciesPickerDialog(idx, "Elegir de la Pokédex", filter = { !it.mega }, onDismiss = onDismiss) { onPick("s:${it.id}") }
        return
    }
    var query by remember { mutableStateOf("") }
    val sorted = remember(owned, league, idx) {
        owned.mapNotNull { p -> (idx[p.battleId] ?: idx[p.speciesId])?.let { p to it } }
            .sortedBy { (_, s) -> idx.pvpRank(league, s.id)?.first ?: Int.MAX_VALUE }
    }
    val shown = remember(query, sorted) {
        val q = query.trim().lowercase()
        (if (q.isEmpty()) sorted else sorted.filter { (p, s) -> s.name.lowercase().contains(q) || p.nickname?.lowercase()?.contains(q) == true }).take(200)
    }
    Dialog(onDismissRequest = onDismiss) {
        WindowBox(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GbaText("Elegir de la caja", size = 17.sp, modifier = Modifier.weight(1f))
                PixelButton("POKéDEX", { fromDex = true }, color = Pal.Muted)
            }
            Spacer(Modifier.height(6.dp))
            SearchField(query, { query = it }, "Buscar")
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.first.uid }) { (p, s) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick("o:${p.uid}") }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sprite(p, s, 40.dp)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            GbaText(p.nickname ?: s.displayName, size = 14.sp)
                            GbaText("PC ${p.cp}" + (idx.pvpRank(league, s.id)?.first?.let { " · #$it" } ?: ""), size = 12.sp, color = Pal.Muted)
                        }
                    }
                }
            }
        }
    }
}
