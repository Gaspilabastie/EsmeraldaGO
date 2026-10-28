package com.esmeraldago.app.ui.teams

import com.esmeraldago.app.ui.common.WeaknessBadges
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.collection.costText
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.RankRow
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.common.fmt1
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.LoadingBox
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Types
import com.esmeraldago.core.reco.PvpCandidate

private val ROLES = listOf("Abridor", "Cambio", "Cierre")

@Composable
fun TeamsScreen(
    onBack: () -> Unit, onOwned: (String) -> Unit, onAdvice: () -> Unit = {}, onScan: () -> Unit = {},
    /** Abre el análisis con la liga y los lugares ("o:uid" separados por coma). */
    onAnalyze: (String, String) -> Unit = { _, _ -> },
) {
    val owned by LocalContainer.current.collection.items.collectAsState()
    var league by rememberSaveable { mutableStateOf(League.GREAT) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Equipos PvP", onBack)
        PixelTabs(League.entries, league, { it.label }, { league = it }, Modifier.padding(vertical = 6.dp))
        WithGame { idx, reco ->
            val (data, _) = rememberComputed(league, owned, reco) {
                reco.pvpTeam(league, owned) to reco.pvpCandidates(league, owned).take(25)
            }
            val (team, others) = data ?: return@WithGame LoadingBox()
            if (team.isEmpty()) {
                DialogBox("Sin Pokémon aptos para la ${league.label}.", Modifier.padding(8.dp))
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("ESCANEAR", onScan)
                    PixelButton("VER QUÉ ATRAPAR", onAdvice, color = Pal.Gold)
                    PixelButton("ANALIZAR UN EQUIPO", { onAnalyze(league.key, "") }, color = Pal.Muted)
                }
                return@WithGame
            }
            val rest = others.filter { o -> team.none { it.owned.uid == o.owned.uid && it.species.id == o.species.id } }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Con la especie que juega (si hay que evolucionarlo, la evolución).
                        PixelButton("ANALIZAR", { onAnalyze(league.key, team.joinToString(",") { "o:${it.owned.uid}@${it.species.id}" }) },
                            Modifier.weight(1f))
                        PixelButton("ARMAR OTRO", { onAnalyze(league.key, "") }, Modifier.weight(1f), color = Pal.Muted)
                    }
                }
                itemsIndexed(team, key = { _, c -> "t|${c.owned.uid}|${c.species.id}" }) { i, c -> CandidateRow(c, ROLES.getOrElse(i) { "" }, idx, onOwned) }
                if (team.size < 3) item {
                    Column {
                        DialogBox(if (team.size == 2) "Falta un Pokémon para completar el equipo." else "Faltan Pokémon para completar el equipo.", Modifier.padding(8.dp))
                        Row(Modifier.padding(horizontal = 8.dp)) { PixelButton("VER QUÉ ATRAPAR", onAdvice, color = Pal.Gold) }
                    }
                }
                if (rest.isNotEmpty()) {
                    item { SectionTitle("Otras opciones", Modifier.padding(horizontal = 8.dp)) }
                    itemsIndexed(rest, key = { _, c -> "o|${c.owned.uid}|${c.species.id}" }) { _, c -> CandidateRow(c, "", idx, onOwned) }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(c: PvpCandidate, role: String, idx: GameIndex, onOwned: (String) -> Unit) {
    val p = c.owned
    RankRow(c.metaRank, c.species, onClick = { onOwned(p.uid) },
        title = (if (role.isNotEmpty()) "$role: " else "") + (p.nickname ?: c.species.displayName) +
            if (c.needsEvolve) " (evolucionar)" else "",
        shadowTint = p.shadow,
        trailing = { GbaText(fmt1(c.value), size = 15.sp) }) {
        Column {
            GbaText("IV ${p.ivAtk}/${p.ivDef}/${p.ivSta}${if (!p.ivExact) " (estim.)" else ""} · rank #${c.iv.rank} · Nv ${fmtLevel(c.iv.level)} → PC ${c.iv.cp}", size = 11.sp)
            c.moveset.mapNotNull { idx.move(it) }.forEach { m ->
                MoveLabel(m, c.species, 12)
                if (m.id in c.missingMoves) {
                    GbaText("  ↳ por aprender: " + if (c.species.isElite(m.id)) "MT Élite" else "MT de ataque", size = 11.sp, color = Pal.Warn)
                }
            }
            if (c.needsEvolve) GbaText("Los ataques se definen al evolucionar.", size = 11.sp, color = Pal.Muted)
            if (!c.cost.isZero) GbaText("Subir: ${costText(c.cost)}", size = 11.sp, color = Pal.Muted)
            WeaknessBadges(c.species.types, Modifier.padding(top = 2.dp))
        }
    }
}
