package com.esmeraldago.app.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.SearchField
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.dexLabel
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.theme.Tag
import androidx.compose.runtime.remember
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.SmallPixelShape
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.WindowBox

private enum class Sort(val label: String) { RECENT("RECIENTES"), CP("PC"), IV("IV"), DEX("No."), NAME("NOMBRE") }

@Composable
fun CollectionScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit, onScan: () -> Unit) {
    val items by LocalContainer.current.collection.items.collectAsState()
    var sort by rememberSaveable { mutableStateOf(Sort.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Mi caja", onBack) {
            Box(
                Modifier.heightIn(min = 40.dp).background(Pal.HeaderDark, SmallPixelShape).clickable(onClick = onAdd)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { GbaText("+ AÑADIR", size = 13.sp, color = Pal.TextLight) }
        }
        WithGame { idx, _ ->
            if (items.isEmpty()) {
                DialogBox("Caja vacía. Los Pokémon se cargan escaneando capturas o a mano.",
                    Modifier.padding(8.dp))
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("ESCANEAR", onScan)
                    PixelButton("AÑADIR", onAdd, color = Pal.Blue)
                }
                return@WithGame
            }
            // Búsqueda y orden solo tienen sentido con Pokémon en la caja.
            Column(Modifier.padding(8.dp)) {
                SearchField(query, { query = it }, "Buscar por nombre o apodo")
            }
            PixelTabs(Sort.entries, sort, { it.label }, { sort = it })
            val shown = remember(items, query, sort, idx) {
                val q = query.trim().lowercase()
                items
                    .filter { p -> q.isEmpty() || (idx[p.speciesId]?.name?.lowercase()?.contains(q) == true) || p.nickname?.lowercase()?.contains(q) == true }
                    .let { list ->
                        when (sort) {
                            Sort.RECENT -> list.sortedByDescending { it.addedAt }
                            Sort.CP -> list.sortedByDescending { it.cp }
                            Sort.IV -> list.sortedByDescending { it.ivAtk + it.ivDef + it.ivSta }
                            Sort.DEX -> list.sortedBy { idx[it.speciesId]?.dex ?: 9999 }
                            Sort.NAME -> list.sortedBy { idx[it.speciesId]?.name ?: it.speciesId }
                        }
                    }
            }
            GbaText("${shown.size} Pokémon", size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(6.dp)) {
                items(shown, key = { it.uid }) { p ->
                    val s = idx[p.battleId] ?: idx[p.speciesId]
                    WindowBox(Modifier.padding(3.dp).clickable(onClickLabel = "Ver ${p.nickname ?: s?.name}") { onOpen(p.uid) }, padding = 3.dp) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            // Oscuro / shiny / suerte / purificado se ven como íconos en el sprite.
                            if (s != null) Sprite(p, s, 60.dp)
                        }
                        // Apodos de hasta 12 letras: en dos renglones si hace falta, nunca cortados.
                        GbaText(p.nickname ?: s?.displayName?.substringBefore(" (") ?: p.speciesId, size = 12.sp, maxLines = 2,
                            align = androidx.compose.ui.text.style.TextAlign.Center)
                        GbaText("PC${p.cp}", size = 12.sp, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val color = com.esmeraldago.app.live.ivColor(p)
                            GbaText((if (!p.ivExact) "≈" else "") + "${p.ivPercent}%", size = 12.sp, color = color, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
