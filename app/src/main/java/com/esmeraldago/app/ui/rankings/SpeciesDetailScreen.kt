package com.esmeraldago.app.ui.rankings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.DefenseMatchups
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.SpeciesTags
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.TierBadge
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.dexLabel
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.common.fmt1
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.HpBar
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.calc.PveTarget
import com.esmeraldago.core.calc.Tier
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.model.Types

private enum class Page(val label: String) { SUMMARY("RESUMEN"), BATTLE("COMBATE"), FORMS("FORMAS") }

/** Puesto de la especie en un ranking, con su tier (relativo al #1 de ese ranking). */
private data class RankInfo(val label: String, val rank: Int, val tier: Tier)

/**
 * Entrada de Pokédex: arriba lo esencial (sprite con shiny, tipos, stats) y tres páginas para no
 * sobrecargar: RESUMEN (rankings, tipos, evoluciones), COMBATE (ataques) y FORMAS (oscuro, megas, regionales).
 */
@Composable
fun SpeciesDetailScreen(id: String, onBack: () -> Unit, onSpecies: (String) -> Unit = {}) {
    var page by rememberSaveable(id) { mutableStateOf(Page.SUMMARY) }
    var shiny by rememberSaveable(id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        WithGame { idx, _ ->
            val s = idx[id]
            if (s == null) {
                TitleBar("Pokémon", onBack)
                GbaText("Especie desconocida: $id", modifier = Modifier.padding(12.dp))
                return@WithGame
            }
            TitleBar("${dexLabel(s.dex)} ${s.displayName}", onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(s, idx, shiny) { shiny = !shiny }
                PixelTabs(Page.entries, page, { it.label }, { page = it })
                when (page) {
                    Page.SUMMARY -> Summary(s, idx, onSpecies)
                    Page.BATTLE -> Battle(s, idx)
                    Page.FORMS -> Forms(s, idx, onSpecies)
                }
            }
        }
    }
}

@Composable
private fun Header(s: Species, idx: GameIndex, shiny: Boolean, toggleShiny: () -> Unit) {
    WindowBox(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = toggleShiny)) {
                Sprite(s, 112.dp, shiny = shiny)
                GbaText(if (shiny) "SHINY · tocá" else "tocá: shiny", size = 11.sp, color = Pal.Muted)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { s.types.forEach { TypeBadge(it) } }
                Spacer(Modifier.height(3.dp))
                SpeciesTags(s, gmax = s.gigantamax, dynamax = s.dynamax)
                StatLine("ATQ", s.atk)
                StatLine("DEF", s.def)
                StatLine("PS", s.sta)
                GbaText("PC máx: Nv40 ${PokeMath.cp(s, 40.0, Ivs.PERFECT, idx)} · Nv50 ${PokeMath.cp(s, 50.0, Ivs.PERFECT, idx)}",
                    size = 12.sp, color = Pal.Muted)
                val kind = listOfNotNull("Legendario".takeIf { s.legendary }, "Singular".takeIf { s.mythical }, "Ultraente".takeIf { s.ultraBeast })
                if (kind.isNotEmpty()) GbaText(kind.joinToString(" · "), size = 12.sp, color = Pal.Muted)
            }
        }
    }
}

// ---------------------------------------------------------------- RESUMEN

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(s: Species, idx: GameIndex, onSpecies: (String) -> Unit) {
    val settings by LocalContainer.current.settings.state.collectAsState()
    // Rankings PvE solo de los tipos de SUS ataques cargados (los demás no tienen sentido para esta especie),
    // comparando con los de su clase: un Pokémon común contra los que no son oscuros ni megas (si no, Mewtwo
    // queda #16 en Psíquico detrás de sus propias versiones mega y oscura); el oscuro incluye oscuros, etc.
    val (pveRanks, busy) = rememberComputed(s.id, settings.rankLevel) {
        PveCalc.usableCharged(s, idx).map { it.type }.distinct().mapNotNull { type ->
            val list = PveCalc.topAttackers(type, idx, settings.rankLevel, includeShadow = s.shadow || s.mega, includeMega = s.mega, limit = 200)
                .distinctBy { it.species.id }
            val i = list.indexOfFirst { it.species.id == s.id }
            if (i < 0) null else RankInfo(Types.es(type), i + 1, Tier.of(list[i].score, list.first().score))
        }.sortedBy { it.rank }
    }
    val (maxRanks, _) = rememberComputed(s.id, settings.rankLevel, settings.maxMoveLevel) {
        if (!s.dynamax && !s.gigantamax) emptyList() else {
            PveCalc.usableFast(s, idx).flatMap { f -> listOfNotNull(MaxCalc.maxMoveType(s, false, f), MaxCalc.maxMoveType(s, true, f).takeIf { s.gigantamax }) }
                .distinct().mapNotNull { type ->
                    val list = MaxCalc.topAttackers(type, idx, settings.rankLevel, settings.maxMoveLevel, limit = 100)
                    val i = list.indexOfFirst { it.species.id == s.id }
                    if (i < 0) null else RankInfo(Types.es(type), i + 1, Tier.of(list[i].maxDamage, list.first().maxDamage))
                }.sortedBy { it.rank }
        }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Rankings")
        GbaText("PvE (atacante por tipo)", size = 14.sp)
        GbaText(
            when {
                s.mega -> "Comparado con todos, megas y oscuros incluidos."
                s.shadow -> "Comparado con los que no son mega (oscuros incluidos)."
                else -> "Comparado con los que no son oscuros ni megas."
            },
            size = 11.sp, color = Pal.Muted,
        )
        when {
            pveRanks == null -> GbaText(if (busy) "Calculando…" else "—", size = 12.sp, color = Pal.Muted)
            pveRanks.isEmpty() -> GbaText("No está entre los 200 mejores de ningún tipo.", size = 12.sp, color = Pal.Muted)
            else -> RankChips(pveRanks)
        }
        GbaText("PvP (PvPoke)", size = 14.sp, modifier = Modifier.padding(top = 4.dp))
        League.entries.forEach { league ->
            val rank = idx.pvpRank(league, s.id)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GbaText(league.label, size = 13.sp, modifier = Modifier.width(96.dp))
                if (rank == null) GbaText("sin ranking", size = 12.sp, color = Pal.Muted)
                else {
                    TierBadge(Tier.of(rank.second.score, idx.pvpRanking(league).firstOrNull()?.score ?: 100.0))
                    GbaText("#${rank.first}", size = 13.sp)
                }
            }
        }
        if (s.dynamax || s.gigantamax) {
            GbaText("Dinamax (atacante)", size = 14.sp, modifier = Modifier.padding(top = 4.dp))
            maxRanks?.let { if (it.isEmpty()) GbaText("No está entre los 100 mejores.", size = 12.sp, color = Pal.Muted) else RankChips(it) }
        }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Tipos")
        DefenseMatchups(s.types)
    }

    val chain = evolutionStages(s, idx)
    if (chain.size > 1) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Evoluciones")
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            chain.forEachIndexed { i, stage ->
                if (i > 0) GbaText("▶", size = 14.sp, color = Pal.Muted, modifier = Modifier.align(Alignment.CenterVertically))
                Column {
                    stage.forEach { e ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable(enabled = e.id != s.id) { onSpecies(e.id) }.padding(2.dp),
                        ) {
                            Sprite(e, 52.dp)
                            GbaText(e.displayName.substringBefore(" ("), size = 11.sp, color = if (e.id == s.id) Pal.Text else Pal.Muted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RankChips(ranks: List<RankInfo>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ranks.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                TierBadge(r.tier)
                GbaText("#${r.rank} ${r.label}", size = 13.sp)
            }
        }
    }
}

/** Etapas de la cadena evolutiva (desde la base), cada una con sus ramas (Eevee tiene 8 en la segunda). */
private fun evolutionStages(s: Species, idx: GameIndex): List<List<Species>> {
    var root = s
    var guard = 0
    while (guard++ < 5) root = root.parent?.let { idx[it] } ?: break
    val stages = mutableListOf(listOf(root))
    while (stages.size < 5) {
        val next = stages.last().flatMap { st -> st.evolutions.mapNotNull { idx[it] } }.filter { !it.shadow && !it.mega }.distinctBy { it.id }
        if (next.isEmpty()) break
        stages += next
    }
    return stages
}

// ---------------------------------------------------------------- COMBATE

@Composable
private fun Battle(s: Species, idx: GameIndex) {
    val settings by LocalContainer.current.settings.state.collectAsState()
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
            Spacer(Modifier.height(4.dp))
        }
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

// ---------------------------------------------------------------- FORMAS

@Composable
private fun Forms(s: Species, idx: GameIndex, onSpecies: (String) -> Unit) {
    val base = idx[s.baseId] ?: s
    val shadow = idx.shadowOf(base)
    val megas = idx.megasOf(base)
    val others = idx.byDex[s.dex].orEmpty().filter { it.id != base.id }

    if (s.id != base.id) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Forma normal")
        FormRow(base, "La versión sin oscuro ni mega.", onSpecies)
    }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Oscuro")
        if (shadow == null) GbaText("No tiene versión oscura en Pokémon GO.", size = 13.sp, color = Pal.Muted)
        else FormRow(shadow, "Ataque ×1.2, defensa ×0.83. Se consigue derrotando al Team GO Rocket o en incursiones oscuras.", onSpecies)
    }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Mega")
        if (megas.isEmpty()) GbaText("No tiene megaevolución.", size = 13.sp, color = Pal.Muted)
        megas.forEach { m ->
            FormRow(m, "ATQ ${m.atk} · DEF ${m.def} · PS ${m.sta}. Potencia ×1.1 los ataques de todos en la incursión (×1.3 los de su tipo).", onSpecies)
        }
    }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Dinamax")
        GbaText(
            when {
                base.gigantamax -> "Puede ser Dinamax y Gigamax (ataque G-Max propio)."
                base.dynamax -> "Puede ser Dinamax."
                else -> "No tiene versión Dinamax en Pokémon GO."
            },
            size = 13.sp, color = if (base.dynamax || base.gigantamax) Pal.Text else Pal.Muted,
        )
    }
    if (others.isNotEmpty()) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Otras formas")
        others.forEach { o -> FormRow(o, o.types.joinToString(" / ") { Types.es(it) } + " · ATQ ${o.atk} · DEF ${o.def} · PS ${o.sta}", onSpecies) }
    }
}

@Composable
private fun FormRow(f: Species, text: String, onSpecies: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onSpecies(f.id) }.padding(vertical = 3.dp),
    ) {
        Sprite(f, 52.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            GbaText(f.displayName, size = 14.sp, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { f.types.forEach { TypeBadge(it, small = true) }; SpeciesTags(f) }
            GbaText(text, size = 12.sp, color = Pal.Muted)
        }
        GbaText("▶", size = 14.sp, color = Pal.Muted)
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
