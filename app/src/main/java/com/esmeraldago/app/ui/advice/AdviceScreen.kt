package com.esmeraldago.app.ui.advice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.collection.costText
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.LoadingBox
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.Tag
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.TypePicker
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.model.Types
import com.esmeraldago.core.reco.Advice
import com.esmeraldago.core.reco.AdviceKind
import com.esmeraldago.core.reco.GameMode

private enum class AdviceTab(val label: String) { POWER("QUÉ SUBIR"), CATCH("QUÉ ATRAPAR") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdviceScreen(onBack: () -> Unit, onOwned: (String) -> Unit, onSpecies: (String) -> Unit, onScan: () -> Unit = {}) {
    val container = LocalContainer.current
    val owned by container.collection.items.collectAsState()
    val raids by container.raids.raids.collectAsState()
    var tab by rememberSaveable { mutableStateOf(AdviceTab.POWER) }
    // Filtros: modo de juego (null = todos) y tipo (null = todos).
    var mode by rememberSaveable { mutableStateOf<GameMode?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Consejos", onBack)
        PixelTabs(AdviceTab.entries, tab, { it.label }, { tab = it }, Modifier.padding(vertical = 6.dp))
        WithGame { idx, reco ->
            LaunchedEffect(idx) { if (raids == null) container.raids.refresh(idx) }
            val raidIds = raids.orEmpty().mapNotNull { it.species?.id }.toSet()
            val (advice, busy) = rememberComputed(tab, owned, raidIds, reco) {
                if (tab == AdviceTab.POWER) reco.powerUpAdvice(owned) else reco.catchAdvice(owned, raidIds)
            }
            val all = advice.orEmpty()
            val list = all.filter { a -> (mode == null || mode in a.modes) && (type == null || type in a.types) }
                .let { if (tab == AdviceTab.CATCH) it.take(60) else it }

            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "filters") {
                    Column {
                        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToggleChip("Todos", mode == null, { mode = null })
                            GameMode.entries.forEach { m -> ToggleChip(m.label, mode == m, { mode = if (it) m else null }) }
                        }
                        Spacer(Modifier.height(4.dp))
                        TypePicker(type ?: "", { t -> type = if (t == type) null else t })
                        // El tipo elegido ya se ve marcado en la grilla: solo se aclara lo que no se ve.
                        if ((tab == AdviceTab.POWER && !reco.allowXl) || busy) GbaText(
                            listOfNotNull("Sin caramelos XL".takeIf { tab == AdviceTab.POWER && !reco.allowXl }, "…".takeIf { busy }).joinToString(" · "),
                            size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        when {
                            advice == null -> LoadingBox("Calculando")
                            all.isEmpty() -> {
                                DialogBox(
                                    if (owned.isEmpty()) "Sin Pokémon en la caja."
                                    else "Ningún Pokémon necesita mejoras.",
                                    Modifier.padding(8.dp),
                                )
                                if (owned.isEmpty()) Row(Modifier.padding(horizontal = 8.dp)) { PixelButton("ESCANEAR", onScan) }
                            }
                            list.isEmpty() -> DialogBox("Sin resultados con estos filtros.", Modifier.padding(8.dp))
                        }
                    }
                }
                itemsIndexed(list, key = { i, a -> "$i|${a.owned?.uid}|${a.species.id}|${a.title}" }) { _, a ->
                    AdviceCard(a, onClick = { a.owned?.let { onOwned(it.uid) } ?: onSpecies(a.species.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdviceCard(a: Advice, onClick: () -> Unit) {
    val color = when (a.kind) {
        AdviceKind.PVE -> Pal.Orange
        AdviceKind.PVP -> Pal.Blue
        AdviceKind.MAX -> Pal.Dynamax
        AdviceKind.EVOLVE -> Pal.Header
        AdviceKind.CATCH -> Pal.Gold
    }
    WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).clickable(onClick = onClick), padding = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            a.owned?.let { Sprite(it, a.species, 56.dp) } ?: Sprite(a.species, 56.dp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Tag(a.kind.label.uppercase(), color)
                    a.modes.filter { it.label.uppercase() != a.kind.label.uppercase() }.forEach { Tag(it.label.uppercase(), Pal.Muted) }
                }
                Spacer(Modifier.height(2.dp))
                GbaText(if (a.owned == null) a.species.displayName else a.title, size = 15.sp)
                GbaText(a.detail, size = 12.sp, color = Pal.Muted)
                a.cost?.let { GbaText("Costo: ${costText(it)}", size = 12.sp) }
            }
        }
    }
}
