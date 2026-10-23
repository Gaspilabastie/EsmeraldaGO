package com.esmeraldago.app.ui.rankings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.SpeciesTags
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.dexLabel
import com.esmeraldago.app.ui.common.fmt1
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.HpBar
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.calc.PveTarget
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeciesDetailScreen(id: String, onBack: () -> Unit) {
    val settings by LocalContainer.current.settings.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        WithGame { idx, _ ->
            val s = idx[id]
            if (s == null) {
                TitleBar("Pokémon", onBack)
                GbaText("Especie desconocida: $id", modifier = Modifier.padding(12.dp))
                return@WithGame
            }
            TitleBar("${dexLabel(s.dex)} ${s.name}", onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WindowBox(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Sprite(s, 120.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { s.types.forEach { TypeBadge(it) } }
                            Spacer(Modifier.width(4.dp))
                            SpeciesTags(s, gmax = s.gigantamax, dynamax = s.dynamax)
                            StatLine("ATQ", s.atk)
                            StatLine("DEF", s.def)
                            StatLine("PS", s.sta)
                            GbaText("PC máx: Nv40 ${PokeMath.cp(s, 40.0, Ivs.PERFECT, idx)} · Nv50 ${PokeMath.cp(s, 50.0, Ivs.PERFECT, idx)}",
                                size = 12.sp, color = Pal.Muted)
                        }
                    }
                }

                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Debilidades")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Types.weaknesses(s.types).forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TypeBadge(t, small = true)
                                GbaText("×${"%.2f".format(Types.effectiveness(t, s.types))}", size = 11.sp)
                            }
                        }
                    }
                    SectionTitle("Resistencias")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Types.resistances(s.types).forEach { TypeBadge(it, small = true) }
                    }
                }

                val (bestIvs, _) = rememberComputed(s.id, settings.pvpMaxLevel) {
                    League.entries.associateWith { PokeMath.pvpTable(s, it.cap, settings.pvpMaxLevel, idx, limit = 1).firstOrNull() }
                }
                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("PvP")
                    League.entries.forEach { league ->
                        val rank = idx.pvpRank(league, s.id)
                        GbaText(league.label + (rank?.let { " · #${it.first} (${fmt1(it.second.score)})" } ?: " · sin ranking"), size = 15.sp)
                        rank?.second?.moveset?.mapNotNull { idx.move(it) }?.forEach { MoveLabel(it, s, 12) }
                        val best = bestIvs?.get(league)
                        if (best != null && league != League.MASTER) {
                            GbaText("IV #1: ${best.ivs} → Nv ${fmtLevel(best.level)}, PC ${best.cp}", size = 12.sp, color = Pal.Muted)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }

                val (pve, _) = rememberComputed(s.id, settings.rankLevel) {
                    val target = PveTarget()
                    PveCalc.usableFast(s, idx).flatMap { f ->
                        PveCalc.usableCharged(s, idx).map { c -> PveCalc.evaluate(s, f, c, settings.rankLevel, Ivs.PERFECT, target, idx) }
                    }.sortedByDescending { it.score }
                }
                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Mejores ataques en PvE")
                    pve?.take(8)?.forEachIndexed { i, r ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(2.dp).background(Pal.FrameInner))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                MoveLabel(r.fast, s, 12)
                                MoveLabel(r.charged, s, 12)
                            }
                            GbaText("DPS ${fmt1(r.dps)}\nTDO ${r.tdo.toInt()}", size = 12.sp)
                        }
                    } ?: GbaText("Calculando…")
                }

                if (s.dynamax || s.gigantamax) WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Dinamax")
                    val (maxRows, _) = rememberComputed(s.id, settings) {
                        listOfNotNull(false.takeIf { s.dynamax }, true.takeIf { s.gigantamax }).flatMap { gmax ->
                            PveCalc.usableFast(s, idx).map { f -> MaxCalc.evaluate(s, gmax, f, idx, settings.rankLevel, maxMoveLevel = settings.maxMoveLevel) }
                        }
                    }
                    maxRows?.forEach { r ->
                        GbaText("${if (r.gigantamax) "G-Max" else "Dynamax"}: ${r.fast.name} → ${r.maxMoveName} (${r.maxDamage.toInt()} daño)", size = 13.sp)
                    }
                }

                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Movimientos")
                    GbaText("PvE: poder / energía / duración — PvP: poder / energía / turnos", size = 11.sp, color = Pal.Muted)
                    (s.fast + s.charged).mapNotNull { idx.move(it) }.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Column(Modifier.weight(1f)) { MoveLabel(m, s, 13) }
                            GbaText(
                                "${m.power.toInt()}/${m.energy}/${fmt1(m.durationMs / 1000.0)}s\n" +
                                    "${m.pvpPower.toInt()}/${m.pvpEnergy}" + if (m.fast) "/${m.pvpTurns}t" else "",
                                size = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        GbaText(label, size = 12.sp, modifier = Modifier.width(34.dp))
        HpBar(value / 350f, Modifier.weight(1f), height = 6.dp, color = Pal.HpGreenDark)
        GbaText(value.toString(), size = 12.sp, modifier = Modifier.width(42.dp), align = androidx.compose.ui.text.style.TextAlign.End)
    }
}
