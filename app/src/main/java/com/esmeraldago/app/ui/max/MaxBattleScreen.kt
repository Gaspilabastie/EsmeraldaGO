package com.esmeraldago.app.ui.max

import com.esmeraldago.app.ui.common.WeaknessBadges
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.RankRow
import com.esmeraldago.app.ui.common.SpeciesPickerDialog
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.LoadingBox
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.Tag
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.model.Types

private data class MaxRow(val result: MaxCalc.MaxResult, val owned: OwnedPokemon?, val role: String = "")

@Composable
fun MaxBattleScreen(onBack: () -> Unit, onSpecies: (String) -> Unit) {
    val container = LocalContainer.current
    val settings by container.settings.state.collectAsState()
    val owned by container.collection.items.collectAsState()
    var bossId by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var mine by rememberSaveable { mutableStateOf(false) }
    var tanks by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Combate Dinamax", onBack)
        WithGame { idx, reco ->
            val boss = bossId?.let { idx[it] }
            WindowBox(Modifier.fillMaxWidth().padding(8.dp)) {
                if (boss != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Sprite(boss, 64.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        GbaText(boss.displayName, size = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { boss.types.forEach { TypeBadge(it, small = true) } }
                        WeaknessBadges(boss.types, Modifier.padding(top = 3.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                PixelButton(if (boss == null) "ELEGIR JEFE" else "CAMBIAR JEFE", { picking = true }, color = Pal.Dynamax)
            }
            if (picking) SpeciesPickerDialog(idx, "Jefe Dinamax", filter = { (it.dynamax || it.gigantamax) && !it.shadow && !it.mega },
                onDismiss = { picking = false }) { bossId = it.id; picking = false }
            if (boss == null) return@WithGame

            PixelTabs(listOf(false, true), mine, { if (it) "MIS POKéMON" else "TODOS" }, { mine = it })
            Spacer(Modifier.height(4.dp))
            if (!mine) PixelTabs(listOf(false, true), tanks, { if (it) "TANQUES" else "ATACANTES" }, { tanks = it })

            val (rows, _) = rememberComputed(boss.id, mine, tanks, owned, settings) {
                if (mine) reco.maxTeam(owned, boss).map { MaxRow(it.result, it.owned, it.role) } +
                    reco.maxOptions(owned, boss).first.map { MaxRow(it.result, it.owned) }
                else {
                    val (att, tk) = MaxCalc.versusBoss(boss, idx, level = settings.rankLevel, maxMoveLevel = settings.maxMoveLevel)
                    (if (tanks) tk else att).map { MaxRow(it, null) }
                }
            }
            val list = rows ?: return@WithGame LoadingBox()
            if (list.isEmpty()) {
                DialogBox(if (mine) "Sin Pokémon Dinamax en la caja." else "Sin resultados.",
                    Modifier.padding(8.dp))
                return@WithGame
            }
            // Sin explicación: el rol de cada uno (ATACANTE / TANQUE) va en su fila.
            val teamSize = list.count { it.role.isNotEmpty() }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { i, r -> "$i|${r.owned?.uid}|${r.result.species.id}|${r.role}" }) { i, row ->
                    if (mine && i == teamSize && teamSize > 0) SectionTitle("Otros Dinamax", Modifier.padding(horizontal = 8.dp))
                    val r = row.result
                    RankRow(if (row.role.isNotEmpty()) 0 else i + 1 - teamSize, r.species, onClick = { onSpecies(r.species.id) },
                        title = (row.owned?.nickname ?: r.displayName),
                        gmax = r.gigantamax, dynamax = !r.gigantamax,
                        trailing = {
                            val showTank = tanks || row.role == "Tanque"
                            GbaText(
                                if (showTank) "${(r.bulk / r.damageTakenMult / 1000).toInt()}k" else r.maxDamage.toInt().toString(),
                                size = 16.sp,
                            )
                        }) {
                        Column {
                            if (row.role.isNotEmpty()) Tag(row.role.uppercase(), if (row.role == "Tanque") Pal.Blue else Pal.Dynamax)
                            MoveLabel(r.fast, r.species)
                            GbaText("→ ${r.maxMoveName}", size = 12.sp, color = Pal.Dynamax)
                            row.owned?.let {
                                GbaText("PC ${it.cp} · Nv ${fmtLevel(it.level)} · IV ${it.ivAtk}/${it.ivDef}/${it.ivSta}", size = 11.sp, color = Pal.Muted)
                            }
                            if (r.damageTakenMult < 1.0) GbaText("Resiste al jefe (×${"%.2f".format(r.damageTakenMult)})", size = 11.sp, color = Pal.Header)
                        }
                    }
                }
            }
        }
    }
}
