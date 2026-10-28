package com.esmeraldago.app.ui.advice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.LoadingBox
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.data.RegionalEntry
import com.esmeraldago.core.data.RegionalGroup
import com.esmeraldago.core.data.Regionals
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.model.Species
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** La especie (y forma, si la app la tiene) para el sprite: la forma se reconoce por su nombre ("Aqua", "Sensu"…). */
private fun speciesFor(e: RegionalEntry, idx: GameIndex): Species? {
    val forms = idx.released.filter { it.dex == e.dex && !it.shadow && !it.mega }
    val label = Regionals.formLabel(e)?.substringBefore(' ')?.lowercase()
    return label?.let { l -> forms.firstOrNull { it.name.lowercase().contains(l) } } ?: forms.firstOrNull { !it.name.contains("(") } ?: forms.firstOrNull()
}

/**
 * Pokémon que solo salen en ciertas regiones del mundo (o en ciertos biomas), según Bulbapedia, con los que ya están
 * en la caja.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegionalsContent(idx: GameIndex, owned: List<OwnedPokemon>) {
    val repo = LocalContainer.current.regionals
    var groups by remember { mutableStateOf<List<RegionalGroup>?>(null) }
    var fetchedAt by remember { mutableStateOf(0L) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var missingOnly by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        failed = false
        repo.load(force = attempt > 0)
            .onSuccess { (g, at) -> groups = g; fetchedAt = at }
            .onFailure { failed = true }
    }
    val ownedDex = remember(owned, idx) { owned.mapNotNull { idx[it.speciesId]?.dex }.toSet() }

    when {
        groups == null && failed -> Column(Modifier.padding(8.dp)) {
            DialogBox("No se pudo consultar Bulbapedia.")
            Row(Modifier.padding(top = 6.dp)) { PixelButton("REINTENTAR", { attempt++ }) }
        }
        groups == null -> LoadingBox("Consultando")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    ToggleChip("Solo los que faltan", missingOnly, { missingOnly = it })
                }
            }
            val shown = groups!!.map { g -> g to g.members.filter { !missingOnly || it.dex !in ownedDex } }.filter { it.second.isNotEmpty() }
            var lastBiome: Boolean? = null
            shown.forEach { (g, members) ->
                if (g.biome != lastBiome) {
                    lastBiome = g.biome
                    item { SectionTitle(if (g.biome) "Por bioma" else "Por región", Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                }
                item {
                    WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), padding = 8.dp) {
                        GbaText(Regionals.SPANISH[g.region] ?: g.region, size = 15.sp)
                        g.note?.let { GbaText(Regionals.NOTES_SPANISH[it] ?: it, size = 12.sp, color = Pal.Muted) }
                        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            members.forEach { e -> RegionalMember(e, idx, e.dex in ownedDex) }
                        }
                    }
                }
            }
            if (shown.isEmpty()) item { DialogBox("Ya están todos en la caja.", Modifier.padding(8.dp)) }
            item {
                GbaText(
                    "Fuente: Bulbapedia (CC BY-NC-SA), consultada el ${SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(fetchedAt))}. " +
                        "Fuera de su región, algunos salen en eventos o en huevos de 5 km obtenidos allí.",
                    size = 11.sp, color = Pal.Muted, modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun RegionalMember(e: RegionalEntry, idx: GameIndex, inBox: Boolean) {
    val s = speciesFor(e, idx)
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (s != null) Sprite(s, 48.dp)
        GbaText(e.name, size = 12.sp)
        Regionals.formLabel(e)?.let { GbaText(it, size = 11.sp, color = Pal.Muted) }
        if (inBox) GbaText("En la caja", size = 11.sp, color = Pal.Good)
    }
}
