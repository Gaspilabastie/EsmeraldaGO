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
import androidx.compose.runtime.remember
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
import com.esmeraldago.core.model.EvoCost
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.model.Types
import com.esmeraldago.core.reco.Recommender

private enum class Page(val label: String) { SUMMARY("INFO"), BATTLE("COMBATE"), FORMS("FORMAS") }

/**
 * "Su clase": los rivales de su misma categoría. Se dejan afuera las megas si no es mega y los legendarios,
 * singulares y ultraentes si no lo es (la idea de la referencia "Budget" de DialgaDex). Los oscuros compiten junto
 * a los normales, como en Pokébattler y DialgaDex.
 */
private fun sameClass(s: Species, o: Species) =
    (s.mega || !o.mega) && (s.nonBudget || !o.nonBudget)

/**
 * Cómo rinde como atacante de un tipo: el puesto general y tier de RANKINGS (ranking completo), el puesto en su
 * clase ([sameClass]) y su mejor par de ataques. Se destaca si está entre los 10 primeros de su clase o en tier B
 * o mejor (≥70 % del #1 general).
 */
private class PveRole(
    val type: String, val species: Species, val rank: Int, val classRank: Int, val tier: Tier, val pct: Int, val bestName: String,
    val result: PveResult, val withoutElite: PveResult?,
) {
    val relevant get() = classRank <= 10 || tier.ordinal <= Tier.B.ordinal
}

/** Un movimiento Max: su puesto (general y en su clase) entre los atacantes Max de su tipo y el rápido que carga la barra. */
private class MaxRole(
    val moveName: String, val type: String, val gmax: Boolean, val damage: Double, val bestFast: Move,
    val rank: Int?, val classRank: Int?, val tier: Tier?, val species: Species,
)

/**
 * Lo que el Pokémon puede llegar a ser: él mismo y todas sus evoluciones, con sus ramas (Eevee). Un oscuro evoluciona
 * a oscuro. Los puestos de INFO son los del mejor de estos, que casi siempre es la última fase; si una fase anterior
 * rinde más (en algunas ligas por el límite de PC), queda esa.
 */
private fun reachable(s: Species, idx: GameIndex): List<Species> {
    if (s.mega) return listOf(s)
    val out = mutableListOf(s)
    val seen = HashSet<String>()
    val queue = ArrayDeque(listOf(idx[s.baseId] ?: s))
    while (queue.isNotEmpty()) {
        for (id in queue.removeFirst().evolutions) {
            if (!seen.add(id)) continue
            val e = idx[id] ?: continue
            queue.add(e)
            val v = if (s.shadow) idx.shadowOf(e) else e
            if (v != null && v.released) out += v
        }
    }
    return out
}

/** "Charizard: " delante de lo que es de una evolución y no del Pokémon de la entrada. */
private fun via(s: Species, who: Species) = if (who.id == s.id) "" else "${who.displayName}: "

/** "#50" o "#50 · #12 en su clase" cuando el puesto en su clase es distinto. */
private fun ranks(rank: Int?, classRank: Int?): String =
    listOfNotNull(rank?.let { "#$it" }, classRank?.takeIf { it != rank }?.let { "#$it en su clase" }).joinToString(" · ")

private class PvpIvs(val best: PokeMath.PvpIvResult?, val bestFloor10: PokeMath.PvpIvResult?)

/** Puesto entre los tanques Dinamax (Def × PS), como en RANKINGS › Dinamax › Tanques. */
private class TankRole(val rank: Int, val classRank: Int, val tier: Tier, val species: Species)

private class EntryData(val pve: List<PveRole>, val max: List<MaxRole>, val tank: TankRole?)

/**
 * Entrada de Pokédex. Las pestañas quedan fijas arriba; cada página tiene su propio desplazamiento.
 * INFO responde "¿para qué sirve?" (incursiones, combates de entrenador, batallas Max, resistencias);
 * COMBATE da el detalle (ataques, IV, tablas de movimientos); FORMAS, su familia (evolución, megas, Dinamax,
 * oscuro, formas de combate, variantes de aspecto) y cómo conseguirlo.
 * Normal / oscuro / megas / Gigamax son versiones de la misma entrada: se cambian en el encabezado sin salir de la
 * pantalla. El Gigamax no es otra especie (mismos stats); su vista muestra su sprite y su ataque G-Max. Un id
 * "charizard:gmax" abre la entrada directamente en esa versión. Los puestos de INFO llevan al ranking ([onRanking]).
 * Los cálculos pesados se hacen una vez por versión, no cada vez que se cambia de pestaña.
 */
@Composable
fun SpeciesDetailScreen(id: String, onBack: () -> Unit, onSpecies: (String) -> Unit = {}, onRanking: (String) -> Unit = {}) {
    var page by rememberSaveable(id) { mutableStateOf(Page.SUMMARY) }
    var shownId by rememberSaveable(id) { mutableStateOf(id.removeSuffix(GMAX_SUFFIX)) }
    var gmaxView by rememberSaveable(id) { mutableStateOf(id.endsWith(GMAX_SUFFIX)) }
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
            // Otra versión de este mismo Pokémon (oscuro, mega, Gigamax): se cambia acá; otra especie abre su entrada.
            val open: (String) -> Unit = { other ->
                val otherId = other.removeSuffix(GMAX_SUFFIX)
                if (idx[otherId]?.baseId == s.baseId) { shownId = otherId; gmaxView = other.endsWith(GMAX_SUFFIX) } else onSpecies(other)
            }
            // La barra lleva la especie; la forma ("Galar", "Mega X") va en el encabezado, para que no se corte.
            TitleBar("${dexLabel(s.dex)} ${s.displayName.substringBefore(" (")}", onBack)
            PixelTabs(Page.entries, page, { it.label }, { page = it }, Modifier.padding(vertical = 6.dp), fill = true)
            val (data, _) = rememberComputed(s.id, settings.rankLevel, settings.maxMoveLevel, settings.pvpMaxLevel) {
                compute(s, idx, settings.rankLevel, settings.maxMoveLevel, settings.pvpMaxLevel)
            }
            Column(
                Modifier.verticalScroll(scrolls.getValue(page)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Header(s, idx, shiny, gmaxView, compact = page != Page.SUMMARY, toggleShiny = { shiny = !shiny }, onVersion = open)
                when (page) {
                    Page.SUMMARY -> Summary(s, idx, data, gmaxView, onRanking)
                    Page.BATTLE -> Battle(s, idx, data)
                    Page.FORMS -> Forms(s, idx, reco, gmaxView, open)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun compute(s: Species, idx: GameIndex, level: Double, maxLevel: Int, pvpMaxLevel: Double): EntryData {
    // Para cada tipo, liga y rol, el mejor de lo que puede llegar a ser ([reachable]).
    val line = reachable(s, idx)

    // PvE: los tipos de los ataques cargados de cualquiera de la línea. El puesto es el de RANKINGS (ranking completo):
    // así coincide con la fila que se resalta al tocarlo.
    val pve = line.flatMap { c -> PveCalc.usableCharged(c, idx).map { it.type } }.distinct().mapNotNull { type ->
        val full = PveCalc.topAttackers(type, idx, level, limit = 400)
        val ranked = full.distinctBy { it.species.id }
        val (c, i) = line.map { it to ranked.indexOfFirst { r -> r.species.id == it.id } }.filter { it.second >= 0 }
            .minByOrNull { it.second } ?: return@mapNotNull null
        val r = ranked[i]
        val top = full.first()
        val classRank = ranked.filter { sameClass(c, it.species) }.indexOfFirst { it.species.id == c.id } + 1
        PveRole(type, c, i + 1, classRank, Tier.of(r.score, top.score), (r.score / top.score * 100).toInt(), top.species.displayName, r,
            full.firstOrNull { it.species.id == c.id && !it.usesElite }.takeIf { r.usesElite })
    }.sortedBy { it.rank }

    // Dinamax: un renglón por tipo de movimiento Max (el G-Max no depende del rápido; el rápido solo llena la barra).
    val maxLine = line.filter { (it.dynamax || it.gigantamax) && !it.shadow && !it.mega }
    val lists = HashMap<String, List<MaxCalc.MaxResult>>()
    val max = maxLine.flatMap { c ->
        listOfNotNull(false.takeIf { c.dynamax }, true.takeIf { c.gigantamax }).flatMap { gmax ->
            PveCalc.usableFast(c, idx).map { f -> MaxCalc.evaluate(c, gmax, f, idx, level, maxMoveLevel = maxLevel) }
        }
    }.groupBy { Triple(it.species.id, it.maxMoveName, it.gigantamax) }.values.map { g ->
        val best = g.maxBy { it.fastDps }
        val c = best.species
        val list = lists.getOrPut(best.maxMoveType) { MaxCalc.topAttackers(best.maxMoveType, idx, level, maxLevel, limit = 200) }
        val i = list.indexOfFirst { it.species.id == c.id && it.gigantamax == best.gigantamax }
        val ci = list.filter { sameClass(c, it.species) }.indexOfFirst { it.species.id == c.id && it.gigantamax == best.gigantamax }
        MaxRole(best.maxMoveName, best.maxMoveType, best.gigantamax, best.maxDamage, best.fast,
            (i + 1).takeIf { i >= 0 }, (ci + 1).takeIf { ci >= 0 }, if (i >= 0) Tier.of(list[i].maxDamage, list.first().maxDamage) else null, c)
    }.groupBy { it.type to it.gmax }.values.map { roles -> roles.minWith(compareBy(nullsLast()) { it.rank }) }
        .sortedWith(compareBy(nullsLast()) { it.rank })

    val tank = if (maxLine.isEmpty()) null else {
        val tanks = MaxCalc.topTanks(idx, level, limit = 400).distinctBy { it.species.id }
        maxLine.map { it to tanks.indexOfFirst { t -> t.species.id == it.id } }.filter { it.second >= 0 }.minByOrNull { it.second }
            ?.let { (c, i) ->
                val ci = tanks.filter { sameClass(c, it.species) }.indexOfFirst { it.species.id == c.id }
                TankRole(i + 1, ci + 1, Tier.of(tanks[i].bulk, tanks.first().bulk), c)
            }
    }
    return EntryData(pve, max, tank)
}

// ---------------------------------------------------------------- encabezado

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(s: Species, idx: GameIndex, shiny: Boolean, gmaxView: Boolean, compact: Boolean, toggleShiny: () -> Unit, onVersion: (String) -> Unit) {
    WindowBox(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Tocar el sprite (o el destello de la esquina) muestra el shiny.
            Box(Modifier.clickable(onClickLabel = "Ver shiny") { toggleShiny() }) {
                Sprite(s, if (compact) 64.dp else 104.dp, shiny = shiny, shinyIcon = false, gmax = gmaxView,
                    dynamaxIcon = s.canDynamax, gigamaxIcon = s.gigantamax)
                ShinyToggle(shiny, Modifier.align(Alignment.BottomEnd), small = compact)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    s.types.forEach { TypeBadge(it) }
                }
                val kind = when { s.legendary -> "Legendario"; s.mythical -> "Singular"; s.ultraBeast -> "Ultraente"; else -> null }
                val form = if (gmaxView) "Gigamax" else Regex("""\((.+)\)""").find(s.displayName)?.groupValues?.get(1)
                listOfNotNull(form, kind).takeIf { it.isNotEmpty() }?.let { GbaText(it.joinToString(" · "), size = 13.sp, color = Pal.Muted) }
                if (compact) {
                    GbaText("ATQ ${s.atk} · DEF ${s.def} · PS ${s.sta}", size = 12.sp, color = Pal.Muted)
                } else {
                    Spacer(Modifier.height(3.dp))
                    StatLine("ATQ", s.atk); StatLine("DEF", s.def); StatLine("PS", s.sta)
                    GbaText("PC máx (Nv 40): ${PokeMath.cp(s, 40.0, Ivs.PERFECT, idx)}", size = 12.sp, color = Pal.Muted)
                    GbaText("PC máx (Nv 50): ${PokeMath.cp(s, 50.0, Ivs.PERFECT, idx)}", size = 12.sp, color = Pal.Muted)
                    s.nameEs?.let { GbaText("Nombre en español: $it", size = 12.sp, color = Pal.Muted) }
                }
            }
        }
        val versions = versionsOf(s, idx)
        // El selector de versiones va solo en el encabezado completo (INFO).
        // Hasta 4 en una fila; con más (Charizard: Normal, Oscuro, Mega X, Mega Y, Gigamax), filas de 3.
        if (!compact && versions.size > 1) versions.chunked(if (versions.size > 4) 3 else versions.size).forEach { row ->
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (key, label) ->
                    val selected = key == (if (gmaxView) s.id + GMAX_SUFFIX else s.id)
                    VersionChip(label, selected, Modifier.weight(1f)) { onVersion(key) }
                }
                repeat((if (versions.size > 4) 3 else versions.size) - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Sufijo de id para abrir la vista Gigamax de una especie ("charizard:gmax"). */
const val GMAX_SUFFIX = ":gmax"

/** Normal, oscuro, megas y Gigamax del mismo Pokémon: (id para abrirla, etiqueta corta). */
private fun versionsOf(s: Species, idx: GameIndex): List<Pair<String, String>> {
    val base = idx[s.baseId] ?: s
    return listOf(base.id to "Normal") + listOfNotNull(idx.shadowOf(base)?.let { it.id to "Oscuro" }) +
        idx.megasOf(base).map { it.id to megaLabel(it) } + listOfNotNull((base.id + GMAX_SUFFIX to "Gigamax").takeIf { base.gigantamax })
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
private fun Summary(s: Species, idx: GameIndex, data: EntryData?, gmaxView: Boolean, onRanking: (String) -> Unit) {
    val line = remember(s.id) { reachable(s, idx) }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Incursiones y gimnasios")
        when {
            data == null -> Reserved("Calculando…", 3)
            data.pve.isEmpty() -> GbaText("No se destaca como atacante en ningún tipo.", size = 13.sp, color = Pal.Muted)
            data.pve.none { it.relevant } -> GbaText("No se destaca como atacante. Sus mejores puestos:", size = 13.sp, color = Pal.Muted)
            else -> {
                data.pve.filter { it.relevant }.take(4).forEach { r ->
                    RankingLink({ onRanking("pve:${r.type}:${r.species.id}") }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TierBadge(r.tier)
                            TypeBadge(r.type, small = true)
                            GbaText(ranks(r.rank, r.classRank), size = 13.sp, maxLines = 2)
                        }
                        GbaText(via(s, r.species) + movesText(r.species, r.result.fast, r.result.charged), size = 12.sp, color = Pal.Muted)
                    }
                }
            }
        }
        data?.pve?.filterNot { it.relevant }?.takeIf { it.isNotEmpty() }?.let { weak ->
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Con algún tipo destacado arriba, el resto es "en otros tipos"; si no, la frase de arriba ya los presenta.
                if (data.pve.any { it.relevant }) GbaText("En otros tipos:", size = 12.sp, color = Pal.Muted)
                weak.forEach {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.clickable(onClickLabel = "Ver en el ranking") { onRanking("pve:${it.type}:${it.species.id}") }) {
                        TypeBadge(it.type, small = true)
                        GbaText(ranks(it.rank, it.classRank) + if (it.species.id != s.id) " (${it.species.displayName})" else "",
                            size = 12.sp, color = Pal.Muted)
                    }
                }
            }
        }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Combates de entrenador")
        run {
            // Por liga, el mejor de la línea: a veces una fase anterior entra mejor por el límite de PC.
            val ranked = League.entries.mapNotNull { lg ->
                line.mapNotNull { c -> idx.pvpRank(lg, c.id)?.let { c to it } }.minByOrNull { it.second.first }?.let { lg to it }
            }
            // Las megas solo se permiten en algunas copas (como Master: Edición Mega), que no tienen ranking acá.
            if (ranked.isEmpty()) GbaText(if (s.mega) "Sin ranking: las megas solo se permiten en algunas copas." else "No compite en ninguna liga.",
                size = 13.sp, color = Pal.Muted)
            ranked.forEach { (lg, cr) ->
                val (c, r) = cr
                RankingLink({ onRanking("pvp:${lg.name}:${c.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TierBadge(Tier.of(r.second.score, idx.pvpRanking(lg).firstOrNull()?.score ?: 0.0))
                        val classRank = idx.pvpRanking(lg).mapNotNull { idx[it.id] }.filter { sameClass(c, it) }.indexOfFirst { it.id == c.id } + 1
                        GbaText("${lg.label.removePrefix("Liga ")} ${ranks(r.first, classRank.takeIf { it > 0 })}", size = 13.sp, maxLines = 2)
                    }
                    GbaText(via(s, c) + r.second.moveset.mapNotNull { idx.move(it)?.name }.joinToString(" · "), size = 12.sp, color = Pal.Muted)
                }
            }
            val missing = League.entries.filter { lg -> ranked.none { it.first == lg } }
            if (ranked.isNotEmpty() && missing.isNotEmpty()) GbaText("No compite en: " + missing.joinToString(", ") { it.label.removePrefix("Liga ") },
                size = 12.sp, color = Pal.Muted)
        }
    }

    val base = idx[s.baseId] ?: s
    if (line.any { (idx[it.baseId] ?: it).let { b -> b.dynamax || b.gigantamax } }) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Batallas Max")
        when {
            s.shadow -> GbaText("Los Pokémon oscuros no pueden Dinamaxizar.", size = 13.sp, color = Pal.Muted)
            s.mega -> GbaText("Sin uso en batallas Max.", size = 13.sp, color = Pal.Muted)
            data == null -> Reserved("Calculando…", 2)
            else -> {
                // Los dos roles siempre: atacante (por cada ataque Max; en la vista Gigamax, solo el G-Max) y tanque.
                data.max.filter { !gmaxView || it.gmax }.forEach { m ->
                    RankingLink({ onRanking("max:${m.type}:${m.species.id}") }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            m.tier?.let { TierBadge(it) }
                            TypeBadge(m.type, small = true)
                            GbaText("Atacante · ${m.moveName}" + ranks(m.rank, m.classRank).let { if (it.isEmpty()) "" else " · $it" }, size = 13.sp, maxLines = 2)
                        }
                        if (m.species.id != s.id) GbaText(m.species.displayName, size = 12.sp, color = Pal.Muted)
                    }
                }
                data.tank?.let { t ->
                    RankingLink({ onRanking("maxtank::${t.species.id}") }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TierBadge(t.tier)
                            GbaText("Tanque · ${ranks(t.rank, t.classRank)}", size = 13.sp, maxLines = 2)
                        }
                        if (t.species.id != s.id) GbaText(t.species.displayName, size = 12.sp, color = Pal.Muted)
                    }
                }
            }
        }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Resistencias")
        DefenseMatchups(s.types)
    }

}

/** Un puesto que lleva a su ranking: toda la fila se toca, con ▶ a la derecha. */
@Composable
private fun RankingLink(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clickable(onClickLabel = "Ver en el ranking", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) { content() }
        GbaText("▶", size = 13.sp, color = Pal.Muted)
    }
}

private fun movesText(s: Species, fast: Move, charged: Move) =
    listOf(fast, charged).joinToString(" + ") { it.name + if (s.isElite(it.id)) " (LEGADO)" else "" }

/** Qué cuesta llegar a esta etapa, en renglones cortos: "50 caramelos", "Piedra Sinnoh", "De noche". */
private fun evoLines(c: EvoCost): List<String> = buildList {
    if (c.candy > 0) add("${c.candy} caramelos")
    if (c.freeIfTraded) add("Gratis si se intercambió")
    c.item?.let { add(if (c.itemCount != null) "${c.item} ×${c.itemCount}" else it) }
    c.buddyKm?.let { add("${it.toInt()} km de compañero") }
    addAll(c.conditions)
}

@Composable
private fun EvoCell(e: Species, current: Boolean, onSpecies: (String) -> Unit, gmax: Boolean = false, cost: EvoCost? = null) {
    val target = if (gmax) e.id + GMAX_SUFFIX else e.id
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp)
            .background(if (current) Pal.Select else androidx.compose.ui.graphics.Color.Transparent, com.esmeraldago.app.ui.theme.SmallPixelShape)
            .clickable(enabled = !current) { onSpecies(target) }.padding(2.dp),
    ) {
        Sprite(e, 48.dp, gmax = gmax)
        // Hasta dos renglones centrados: "Nidoran Female", "Crowned Sword" no se cortan.
        GbaText(e.displayName.substringBefore(" ("), size = 10.sp, maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
        val sub = if (gmax) "Gigamax" else subLabel(e)
        sub?.let { GbaText(it, size = 9.sp, color = if (gmax) Pal.Dynamax else if (e.mega) Pal.Blue else Pal.Muted, maxLines = 2,
            align = androidx.compose.ui.text.style.TextAlign.Center) }
        // Sin límite de renglones: "Módulo Cebo Musgoso" o "Con el teléfono boca abajo" bajan enteros, nunca cortados.
        cost?.let { c ->
            GbaText(evoLines(c).joinToString("\n"), size = 9.sp, color = Pal.Muted, maxLines = 8,
                align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Qué forma es, en corto: "Mega X", "Alola", o lo que diga el paréntesis del nombre ("Wash", "Galar Zen"). */
private fun subLabel(s: Species): String? =
    if (s.mega) megaLabel(s) else if (s.shadow) "Oscuro" else regionTag(s) ?: Regex("""\((.+)\)""").find(s.name)?.groupValues?.get(1)
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
                if (PveCalc.usableCharged(s, idx).isEmpty()) "No tiene ataques que causen daño." else "Sin uso destacado en incursiones.",
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
                    }
                    GbaText("${fmt1(r.result.dps)} DPS\n${r.result.tdo.toInt()} TDO", size = 12.sp)
                }
            }
        }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Combates de entrenador")
        val ranked = League.entries.mapNotNull { lg -> idx.pvpRank(lg, s.id)?.let { lg to it } }
        if (ranked.isEmpty()) GbaText(if (s.mega) "Sin ranking: las megas solo se permiten en algunas copas." else "No compite en ninguna liga.",
            size = 13.sp, color = Pal.Muted)
        ranked.forEachIndexed { i, (lg, r) ->
            if (i > 0) Divider()
            GbaText("${lg.label} · #${r.first}", size = 14.sp)
            GbaText(r.second.moveset.mapNotNull { idx.move(it)?.name }.joinToString(" · "), size = 12.sp)
            r.second.nonEliteMoveset(s)?.takeIf { it.isNotEmpty() }?.let { alt ->
                GbaText("Sin MT Élite: " + alt.mapNotNull { idx.move(it)?.name }.joinToString(" · ") + " (rinde menos)", size = 11.sp, color = Pal.Muted)
            }
        }
    }

    if (data != null && data.max.isNotEmpty()) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Batallas Max")
        // Como las tablas de ataques: tipo en color, nombre y daño a la derecha; abajo, el rápido que carga la barra.
        MoveTableHeader("daño", "")
        data.max.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp)) {
                Box(Modifier.size(8.dp).background(Pal.type(m.type), com.esmeraldago.app.ui.theme.SmallPixelShape))
                Spacer(Modifier.width(3.dp))
                GbaText(m.moveName, size = 12.sp, color = if (m.gmax) Pal.Dynamax else Pal.Text, modifier = Modifier.weight(1f))
                GbaText(m.damage.toInt().toString(), size = 12.sp, modifier = Modifier.width(88.dp), align = androidx.compose.ui.text.style.TextAlign.End)
                Spacer(Modifier.width(88.dp))
            }
            Row(Modifier.padding(start = 12.dp)) { MoveLabel(m.bestFast, s, 11) }
        }
    }

    // Hidden Power: 16 variantes iguales salvo el tipo; se muestra una sola.
    // De mejor a peor: daño por segundo en incursiones (poder / duración).
    fun pveDps(m: Move) = if (m.durationMs > 0) m.power / (m.durationMs / 1000.0) else m.power
    val fast = s.fast.mapNotNull { idx.move(it) }.distinctBy { if (it.id.startsWith("HIDDEN_POWER")) "HP" else it.id }.sortedByDescending { pveDps(it) }
    val charged = s.charged.mapNotNull { idx.move(it) }.sortedByDescending { pveDps(it) }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Ataques rápidos")
        MoveTableHeader("PvE poder·energía·s", "PvP poder·energía·turnos")
        fast.forEach { m -> MoveRow(m, s, "${m.power.toInt()} · ${m.energy} · ${fmt1(m.durationMs / 1000.0)}", "${m.pvpPower.toInt()} · ${m.pvpEnergy} · ${m.pvpTurns}") }
    }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Ataques cargados")
        MoveTableHeader("PvE poder·energía", "PvP poder·energía")
        charged.forEach { m -> MoveRow(m, s, "${m.power.toInt()} · ${m.energy}", "${m.pvpPower.toInt()} · ${m.pvpEnergy}") }
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
private fun Forms(s: Species, idx: GameIndex, reco: Recommender, gmaxView: Boolean, open: (String) -> Unit) {
    val container = LocalContainer.current
    val owned by container.collection.items.collectAsState()
    val raids by container.raids.raids.collectAsState()
    // Viendo un disfraz, un duplicado o una forma de combate, la familia es la de su forma principal.
    val base = (idx[s.baseId] ?: s).let { b -> idx.cosmeticOf[b.id]?.let { idx[it] } ?: b }
    val stages = familyStages(base, idx)
    val members = stages.flatten()
    fun isCurrent(e: Species) = !gmaxView && (e.id == s.id || (s.shadow && e.id == s.baseId))
    val cell: @Composable (Species) -> Unit = { e -> EvoCell(e, current = isCurrent(e), open) }
    // Lo que cuesta llegar a cada etapa (Game Master), debajo de ella.
    val costTo = members.flatMap { it.evoCosts }.associateBy { it.to }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Evolución y formas")
        if (members.size == 1) GbaText("No evoluciona ni tiene otras formas.", size = 13.sp, color = Pal.Muted)
        else Tree(stages.map { stage -> stage.map { e -> @Composable { EvoCell(e, current = isCurrent(e), open, cost = costTo[e.id]) } } })
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

    val dynamax = members.filter { it.canDynamax || it.gigantamax }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Dinamax")
        if (dynamax.isEmpty()) GbaText("Ningún Pokémon de esta familia puede Dinamaxizar.", size = 13.sp, color = Pal.Muted)
        else {
            CenteredCells { dynamax.forEach { cell(it) } }
        }
        if (members.any { it.maxWithoutDynamax })
            GbaText("La forma Coronada entra a los combates Max sin Dinamaxizar.", size = 13.sp, color = Pal.Muted)
    }

    val gmax = members.filter { it.gigantamax }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Gigamax")
        if (gmax.isEmpty()) GbaText("Esta familia no tiene forma Gigamax.", size = 13.sp, color = Pal.Muted)
        else Tree(listOf(gmax.map { b -> @Composable { cell(b) } },
            gmax.map { b -> @Composable { EvoCell(b, current = gmaxView && b.id == s.id, open, gmax = true) } }))
    }

    val shadow = idx.shadowOf(idx[s.baseId] ?: base)
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Oscuro")
        // Solo si existe: tocarla abre la versión oscura.
        if (shadow == null) GbaText("No tiene versión oscura.", size = 13.sp, color = Pal.Muted)
        else CenteredCells { EvoCell(shadow, current = s.id == shadow.id, open) }
    }

    // Formas que solo aparecen a mitad del combate (Aegislash Filo, Morpeko Voraz…): forma normal ▶ forma de combate.
    val battle = members.map { m -> m to idx.battleFormsOf(m).filter { b -> battleBaseOf(b, members)?.id == m.id } }.filter { it.second.isNotEmpty() }
    if (battle.isNotEmpty()) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Formas en combate")
        GbaText("Aparecen solo durante el combate.", size = 12.sp, color = Pal.Muted)
        Tree(listOf(battle.map { (m, _) -> @Composable { cell(m) } }, battle.flatMap { (_, bs) -> bs.map { b -> @Composable { cell(b) } } }))
    }

    // Variantes de aspecto: las que son entradas de los datos (disfraces de Pikachu, capas de Burmy…) y las que no
    // (letras de Unown, diseños de Vivillon…), con su sprite.
    val cosmetics = members.flatMap { m -> idx.cosmeticsOf(m).let { if (it.isEmpty()) it else listOf(m) + it } }.distinctBy { it.id }
    val catalog = members.mapNotNull { m -> VARIANT_SPRITES[m.dex]?.let { m to it } }
    val notes = members.mapNotNull { m -> VARIANT_NOTES[m.dex]?.let { m to it } }.distinctBy { it.second }
    if (cosmetics.isNotEmpty() || catalog.isNotEmpty() || notes.isNotEmpty()) WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Variantes de aspecto")
        if (cosmetics.isNotEmpty()) CenteredCells(Modifier.padding(top = 4.dp)) { cosmetics.forEach { cell(it) } }
        catalog.forEach { (m, variants) ->
            if (catalog.size > 1 || cosmetics.isNotEmpty()) GbaText(m.displayName, size = 13.sp, modifier = Modifier.padding(top = 6.dp))
            CenteredCells(Modifier.padding(top = 4.dp)) { variants.forEach { (label, url) -> VariantCell(m, label, url) } }
        }
        // Aclaraciones abajo, sin repetir el nombre (es la ficha de este mismo Pokémon).
        notes.forEach { (_, note) -> GbaText(note, size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp)) }
    }

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Cómo conseguirlo")
        val mine = owned.count { it.speciesId == s.id || it.speciesId == s.baseId }
        val inRaids = raids.orEmpty().mapNotNull { it.species?.id }.toSet() + raids.orEmpty().mapNotNull { it.species?.baseId }
        GbaText("Dónde: " + reco.whereToGet(s, inRaids), size = 13.sp)
        GbaText("Compañero: ${s.buddyKm} km por caramelo", size = 13.sp)
        GbaText(if (mine > 0) "En la caja: $mine" else "En la caja: ninguno", size = 13.sp, color = if (mine > 0) Pal.Text else Pal.Muted)
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).padding(2.dp)) {
        SpriteUrl(url, 44.dp, "${m.name} $label")
        GbaText(label, size = 10.sp, maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
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
    327 to "Cada Spinda tiene uno de los patrones de manchas de Pokémon GO; no cambia sus stats.",
    666 to "El diseño depende de la región: en Pokémon GO se obtiene con Postales de esa región.",
    422 to "Mar Oeste y Mar Este aparecen en distintas regiones del mundo.",
    423 to "Mar Oeste y Mar Este aparecen en distintas regiones del mundo.",
    585 to "La estación cambia con la temporada de Pokémon GO.",
    586 to "La estación cambia con la temporada de Pokémon GO.",
    649 to "Techno Blast cambia de tipo según el disco.",
    678 to "Macho y hembra aprenden ataques distintos.",
    999 to "En Pokémon GO aparece en su forma Andante.",
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
