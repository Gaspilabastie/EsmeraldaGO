package com.esmeraldago.app.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.dexLabel
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.common.fmt1
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.common.fmtNum
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.IvBar
import com.esmeraldago.app.ui.theme.Labeled
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.app.ui.theme.YesNoDialog
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.calc.PowerUpCost
import com.esmeraldago.core.calc.PveTarget
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Types

private data class PveLine(val type: String, val rankNow: Int, val rank40: Int, val fast: String, val charged: String)
private data class PvpLine(val league: League, val formName: String?, val iv: PokeMath.PvpIvResult, val metaRank: Int?, val metaScore: Double?, val tooHigh: Boolean)

@Composable
fun OwnedDetailScreen(uid: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val container = LocalContainer.current
    val items by container.collection.items.collectAsState()
    val settings by container.settings.state.collectAsState()
    val p = items.firstOrNull { it.uid == uid }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (p == null) {
            TitleBar("Pokémon", onBack)
            DialogBox("Este Pokémon ya no está en tu caja.", Modifier.padding(8.dp))
            return@Column
        }
        WithGame { idx, reco ->
            val s = reco.battleSpecies(p)
            if (s == null) {
                TitleBar("Pokémon", onBack)
                GbaText("Especie desconocida", modifier = Modifier.padding(12.dp))
                return@WithGame
            }
            TitleBar(p.nickname ?: s.displayName, onBack)

            val (pvp, _) = rememberComputed(p, settings.pvpMaxLevel) {
                League.entries.flatMap { league ->
                    val forms = (listOf(s) + idx.descendants(idx[p.speciesId] ?: s).map { idx.sameVariant(it, p.shadow) }).distinctBy { it.id }
                    forms.mapNotNull { form ->
                        val meta = idx.pvpRank(league, form.id)
                        val iv = PokeMath.pvpRankOf(form, reco.ivs(p), league.cap, settings.pvpMaxLevel, idx) ?: return@mapNotNull null
                        if (meta == null && form.id != s.id) return@mapNotNull null
                        PvpLine(league, form.displayName.takeIf { form.id != s.id }, iv, meta?.first, meta?.second?.score,
                            league != League.MASTER && p.level > iv.level)
                    }
                }
            }
            val (pve, _) = rememberComputed(p) {
                val fast = idx.move(p.fast)?.let { listOf(it) }
                val charged = p.chargedMoves.mapNotNull { idx.move(it) }.ifEmpty { null }
                val known = fast != null && charged != null
                val types = charged?.map { it.type }?.distinct() ?: s.types
                types.mapNotNull { t ->
                    val target = PveTarget(assumeWeakTo = t)
                    val now = com.esmeraldago.core.calc.PveCalc.best(s, target, idx, p.level, reco.ivs(p), chargedType = t,
                        allowElite = known, allowHiddenPower = known, fastMoves = fast, chargedMoves = charged) ?: return@mapNotNull null
                    val at40 = com.esmeraldago.core.calc.PveCalc.best(s, target, idx, maxOf(40.0, p.level), reco.ivs(p), chargedType = t,
                        allowElite = known, allowHiddenPower = known, fastMoves = fast, chargedMoves = charged)!!
                    PveLine(t, reco.rankAmong(t, now.score), reco.rankAmong(t, at40.score), now.fast.name, now.charged.name)
                }.sortedBy { it.rank40 }
            }

            Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WindowBox(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Sprite(p, s, 112.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            GbaText("${dexLabel(s.dex)} ${s.displayName}", size = 16.sp)
                            GbaText("PC ${p.cp}   Nv ${fmtLevel(p.level)}", size = 18.sp)
                            p.hp?.let { GbaText("PS $it", size = 14.sp, color = Pal.Muted) }
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { s.types.forEach { TypeBadge(it, small = true) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (p.shiny) GbaText("Shiny", size = 12.sp, color = Pal.Gold)
                                if (p.lucky) GbaText("Con suerte", size = 12.sp, color = Pal.Gold)
                                if (p.purified) GbaText("Purificado", size = 12.sp, color = Pal.Blue)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    IvBar("ATQ", p.ivAtk)
                    IvBar("DEF", p.ivDef)
                    IvBar("PS", p.ivSta)
                    GbaText("IV ${p.ivPercent}%" + if (!p.ivExact) " (estimado: escaneá la valoración para precisarlo)" else "",
                        size = 12.sp, color = if (p.ivExact) Pal.Text else Pal.Warn)
                }

                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Movimientos")
                    val moves = listOfNotNull(idx.move(p.fast)) + p.chargedMoves.mapNotNull { idx.move(it) }
                    if (moves.isEmpty()) GbaText("Sin cargar: se calcula con sus ataques más comunes. Editalo o escaneá la pantalla del Pokémon.",
                        size = 13.sp, color = Pal.Muted)
                    moves.forEach { MoveLabel(it, s, 14) }
                }

                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("PvP")
                    when {
                        pvp == null -> GbaText("Calculando…", size = 13.sp)
                        pvp.isEmpty() -> GbaText("No entra en ninguna liga.", size = 13.sp)
                        else -> pvp.forEach { l ->
                            GbaText("${l.league.label}${l.formName?.let { " (como $it)" } ?: ""}", size = 15.sp)
                            GbaText(
                                "IV rank #${l.iv.rank} (${fmt1(l.iv.percentOfBest)}%) · Nv ${fmtLevel(l.iv.level)} → PC ${l.iv.cp}" +
                                    (l.metaRank?.let { "\nMeta: #$it (${fmt1(l.metaScore ?: 0.0)})" } ?: "\nFuera del ranking de la liga") +
                                    if (l.tooHigh) "\nYa pasa el tope de PC de la liga" else "",
                                size = 12.sp, color = Pal.Muted,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Incursiones")
                    val list = pve
                    when {
                        list == null -> GbaText("Calculando…", size = 13.sp)
                        list.isEmpty() -> GbaText("Sin ataques útiles en PvE.", size = 13.sp)
                        else -> list.forEach { l ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TypeBadge(l.type, small = true)
                                Spacer(Modifier.width(6.dp))
                                GbaText(
                                    "Puesto ~#${l.rankNow} ahora" + if (p.level < 40) " · ~#${l.rank40} a Nv40" else "",
                                    size = 13.sp,
                                )
                            }
                            GbaText("${l.fast} / ${l.charged} (vs top 60 de ${Types.es(l.type)})", size = 11.sp, color = Pal.Muted)
                        }
                    }
                }

                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Costo de subir de nivel")
                    listOf(30.0, 40.0, 50.0).filter { it > p.level }.forEach { target ->
                        val c = PokeMath.powerUpCost(p.level, target, p.shadow, p.purified, p.lucky)
                        Labeled("A Nv ${fmtLevel(target)} (PC ${PokeMath.cp(s, target, reco.ivs(p), idx)})", costText(c))
                    }
                    if (p.level >= 50) GbaText("¡Ya está al máximo!", size = 13.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("EDITAR", onEdit)
                    PixelButton("LIBERAR", { confirmDelete = true }, color = Pal.Red)
                }
            }

            if (confirmDelete) YesNoDialog(
                "¿Liberar a ${p.nickname ?: s.displayName}? ¡Adiós, ${p.nickname ?: s.displayName}!",
                onYes = {
                    confirmDelete = false
                    val removed = container.collection.delete(p.uid)
                    container.notify("${p.nickname ?: s.displayName} fue liberado.", "DESHACER") {
                        removed?.let { container.collection.upsert(it) }
                    }
                    onBack()
                },
                onNo = { confirmDelete = false },
            )
        }
    }
}

fun costText(c: PowerUpCost) = buildString {
    append("${fmtNum(c.stardust)} polvo")
    if (c.candy > 0) append(" · ${c.candy} caram.")
    if (c.xlCandy > 0) append(" · ${c.xlCandy} XL")
}
