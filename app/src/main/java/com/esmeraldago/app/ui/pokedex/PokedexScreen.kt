package com.esmeraldago.app.ui.pokedex

import com.esmeraldago.core.GameIndex
import com.esmeraldago.app.ui.rankings.GMAX_SUFFIX
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.SearchField
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.dexLabel
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.TypePicker
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.PokeMath

/** Generaciones por número de Pokédex. */
private val GENERATIONS = listOf(
    "TODAS" to 1..1025, "I" to 1..151, "II" to 152..251, "III" to 252..386, "IV" to 387..493,
    "V" to 494..649, "VI" to 650..721, "VII" to 722..809, "VIII" to 810..905, "IX" to 906..1025,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PokedexScreen(onBack: () -> Unit, onSpecies: (String) -> Unit) {
    val owned by LocalContainer.current.collection.items.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var gen by rememberSaveable { mutableStateOf("TODAS") }
    // Hasta dos tipos, como en TIPOS: tocar uno elegido lo quita; con dos elegidos, el nuevo reemplaza al más viejo.
    var types by rememberSaveable { mutableStateOf(listOf<String>()) }
    var forms by rememberSaveable { mutableStateOf(false) }
    var onlyMine by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Pokédex", onBack)
        WithGame { idx, _ ->
            val ownedIds = remember(owned) { owned.map { it.speciesId }.toSet() }
            val list = remember(idx, query, gen, types, forms, onlyMine, ownedIds) {
                val range = GENERATIONS.first { it.first == gen }.second
                val q = query.trim().lowercase()
                idx.released.asSequence()
                    .filter { forms || (!it.shadow && !it.mega) }
                    // Disfraces, variantes de aspecto y formas de combate van dentro de la entrada principal (FORMAS);
                    // se encuentran buscándolos.
                    .filter { q.isNotEmpty() || (idx.cosmeticOf[it.id] == null && idx.cosmeticOf[it.baseId] == null && it.id !in GameIndex.BATTLE_FORMS && it.id !in GameIndex.ALIAS_FORMS) }
                    .filter { it.dex in range }
                    .filter { s -> types.all { it in s.types } }
                    .filter { !onlyMine || it.baseId in ownedIds }
                    .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.dex.toString() == q }
                    .sortedWith(compareBy({ it.dex }, { it.shadow }, { it.mega }))
                    // Con "Otras versiones", cada Gigamax como una fila más, después de su forma normal.
                    .flatMap { s -> listOf(s to false) + listOfNotNull((s to true).takeIf { forms && s.gigantamax && !s.shadow && !s.mega }) }
                    .toList()
            }
            val caught = remember(idx, ownedIds) { idx.byDex.count { (_, f) -> f.any { it.id in ownedIds } } }

            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "filters") {
                    Column {
                        Column(Modifier.padding(8.dp)) { SearchField(query, { query = it }, "Buscar (nombre o número)") }
                        PixelTabs(GENERATIONS.map { it.first }, gen, { if (it == "TODAS") it else "GEN $it" }, { gen = it })
                        TypePicker("", { t ->
                            types = when {
                                t in types -> types - t
                                types.size < 2 -> types + t
                                else -> listOf(types.last(), t)
                            }
                        }, selectedSet = types.toSet())
                        FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToggleChip("Otras versiones", forms, { forms = it })
                            ToggleChip("En la caja", onlyMine, { onlyMine = it })
                        }
                        GbaText("${list.size} resultados", size = 12.sp, color = Pal.Muted,
                            modifier = Modifier.padding(horizontal = 10.dp))
                    }
                }
                items(list, key = { (s, gmax) -> if (gmax) s.id + GMAX_SUFFIX else s.id }) { (s, gmax) ->
                    WindowBox(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)
                            .clickable { onSpecies(if (gmax) s.id + GMAX_SUFFIX else s.id) },
                        padding = 6.dp,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(52.dp)) {
                                GbaText(dexLabel(s.dex), size = 12.sp, color = Pal.Muted)
                                // Poké Ball de "atrapado", como en la Pokédex de Esmeralda.
                                if (s.baseId in ownedIds) GbaText("●", size = 14.sp, color = Pal.Good)
                            }
                            Sprite(s, 52.dp, gmax = gmax, dynamaxIcon = s.canDynamax, gigamaxIcon = s.gigantamax)
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                GbaText(s.displayName + if (gmax) " (Gigamax)" else "", size = 15.sp, maxLines = 2)
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    s.types.forEach { TypeBadge(it, small = true) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
