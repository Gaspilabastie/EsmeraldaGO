package com.esmeraldago.app.ui.rankings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.DefenseMatchups
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.ShinyIcon
import com.esmeraldago.app.ui.common.SpeciesTags
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.SpriteUrl
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

private enum class Page(val label: String) { SUMMARY("INFO"), BATTLE("COMBATE"), FORMS("FORMAS") }

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
 * INFO responde "¿para qué sirve?" (incursiones, combates de entrenador, batallas Max, resistencias);
 * COMBATE da el detalle (ataques, IV, tablas de movimientos); FORMAS, su familia (evolución, megas, Dinamax,
 * oscuro, formas de combate, variantes de aspecto) y cómo conseguirlo.
 * Normal / oscuro / megas son versiones de la misma entrada: se cambian en el encabezado sin salir de la pantalla.
 * Los cálculos pesados se hacen una vez por versión, no cada vez que se cambia de pestaña.
 */
@Composable
fun SpeciesDetailScreen(id: String, onBack: () -> Unit, onSpecies: (String) -> Unit = {}) {
    var page by rememberSaveable(id) { mutableStateOf(Page.SUMMARY) }
    var shownId by rememberSaveable(id) { mutableStateOf(id) }
    var shiny by rememberSaveable(id) { mutableStateOf(false) }
    val settings by LocalContainer.current.settings.state.collectAsState()
    val scrolls = Page.entries.associateWith { rememberScrollState() }
    Column(Modifier.fillMaxSize()) {
        WithGame { idx, reco ->
            val s = idx[shownId]
            if (s == null) {
                TitleBar("Pokémon", onBack)
                GbaText("Especie desconocida: $shownId", modifier = Modifier.padding(12.dp))
                return@WithGame
            }
            // Otra versión de este mismo Pokémon (oscuro, mega): se cambia acá; otra especie abre su entrada.
            val open: (String) -> Unit = { other -> if (idx[other]?.baseId == s.baseId) shownId = other else onSpecies(other) }
            TitleBar("${dexLabel(s.dex)} ${s.displayName}", onBack)
            PixelTabs(Page.entries, page, { it.label }, { page = it }, Modifier.padding(vertical = 6.dp), fill = true)
            val (data, _) = rememberComputed(s.id, settings.rankLevel, settings.maxMoveLevel, settings.pvpMaxLevel) {
                compute(s, idx, settings.rankLevel, settings.maxMoveLevel, settings.pvpMaxLevel)
            }
            Column(
                Modifier.verticalScroll(scrolls.getValue(page)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Header(s, idx, shiny, compact = page != Page.SUMMARY, toggleShiny = { shiny = !shiny }, onVersion = { shownId = it })
                when (page) {
                    Page.SUMMARY -> Summary(s, idx, data)
                    Page.BATTLE -> Battle(s, idx, data)
                    Page.FORMS -> Forms(s, idx, reco, open)
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
private fun Header(s: Species, idx: GameIndex, shiny: Boolean, compact: Boolean, toggleShiny: () -> Unit, onVersion: (String) -> Unit) {
    WindowBox(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Tocar el sprite (o el destello de la esquina) muestra el shiny.
            Box(Modifier.clickable(onClickLabel = "Ver shiny") { toggleShiny() }) {
                Sprite(s, if (compact) 64.dp else 104.dp, shiny = shiny, shinyIcon = false)
                ShinyToggle(shiny, Modifier.align(Alignment.BottomEnd), small = compact)
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
        val versions = versionsOf(s, idx)
        // El selector de versiones va solo en el encabezado completo (INFO).
        if (!compact && versions.size > 1) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            versions.forEach { (v, label) -> VersionChip(label, v.id == s.id, Modifier.weight(1f)) { onVersion(v.id) } }
        }
    }
}

/** Normal, oscuro y megas del mismo Pokémon, con su etiqueta corta. */
private fun versionsOf(s: Species, idx: GameIndex): List<Pair<Species, String>> {
    val base = idx[s.baseId] ?: s
    return listOf(base to "Normal") + listOfNotNull(idx.shadowOf(base)?.let { it to "Oscuro" }) +
        idx.megasOf(base).map { it to megaLabel(it) }
}

/** "Charizard (Mega X)" → "Mega X"; "Kyogre (Primal)" → "Primigenio". */
private fun megaLabel(m: Species): String {
    val inner = Regex("""\((.+)\)""").find(m.name)?.groupValues?.get(1).orEmpty()
    return when {
        inner.contains("Primal", ignoreCase = true) -> "Primigenio"
        inner.startsWith("Mega", ignoreCase = true) -> inner
        else -> "Mega"
    }
}

@Composable
private fun VersionChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 36.dp)
            .background(if (selected) Pal.Select else Pal.Window, com.esmeraldago.app.ui.theme.SmallPixelShape)
            .border(2.dp, if (selected) Pal.Cursor else Pal.FrameInner, com.esmeraldago.app.ui.theme.SmallPixelShape)
            .selectable(selected = selected, role = Role.Tab) { onClick() }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { GbaText(label, size = 13.sp, maxLines = 1) }
}

/** Botón del destello en la esquina del sprite: claro y con borde marcado cuando el shiny está a la vista. */
@Composable
private fun ShinyToggle(on: Boolean, modifier: Modifier, small: Boolean) {
    Box(
        modifier
            .size(if (small) 24.dp else 32.dp)
            .background(if (on) Pal.Select else Pal.Window, com.esmeraldago.app.ui.theme.SmallPixelShape)
            .border(2.dp, if (on) Pal.Cursor else Pal.FrameInner, com.esmeraldago.app.ui.theme.SmallPixelShape)
            .semantics { contentDescription = if (on) "Shiny: sí" else "Shiny: no" },
        contentAlignment = Alignment.Center,
    ) {
        ShinyIcon(if (small) 16.dp else 22.dp, Modifier.alpha(if (on) 1f else 0.45f))
    }
}

// ---------------------------------------------------------------- RESUMEN

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(s: Species, idx: GameIndex, data: EntryData?) {
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Incursiones y gimnasios")
        when {
            data == null -> Reserved("Calculando…", 3)
            data.pve.isEmpty() -> GbaText("No se destaca como atacante en ningún tipo.", size = 13.sp, color = Pal.Muted)
            data.pve.none { it.relevant } -> GbaText("No se destaca como atacante. Sus mejores puestos:", size = 13.sp, color = Pal.Muted)
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
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Con algún tipo destacado arriba, el resto es "en otros tipos"; si no, la frase de arriba ya los presenta.
                if (data.pve.any { it.relevant }) GbaText("En otros tipos:", size = 12.sp, color = Pal.Muted)
                weak.forEach {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TypeBadge(it.type, small = true); GbaText("#${it.rank}", size = 12.sp, color = Pal.Muted)
                    }
                }
            }
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
                    TierBadge(Tier.of(r.second.score, idx.pvpRanking(lg).firstOrNull()?.score ?: 0.0))
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
        SectionTitle("Batallas Max")
        when {
            s.shadow -> GbaText("Los oscuros no pueden Dinamaxizar: hace falta uno atrapado en una batalla Max.", size = 13.sp, color = Pal.Muted)
            s.mega -> GbaText("Las megas no se usan en batallas Max.", size = 13.sp, color = Pal.Muted)
            data == null -> Reserved("Calculando…", 2)
            else -> {
                GbaText((if (s.gigantamax && s.dynamax) "Puede ser Dinamax y Gigamax." else if (s.gigantamax) "Puede ser Gigamax." else "Puede ser Dinamax.") +
                    " Para cada ataque Max: puesto entre los atacantes Max de ese tipo y tier contra el mejor.",
                    size = 11.sp, color = Pal.Muted)
                data.max.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)) {
                        m.tier?.let { TierBadge(it) }
                        TypeBadge(m.type, small = true)
                        GbaText(m.moveName + (m.rank?.let { " · #$it" } ?: ""), size = 13.sp, maxLines = 1)
                    }
                    GbaText("${m.damage.toInt()} de daño · carga la barra con ${m.bestFast.name}", size = 12.sp, color = Pal.Muted)
                }
            }
        }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Resistencias")
        DefenseMatchups(s.types)
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
private fun EvoCell(e: Species, current: Boolean, onSpecies: (String) -> Unit, gmax: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp)
            .background(if (current) Pal.Select else androidx.compose.ui.graphics.Color.Transparent, com.esmeraldago.app.ui.theme.SmallPixelShape)
            .clickable(enabled = !current) { onSpecies(e.id) }.padding(2.dp),
    ) {
        Sprite(e, 48.dp, gmax = gmax)
        GbaText(e.displayName.substringBefore(" ("), size = 10.sp, maxLines = 1)
        val sub = if (gmax) "Gigamax" else subLabel(e)
        sub?.let { GbaText(it, size = 9.sp, color = if (gmax) Pal.Dynamax else if (e.mega) Pal.Blue else Pal.Muted, maxLines = 1) }
    }
}

/** Qué forma es, en corto: "Mega X", "Alola", o lo que diga el paréntesis del nombre ("Wash", "Galar Zen"). */
private fun subLabel(s: Species): String? =
    if (s.mega) megaLabel(s) else regionTag(s) ?: Regex("""\((.+)\)""").find(s.name)?.groupValues?.get(1)
        ?.replace("Galarian", "Galar")?.replace("Alolan", "Alola")?.replace("Hisuian", "Hisui")?.replace("Paldean", "Paldea")

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
        SectionTitle("Batallas Max")
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

/**
 * La familia (línea evolutiva: los que comparten caramelo) y todas sus formas. Los árboles crecen de izquierda a
 * derecha y cada etapa es una columna. Cada sección dice explícitamente cuando no aplica.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Forms(s: Species, idx: GameIndex, reco: Recommender, open: (String) -> Unit) {
    val container = LocalContainer.current
    val owned by container.collection.items.collectAsState()
    val raids by container.raids.raids.collectAsState()
    // Viendo un disfraz, un duplicado o una forma de combate, la familia es la de su forma principal.
    val base = (idx[s.baseId] ?: s).let { b -> idx.cosmeticOf[b.id]?.let { idx[it] } ?: b }
    val stages = familyStages(base, idx)
    val members = stages.flatten()
    fun isCurrent(e: Species) = e.id == s.id || (s.shadow && e.id == s.baseId)
    val cell: @Composable (Species) -> Unit = { e -> EvoCell(e, current = isCurrent(e), open) }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Evolución y formas")
        if (members.size == 1) GbaText("No evoluciona ni tiene otras formas.", size = 13.sp, color = Pal.Muted)
        else Tree(stages.map { stage -> stage.map { e -> @Composable { cell(e) } } })
    }

    val withMegas = members.map { it to idx.megasOf(it).filter { m -> m.baseId == it.id } }.filter { it.second.isNotEmpty() }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Megaevolución")
        if (withMegas.isEmpty()) GbaText("Esta familia no tiene megaevoluciones.", size = 13.sp, color = Pal.Muted)
        else {
            Tree(listOf(withMegas.map { (b, _) -> @Composable { cell(b) } }, withMegas.flatMap { (_, ms) -> ms.map { m -> @Composable { cell(m) } } }))
            withMegas.forEach { (b, megas) ->
                megas.forEach { m ->
                    GbaText("${b.displayName.substringBefore(" (")} ${megaLabel(m)}: ATQ ${signed(m.atk - b.atk)} · DEF ${signed(m.def - b.def)} · PS ${signed(m.sta - b.sta)}",
                        size = 12.sp, color = Pal.Muted)
                }
            }
        }
    }

    val dynamax = members.filter { it.dynamax || it.gigantamax }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Dinamax")
        if (dynamax.isEmpty()) GbaText("Ningún Pokémon de esta familia puede Dinamaxizar.", size = 13.sp, color = Pal.Muted)
        else {
            CenteredCells { dynamax.forEach { cell(it) } }
            GbaText("Solo los atrapados en batallas Max.", size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(top = 4.dp))
        }
    }

    val gmax = members.filter { it.gigantamax }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Gigamax")
        if (gmax.isEmpty()) GbaText("Esta familia no tiene forma Gigamax.", size = 13.sp, color = Pal.Muted)
        else Tree(listOf(gmax.map { b -> @Composable { cell(b) } }, gmax.map { b -> @Composable { EvoCell(b, current = false, open, gmax = true) } }))
    }

    val shadow = idx.shadowOf(idx[s.baseId] ?: base)
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Oscuro")
        if (shadow == null) GbaText("No tiene versión oscura.", size = 13.sp, color = Pal.Muted)
        else {
            GbaText("Ataque ×1.2, defensa ×0.83. Del Team GO Rocket o de incursiones oscuras.", size = 13.sp)
            GbaText("Purificado: +2 a cada IV, aprende Return y sube de nivel más barato (90 % del polvo).", size = 12.sp, color = Pal.Muted)
        }
    }

    // Formas que solo aparecen a mitad del combate (Aegislash Filo, Morpeko Voraz…): forma normal ▶ forma de combate.
    val battle = members.map { m -> m to idx.battleFormsOf(m).filter { b -> battleBaseOf(b, members)?.id == m.id } }.filter { it.second.isNotEmpty() }
    if (battle.isNotEmpty()) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Formas en combate")
        GbaText("Cambia de forma durante el combate; no se atrapa así.", size = 12.sp, color = Pal.Muted)
        Tree(listOf(battle.map { (m, _) -> @Composable { cell(m) } }, battle.flatMap { (_, bs) -> bs.map { b -> @Composable { cell(b) } } }))
    }

    // Variantes de aspecto: las que son entradas de los datos (disfraces de Pikachu, capas de Burmy…) y las que no
    // (letras de Unown, diseños de Vivillon…), con su sprite.
    val cosmetics = members.flatMap { m -> idx.cosmeticsOf(m).let { if (it.isEmpty()) it else listOf(m) + it } }.distinctBy { it.id }
    val catalog = members.mapNotNull { m -> VARIANT_SPRITES[m.dex]?.let { m to it } }
    val notes = members.mapNotNull { m -> VARIANT_NOTES[m.dex]?.let { m to it } }.distinctBy { it.second }
    if (cosmetics.isNotEmpty() || catalog.isNotEmpty() || notes.isNotEmpty()) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Variantes de aspecto")
        GbaText("Mismos stats y tipos: rinden igual, salvo algún ataque exclusivo.", size = 12.sp, color = Pal.Muted)
        notes.forEach { (m, note) -> GbaText(if (members.size > 1) "${m.displayName}: $note" else note, size = 13.sp) }
        if (cosmetics.isNotEmpty()) CenteredCells(Modifier.padding(top = 4.dp)) { cosmetics.forEach { cell(it) } }
        catalog.forEach { (m, variants) ->
            if (catalog.size > 1 || cosmetics.isNotEmpty()) GbaText(m.displayName, size = 13.sp, modifier = Modifier.padding(top = 6.dp))
            CenteredCells(Modifier.padding(top = 4.dp)) { variants.forEach { (label, url) -> VariantCell(m, label, url) } }
        }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Cómo conseguirlo")
        val mine = owned.count { it.speciesId == s.id || it.speciesId == s.baseId }
        val inRaids = raids.orEmpty().mapNotNull { it.species?.id }.toSet() + raids.orEmpty().mapNotNull { it.species?.baseId }
        GbaText("Dónde: " + reco.whereToGet(s, inRaids), size = 13.sp)
        GbaText("Compañero: ${s.buddyKm} km por caramelo", size = 13.sp)
        GbaText(if (mine > 0) "Tenés $mine en tu caja" else "No tenés ninguno", size = 13.sp, color = if (mine > 0) Pal.Text else Pal.Muted)
    }
}

/** La forma normal de una forma de combate: la de su mismo Nº y región (Darmanitan de Galar Daruma → Darmanitan de Galar). */
private fun battleBaseOf(battle: Species, members: List<Species>): Species? {
    val sameDex = members.filter { it.dex == battle.dex }
    val regional = listOf("_galarian", "_alolan", "_hisuian", "_paldean").firstOrNull { battle.id.contains(it) }
    return sameDex.firstOrNull { regional != null && it.id.contains(regional) }
        ?: sameDex.firstOrNull { r -> listOf("_galarian", "_alolan", "_hisuian", "_paldean").none { r.id.contains(it) } }
}

/**
 * Árbol de izquierda a derecha: una columna por etapa (las formas de la misma etapa, una debajo de la otra),
 * con ▶ entre columnas. Si no entra a lo ancho, se desliza.
 */
@Composable
private fun Tree(columns: List<List<@Composable () -> Unit>>) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            columns.forEachIndexed { i, col ->
                if (i > 0) GbaText("▶", size = 14.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 2.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    col.forEach { it() }
                }
            }
        }
    }
}

/** Una variante de aspecto que no es una entrada de los datos: sprite y nombre, sin navegación. */
@Composable
private fun VariantCell(m: Species, label: String, url: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp).padding(2.dp)) {
        SpriteUrl(url, 44.dp, "${m.name} $label")
        GbaText(label, size = 10.sp, maxLines = 1)
    }
}

private const val SHOWDOWN = "https://play.pokemonshowdown.com/sprites/gen5/"
private const val EMERALD = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/versions/generation-iii/emerald/"
private fun sd(slug: String) = "$SHOWDOWN$slug.png"

/** Variantes de aspecto que no figuran en los datos (una sola entrada), con su sprite. */
private val VARIANT_SPRITES: Map<Int, List<Pair<String, String>>> = run {
    val unown = (('A'..'Z').map { it.toString() to "${EMERALD}201-${it.lowercaseChar()}.png" }) +
        listOf("!" to "${EMERALD}201-exclamation.png", "?" to "${EMERALD}201-question.png")
    val vivillon = listOf("Pradera" to "vivillon", "Archipiélago" to "vivillon-archipelago", "Continental" to "vivillon-continental",
        "Elegante" to "vivillon-elegant", "Fantasía" to "vivillon-fancy", "Jardín" to "vivillon-garden", "Altiplano" to "vivillon-highplains",
        "Taiga" to "vivillon-icysnow", "Jungla" to "vivillon-jungle", "Marino" to "vivillon-marine", "Moderno" to "vivillon-modern",
        "Monzón" to "vivillon-monsoon", "Océano" to "vivillon-ocean", "Poké Ball" to "vivillon-pokeball", "Polo" to "vivillon-polar",
        "Fluvial" to "vivillon-river", "Desierto" to "vivillon-sandstorm", "Sabana" to "vivillon-savanna", "Sol" to "vivillon-sun",
        "Tundra" to "vivillon-tundra").map { (l, s) -> l to sd(s) }
    fun flowers(name: String) = listOf("Roja" to name, "Amarilla" to "$name-yellow", "Naranja" to "$name-orange",
        "Azul" to "$name-blue", "Blanca" to "$name-white").map { (l, s) -> l to sd(s) }
    fun seas(name: String) = listOf("Mar Oeste" to sd(name), "Mar Este" to sd("$name-east"))
    fun seasons(name: String) = listOf("Primavera" to name, "Verano" to "$name-summer", "Otoño" to "$name-autumn",
        "Invierno" to "$name-winter").map { (l, s) -> l to sd(s) }
    mapOf(
        201 to unown,
        666 to vivillon,
        669 to flowers("flabebe"), 670 to flowers("floette"), 671 to flowers("florges"),
        422 to seas("shellos"), 423 to seas("gastrodon"),
        585 to seasons("deerling"), 586 to seasons("sawsbuck"),
        676 to listOf("Salvaje" to "furfrou", "Corazón" to "furfrou-heart", "Estrella" to "furfrou-star", "Diamante" to "furfrou-diamond",
            "Señorita" to "furfrou-debutante", "Dama" to "furfrou-matron", "Caballero" to "furfrou-dandy", "Reina" to "furfrou-lareine",
            "Kabuki" to "furfrou-kabuki", "Faraón" to "furfrou-pharaoh").map { (l, s) -> l to sd(s) },
        774 to listOf("Rojo" to "minior", "Naranja" to "minior-orange", "Amarillo" to "minior-yellow", "Verde" to "minior-green",
            "Azul" to "minior-blue", "Añil" to "minior-indigo", "Violeta" to "minior-violet").map { (l, s) -> l to sd(s) },
        550 to listOf("Raya roja" to "basculin", "Raya azul" to "basculin-bluestriped", "Raya blanca" to "basculin-whitestriped").map { (l, s) -> l to sd(s) },
        854 to listOf("Falsificada" to sd("sinistea"), "Auténtica" to sd("sinistea-antique")),
        855 to listOf("Falsificada" to sd("polteageist"), "Auténtica" to sd("polteageist-antique")),
        931 to listOf("Verde" to "squawkabilly", "Azul" to "squawkabilly-blue", "Amarillo" to "squawkabilly-yellow",
            "Blanco" to "squawkabilly-white").map { (l, s) -> l to sd(s) },
        982 to listOf("Dos segmentos" to sd("dudunsparce"), "Tres segmentos" to sd("dudunsparce-threesegment")),
        999 to listOf("Cofre" to sd("gimmighoul"), "Andante" to sd("gimmighoul-roaming")),
    )
}

/** Particularidades que conviene aclarar (sin sprites para mostrar o además de ellos). */
private val VARIANT_NOTES = mapOf(
    327 to "Cada uno tiene su patrón de manchas; solo cambia el aspecto.",
    666 to "El diseño depende de la región donde se consiguió (Postales).",
    422 to "Mar Este y Mar Oeste son regionales.",
    423 to "Mar Este y Mar Oeste son regionales.",
    585 to "La estación cambia con la temporada de GO.",
    586 to "La estación cambia con la temporada de GO.",
    649 to "El disco solo cambia el tipo de Techno Blast.",
    678 to "Macho y hembra aprenden ataques distintos.",
    999 to "En GO se atrapa en su forma Andante.",
)

/** Una fila de celdas centrada, que baja de renglón si no entran. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CenteredCells(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/**
 * La familia por etapas, desde la raíz y siguiendo TODAS las ramas (Meowth de Galar ▶ Perrserker, Slowpoke de
 * Galar ▶ Slowbro y Slowking de Galar), con las formas de cada etapa que rinden distinto (Raichu de Alola,
 * Wormadam Arena, Rotom Lavado). Sin megas, Gigamax, disfraces, duplicados ni formas de combate: tienen su sección.
 * En cada etapa, los hijos van en el orden de sus padres para que las filas coincidan.
 */
private fun familyStages(base: Species, idx: GameIndex): List<List<Species>> {
    fun ok(x: Species) = !x.shadow && !x.mega && x.id !in GameIndex.BATTLE_FORMS && x.id !in GameIndex.ALIAS_FORMS &&
        idx.cosmeticOf[x.id] == null && (idx.includeUnreleased || x.released)
    var root = base
    for (guard in 0 until 5) root = root.parent?.let { idx[it] } ?: break
    val seen = HashSet<String>()
    val stages = mutableListOf<List<Species>>()
    var current = (listOf(root) + idx.byDex[root.dex].orEmpty()).filter { ok(it) && seen.add(it.id) }
    while (current.isNotEmpty() && stages.size < 5) {
        stages += current
        val children = current.flatMap { p -> p.evolutions.mapNotNull { idx[it] } + idx.data.species.filter { it.parent == p.id } }
        current = (children + children.flatMap { idx.byDex[it.dex].orEmpty() }).filter { ok(it) && seen.add(it.id) }
    }
    return stages
}
private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"
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
