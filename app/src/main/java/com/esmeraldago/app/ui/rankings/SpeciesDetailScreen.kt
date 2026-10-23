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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.Dp
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
import com.esmeraldago.app.ui.theme.Tag
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.calc.PveResult
import com.esmeraldago.core.calc.Tier
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.model.Types
import com.esmeraldago.core.reco.Recommender

private enum class Page(val label: String) { SUMMARY("RESUMEN"), BATTLE("COMBATE"), FORMS("FORMAS") }

/** Cómo rinde como atacante de un tipo: puesto en su clase, tier y % contra el mejor de TODOS, y su mejor par de ataques. */
private class PveRole(
    val type: String, val rank: Int, val tier: Tier, val pct: Int, val bestName: String,
    val result: PveResult, val withoutElite: PveResult?,
) {
    val relevant get() = rank <= 30 || tier.ordinal <= Tier.B.ordinal
}

/** Un movimiento Max: su puesto en el ranking Dinamax de su tipo y el mejor rápido para llenar la barra. */
private class MaxRole(val moveName: String, val type: String, val gmax: Boolean, val damage: Double, val bestFast: Move, val rank: Int?, val tier: Tier?)

private class PvpIvs(val best: PokeMath.PvpIvResult?, val bestFloor10: PokeMath.PvpIvResult?)

private class EntryData(val pve: List<PveRole>, val max: List<MaxRole>, val pvp: Map<League, PvpIvs>)

/**
 * Entrada de Pokédex. Las pestañas quedan fijas arriba; cada página tiene su propio desplazamiento.
 * RESUMEN responde "¿para qué sirve?" (incursiones, combates de entrenador, Dinamax, defensa, datos,
 * evoluciones); COMBATE da el detalle (ataques, IV, tablas de movimientos); FORMAS, sus otras versiones.
 * Los cálculos pesados se hacen una vez por especie, no cada vez que se cambia de pestaña.
 */
@Composable
fun SpeciesDetailScreen(id: String, onBack: () -> Unit, onSpecies: (String) -> Unit = {}) {
    var page by rememberSaveable(id) { mutableStateOf(Page.SUMMARY) }
    var shiny by rememberSaveable(id) { mutableStateOf(false) }
    val settings by LocalContainer.current.settings.state.collectAsState()
    val scrolls = Page.entries.associateWith { rememberScrollState() }
    Column(Modifier.fillMaxSize()) {
        WithGame { idx, reco ->
            val s = idx[id]
            if (s == null) {
                TitleBar("Pokémon", onBack)
                GbaText("Especie desconocida: $id", modifier = Modifier.padding(12.dp))
                return@WithGame
            }
            TitleBar("${dexLabel(s.dex)} ${s.displayName}", onBack)
            PixelTabs(Page.entries, page, { it.label }, { page = it }, Modifier.padding(vertical = 6.dp), fill = true)
            val (data, _) = rememberComputed(s.id, settings.rankLevel, settings.maxMoveLevel, settings.pvpMaxLevel) {
                compute(s, idx, settings.rankLevel, settings.maxMoveLevel, settings.pvpMaxLevel)
            }
            Column(
                Modifier.verticalScroll(scrolls.getValue(page)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Header(s, idx, shiny, compact = page != Page.SUMMARY) { shiny = !shiny }
                when (page) {
                    Page.SUMMARY -> Summary(s, idx, reco, data, onSpecies)
                    Page.BATTLE -> Battle(s, idx, data)
                    Page.FORMS -> Forms(s, idx, onSpecies)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun compute(s: Species, idx: GameIndex, level: Double, maxLevel: Int, pvpMaxLevel: Double): EntryData {
    // PvE: solo los tipos de SUS ataques cargados. Puesto en su clase (un común contra los que no son oscuros ni
    // megas; si no, Mewtwo queda #16 detrás de sus propias versiones), tier contra el #1 de TODOS (como RANKINGS).
    val pve = PveCalc.usableCharged(s, idx).map { it.type }.distinct().mapNotNull { type ->
        val full = PveCalc.topAttackers(type, idx, level, limit = 400)
        val inClass = full.filter { (s.shadow || s.mega || !it.species.shadow) && (s.mega || !it.species.mega) }.distinctBy { it.species.id }
        val i = inClass.indexOfFirst { it.species.id == s.id }
        if (i < 0) return@mapNotNull null
        val r = inClass[i]
        val top = full.first()
        PveRole(type, i + 1, Tier.of(r.score, top.score), (r.score / top.score * 100).toInt(), top.species.displayName, r,
            full.firstOrNull { it.species.id == s.id && !it.usesElite }.takeIf { r.usesElite })
    }.sortedBy { it.rank }

    // Dinamax: un renglón por movimiento Max (el G-Max no depende del rápido; el rápido solo llena la barra).
    val max = if ((!s.dynamax && !s.gigantamax) || s.shadow || s.mega) emptyList() else {
        listOfNotNull(false.takeIf { s.dynamax }, true.takeIf { s.gigantamax }).flatMap { gmax ->
            PveCalc.usableFast(s, idx).map { f -> MaxCalc.evaluate(s, gmax, f, idx, level, maxMoveLevel = maxLevel) }
        }.groupBy { it.maxMoveName to it.gigantamax }.values.map { g ->
            val best = g.maxBy { it.fastDps }
            val list = MaxCalc.topAttackers(best.maxMoveType, idx, level, maxLevel, limit = 200)
            val i = list.indexOfFirst { it.species.id == s.id && it.gigantamax == best.gigantamax }
            MaxRole(best.maxMoveName, best.maxMoveType, best.gigantamax, best.maxDamage, best.fast,
                (i + 1).takeIf { i >= 0 }, if (i >= 0) Tier.of(list[i].maxDamage, list.first().maxDamage) else null)
        }.sortedWith(compareBy(nullsLast()) { it.rank })
    }

    // PvP: IV solo para las ligas en las que compite; los legendarios/singulares/ultraentes salen con piso 10/10/10.
    val pvp = if (s.mega) emptyMap() else League.entries.filter { it != League.MASTER && idx.pvpRank(it, s.id) != null }.associateWith { lg ->
        val table = PokeMath.pvpTable(s, lg.cap, pvpMaxLevel, idx)
        PvpIvs(table.firstOrNull(), table.firstOrNull { minOf(it.ivs.atk, it.ivs.def, it.ivs.sta) >= 10 }.takeIf { s.nonBudget })
    }
    return EntryData(pve, max, pvp)
}

// ---------------------------------------------------------------- encabezado

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(s: Species, idx: GameIndex, shiny: Boolean, compact: Boolean, toggleShiny: () -> Unit) {
    WindowBox(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Sprite(s, if (compact) 64.dp else 104.dp, shiny = shiny)
                if (!compact) ToggleChip("★ Shiny", shiny, { toggleShiny() })
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    s.types.forEach { TypeBadge(it) }
                    SpeciesTags(s, gmax = s.gigantamax, dynamax = s.dynamax)
                    when {
                        s.legendary -> Tag("LEGENDARIO", Pal.Gold)
                        s.mythical -> Tag("SINGULAR", Pal.Gold)
                        s.ultraBeast -> Tag("ULTRAENTE", Pal.Gold)
                    }
                }
                if (compact) {
                    GbaText("ATQ ${s.atk} · DEF ${s.def} · PS ${s.sta}", size = 12.sp, color = Pal.Muted)
                } else {
                    Spacer(Modifier.height(3.dp))
                    StatLine("ATQ", s.atk); StatLine("DEF", s.def); StatLine("PS", s.sta)
                    GbaText("PC máx (Nv 40): ${PokeMath.cp(s, 40.0, Ivs.PERFECT, idx)}", size = 12.sp, color = Pal.Muted)
                    GbaText("PC máx (Nv 50): ${PokeMath.cp(s, 50.0, Ivs.PERFECT, idx)}", size = 12.sp, color = Pal.Muted)
                    s.nameEs?.let { GbaText("En el juego en español: $it", size = 12.sp, color = Pal.Muted) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- RESUMEN

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(s: Species, idx: GameIndex, reco: Recommender, data: EntryData?, onSpecies: (String) -> Unit) {
    val container = LocalContainer.current
    val owned by container.collection.items.collectAsState()
    val raids by container.raids.raids.collectAsState()

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Incursiones y gimnasios")
        when {
            data == null -> Reserved("Calculando…", 3)
            data.pve.none { it.relevant } -> GbaText("No se destaca como atacante en ningún tipo.", size = 13.sp, color = Pal.Muted)
            else -> {
                GbaText(classNote(s), size = 11.sp, color = Pal.Muted)
                data.pve.filter { it.relevant }.take(4).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)) {
                        TierBadge(r.tier)
                        TypeBadge(r.type, small = true)
                        GbaText("#${r.rank} · ${r.pct}% de ${r.bestName}", size = 13.sp, maxLines = 1)
                    }
                    GbaText(movesText(s, r.result.fast, r.result.charged), size = 12.sp, color = Pal.Muted)
                }
            }
        }
        data?.pve?.filterNot { it.relevant }?.takeIf { it.isNotEmpty() }?.let { weak ->
            GbaText("Otros: " + weak.joinToString(", ") { "${Types.es(it.type)} #${it.rank}" }, size = 12.sp, color = Pal.Muted,
                modifier = Modifier.padding(top = 4.dp))
        }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Combates de entrenador")
        if (s.mega) GbaText("Las megas no se usan en combates de entrenador.", size = 13.sp, color = Pal.Muted)
        else {
            val ranked = League.entries.mapNotNull { lg -> idx.pvpRank(lg, s.id)?.let { lg to it } }
            if (ranked.isEmpty()) GbaText("No compite en ninguna liga.", size = 13.sp, color = Pal.Muted)
            ranked.forEach { (lg, r) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    TierBadge(Tier.pvpOf(r.first))
                    GbaText("${lg.label.removePrefix("Liga ")} #${r.first} · ${fmt1(r.second.score)}", size = 13.sp, maxLines = 1)
                }
                GbaText(r.second.moveset.mapNotNull { idx.move(it)?.name }.joinToString(" · "), size = 12.sp, color = Pal.Muted)
            }
            val missing = League.entries.filter { idx.pvpRank(it, s.id) == null }
            if (ranked.isNotEmpty() && missing.isNotEmpty()) GbaText("No compite en: " + missing.joinToString(", ") { it.label.removePrefix("Liga ") },
                size = 12.sp, color = Pal.Muted)
        }
    }

    val base = idx[s.baseId] ?: s
    if (base.dynamax || base.gigantamax) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Dinamax")
        when {
            s.shadow -> GbaText("Los oscuros no pueden Dinamaxizar: hace falta uno atrapado en una batalla Max.", size = 13.sp, color = Pal.Muted)
            s.mega -> GbaText("Las megas no se usan en batallas Max.", size = 13.sp, color = Pal.Muted)
            data == null -> Reserved("Calculando…", 2)
            else -> data.max.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    m.tier?.let { TierBadge(it) }
                    GbaText("${m.moveName} · " + (m.rank?.let { "#$it ${Types.es(m.type)}" } ?: Types.es(m.type)), size = 13.sp, maxLines = 1)
                }
                GbaText("${m.damage.toInt()} de daño · rápido para la barra: ${m.bestFast.name}", size = 12.sp, color = Pal.Muted)
            }
        }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Defensa")
        DefenseMatchups(s.types)
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Datos")
        val mine = owned.count { it.speciesId == s.id || it.speciesId == s.baseId }
        val inRaids = raids.orEmpty().mapNotNull { it.species?.id }.toSet() + raids.orEmpty().mapNotNull { it.species?.baseId }
        GbaText("Dónde: " + reco.whereToGet(s, inRaids), size = 13.sp)
        GbaText("Compañero: ${s.buddyKm} km por caramelo", size = 13.sp)
        GbaText(if (mine > 0) "Tenés $mine en tu caja" else "No tenés ninguno", size = 13.sp, color = if (mine > 0) Pal.Text else Pal.Muted)
    }

    val stages = evolutionStages(s, idx)
    if (stages.size > 1) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Evoluciones")
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            stages.forEachIndexed { i, stage ->
                if (i > 0) GbaText("▶", size = 14.sp, color = Pal.Muted, modifier = Modifier.align(Alignment.CenterVertically))
                // Muchas ramas (Eevee): en grilla de 4.
                FlowRow(Modifier.width(if (stage.size > 2) 248.dp else (62 * stage.size).dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    stage.forEach { e -> EvoCell(e, current = e.id == s.id || e.id == s.baseId, onSpecies) }
                }
            }
        }
    }
}

private fun classNote(s: Species) = when {
    s.mega -> "Puesto entre todos · tier y % contra el mejor."
    s.shadow -> "Puesto entre los que no son mega · tier y % contra el mejor de todos."
    else -> "Puesto entre los que no son oscuros ni megas · tier y % contra el mejor de todos."
}

private fun movesText(s: Species, fast: Move, charged: Move) =
    listOf(fast, charged).joinToString(" + ") { it.name + if (s.isElite(it.id)) " (LEGADO)" else "" }

@Composable
private fun EvoCell(e: Species, current: Boolean, onSpecies: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp)
            .background(if (current) Pal.Select else androidx.compose.ui.graphics.Color.Transparent, com.esmeraldago.app.ui.theme.SmallPixelShape)
            .clickable(enabled = !current) { onSpecies(e.id) }.padding(2.dp),
    ) {
        Sprite(e, 48.dp)
        GbaText(e.displayName.substringBefore(" ("), size = 10.sp, maxLines = 1)
        regionTag(e)?.let { GbaText(it, size = 9.sp, color = Pal.Muted) }
    }
}

private fun regionTag(s: Species): String? = when {
    s.id.endsWith("_alolan") -> "Alola"; s.id.endsWith("_galarian") -> "Galar"
    s.id.endsWith("_hisuian") -> "Hisui"; s.id.endsWith("_paldean") -> "Paldea"
    else -> null
}

/** Etapas de la cadena (desde la base) con todas sus ramas, regionales incluidas (Raichu de Alola, Exeggutor de Alola). */
private fun evolutionStages(s: Species, idx: GameIndex): List<List<Species>> {
    val playable = idx.byDex.values.flatten()
    var root = idx[s.baseId] ?: s
    var guard = 0
    while (guard++ < 5) root = root.parent?.let { idx[it] } ?: break
    val stages = mutableListOf(listOf(root))
    while (stages.size < 5) {
        val prev = stages.last()
        val next = prev.flatMap { st -> st.evolutions.mapNotNull { idx[it] } + playable.filter { it.parent == st.id } }
            .filter { !it.shadow && !it.mega }.distinctBy { it.id }
        if (next.isEmpty()) break
        stages += next
    }
    return stages
}

// ---------------------------------------------------------------- COMBATE

@Composable
private fun Battle(s: Species, idx: GameIndex, data: EntryData?) {
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Ataques para incursiones")
        when {
            data == null -> Reserved("Calculando…", 3)
            data.pve.isEmpty() -> GbaText(
                if (PveCalc.usableCharged(s, idx).isEmpty()) "No tiene ataques que hagan daño (por ejemplo Ditto copia al rival)."
                else "No figura entre los mejores atacantes de ningún tipo.",
                size = 13.sp, color = Pal.Muted,
            )
            else -> data.pve.forEachIndexed { i, r ->
                if (i > 0) Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TypeBadge(r.type, small = true); GbaText("#${r.rank}", size = 12.sp, color = Pal.Muted)
                        }
                        MoveLabel(r.result.fast, s, 12)
                        MoveLabel(r.result.charged, s, 12)
                        r.withoutElite?.let { GbaText("Sin legado: ${it.fast.name} + ${it.charged.name} (${fmt1(it.dps)} DPS)", size = 11.sp, color = Pal.Muted) }
                    }
                    GbaText("${fmt1(r.result.dps)} DPS\n${r.result.tdo.toInt()} TDO", size = 12.sp)
                }
            }
        }
        GbaText("DPS: daño por segundo · TDO: daño total antes de caer, contra un jefe débil a ese tipo.", size = 11.sp, color = Pal.Muted,
            modifier = Modifier.padding(top = 4.dp))
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Combates de entrenador")
        val ranked = League.entries.mapNotNull { lg -> idx.pvpRank(lg, s.id)?.let { lg to it } }
        when {
            s.mega -> GbaText("Las megas no se usan en combates de entrenador.", size = 13.sp, color = Pal.Muted)
            ranked.isEmpty() -> GbaText("No compite en ninguna liga.", size = 13.sp, color = Pal.Muted)
        }
        if (!s.mega) ranked.forEachIndexed { i, (lg, r) ->
            if (i > 0) Divider()
            GbaText("${lg.label} · #${r.first}", size = 14.sp)
            GbaText(r.second.moveset.mapNotNull { idx.move(it)?.name }.joinToString(" · "), size = 12.sp)
            r.second.nonEliteMoveset(s)?.takeIf { it.isNotEmpty() }?.let { alt ->
                GbaText("Sin MT Élite: " + alt.mapNotNull { idx.move(it)?.name }.joinToString(" · ") + " (rinde menos)", size = 11.sp, color = Pal.Muted)
            }
            data?.pvp?.get(lg)?.let { ivs ->
                ivs.best?.let { GbaText("Mejores IV: ${it.ivs} (Nv ${fmtLevel(it.level)}, PC ${it.cp})", size = 12.sp, color = Pal.Muted) }
                ivs.bestFloor10?.let { GbaText("Con mínimo 10/10/10 (incursión): ${it.ivs} (Nv ${fmtLevel(it.level)}, PC ${it.cp})", size = 12.sp, color = Pal.Muted) }
            }
        }
    }

    if (data != null && data.max.isNotEmpty()) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Dinamax")
        data.max.forEach { m ->
            GbaText("${m.moveName}: ${m.damage.toInt()} de daño", size = 13.sp)
            GbaText("Mejor rápido para llenar la barra: ${m.bestFast.name}", size = 12.sp, color = Pal.Muted)
        }
    }

    // Hidden Power: 16 variantes iguales salvo el tipo; se muestra una sola.
    val fast = s.fast.mapNotNull { idx.move(it) }.distinctBy { if (it.id.startsWith("HIDDEN_POWER")) "HP" else it.id }
    val charged = s.charged.mapNotNull { idx.move(it) }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Ataques rápidos")
        MoveTableHeader("PvE poder·energía·s", "PvP poder·energía·turnos")
        fast.forEach { m -> MoveRow(m, s, "${m.power.toInt()} · ${m.energy} · ${fmt1(m.durationMs / 1000.0)}", "${m.pvpPower.toInt()} · ${m.pvpEnergy} · ${m.pvpTurns}") }
    }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Ataques cargados")
        MoveTableHeader("PvE poder·energía", "PvP poder·energía")
        charged.sortedByDescending { it.power }.forEach { m -> MoveRow(m, s, "${m.power.toInt()} · ${m.energy}", "${m.pvpPower.toInt()} · ${m.pvpEnergy}") }
    }
}

@Composable
private fun MoveTableHeader(pve: String, pvp: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Spacer(Modifier.weight(1f))
        GbaText(pve, size = 10.sp, color = Pal.Muted, modifier = Modifier.width(88.dp), align = androidx.compose.ui.text.style.TextAlign.End)
        GbaText(pvp, size = 10.sp, color = Pal.Muted, modifier = Modifier.width(88.dp), align = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun MoveRow(m: Move, s: Species, pve: String, pvp: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp)) {
        Box(Modifier.weight(1f)) { MoveLabel(if (m.id.startsWith("HIDDEN_POWER")) m.copy(name = "Hidden Power (tipo al azar)") else m, s, 12) }
        GbaText(pve, size = 12.sp, modifier = Modifier.width(88.dp), align = androidx.compose.ui.text.style.TextAlign.End)
        GbaText(pvp, size = 12.sp, modifier = Modifier.width(88.dp), align = androidx.compose.ui.text.style.TextAlign.End)
    }
}

// ---------------------------------------------------------------- FORMAS

@Composable
private fun Forms(s: Species, idx: GameIndex, onSpecies: (String) -> Unit) {
    val base = idx[s.baseId] ?: s
    val shadow = idx.shadowOf(base)
    val megas = idx.megasOf(base)
    // Formas con los mismos stats y tipos (disfraces de Pikachu, tamaños de Pumpkaboo) van juntas.
    val others = idx.byDex[s.dex].orEmpty().filter { it.id != base.id }
        .groupBy { "${it.atk}|${it.def}|${it.sta}|${it.types}" }.values.toList()
    val lacks = listOfNotNull("oscuro".takeIf { shadow == null }, "mega".takeIf { megas.isEmpty() },
        "Dinamax".takeIf { !base.dynamax && !base.gigantamax })
    if (lacks.isNotEmpty()) GbaText("No tiene: " + lacks.joinToString(" · "), size = 13.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 4.dp))

    if (s.id != base.id) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Forma normal")
        FormRow(base, "Sin oscuro ni mega.", current = false, onSpecies)
    }
    if (shadow != null) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Oscuro")
        FormRow(shadow, "Ataque ×1.2, defensa ×0.83. Del Team GO Rocket o de incursiones oscuras.", current = s.id == shadow.id, onSpecies)
        GbaText("Purificado: +2 a cada IV, aprende Return y sube de nivel más barato (90 % del polvo).", size = 12.sp, color = Pal.Muted)
    }
    if (megas.isNotEmpty()) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Mega")
        GbaText("En la incursión suma ×1.1 al daño de todos (×1.3 a los ataques de su tipo).", size = 12.sp, color = Pal.Muted)
        megas.forEach { m ->
            FormRow(m, "ATQ ${signed(m.atk - base.atk)} · DEF ${signed(m.def - base.def)} · PS ${signed(m.sta - base.sta)}", current = s.id == m.id, onSpecies)
        }
    }
    if (base.dynamax || base.gigantamax) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Dinamax")
        GbaText(
            (if (base.gigantamax && base.dynamax) "Dinamax y Gigamax (con su ataque G-Max)." else if (base.gigantamax) "Gigamax (con su ataque G-Max)." else "Dinamax.") +
                " Solo los atrapados en batallas Max.",
            size = 13.sp,
        )
    }
    if (others.isNotEmpty()) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Otras formas")
        others.forEach { g ->
            if (g.size == 1) FormRow(g.first(), "ATQ ${g.first().atk} · DEF ${g.first().def} · PS ${g.first().sta}", current = s.id == g.first().id, onSpecies)
            else Column(Modifier.padding(vertical = 3.dp)) {
                GbaText("${g.size} formas con los mismos stats", size = 13.sp)
                GbaText(g.joinToString(", ") { Regex("""\((.+)\)""").find(it.name)?.groupValues?.get(1) ?: it.name }, size = 12.sp, color = Pal.Muted)
            }
        }
    }
}

private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"

@Composable
private fun FormRow(f: Species, text: String, current: Boolean, onSpecies: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(if (current) Pal.Select else androidx.compose.ui.graphics.Color.Transparent, com.esmeraldago.app.ui.theme.SmallPixelShape)
            .clickable(enabled = !current) { onSpecies(f.id) }.padding(vertical = 3.dp),
    ) {
        Sprite(f, 52.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            GbaText(f.displayName + if (current) " (esta)" else "", size = 14.sp, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { f.types.forEach { TypeBadge(it, small = true) } }
            GbaText(text, size = 12.sp, color = Pal.Muted)
        }
        if (!current) { Spacer(Modifier.width(6.dp)); GbaText("▶", size = 14.sp, color = Pal.Muted) }
    }
}

// ---------------------------------------------------------------- piezas

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(2.dp).background(Pal.FrameInner))

/** Mientras calcula, reserva la altura aproximada (que no salte todo lo de abajo al terminar). */
@Composable
private fun Reserved(text: String, lines: Int, lineHeight: Dp = 22.dp) {
    Box(Modifier.fillMaxWidth().height(lineHeight * lines)) { GbaText(text, size = 12.sp, color = Pal.Muted) }
}

@Composable
private fun StatLine(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        GbaText(label, size = 12.sp, modifier = Modifier.width(34.dp))
        HpBar(value / 350f, Modifier.weight(1f), height = 6.dp, color = Pal.HpGreenDark)
        GbaText(value.toString(), size = 12.sp, modifier = Modifier.width(42.dp), align = androidx.compose.ui.text.style.TextAlign.End)
    }
}
