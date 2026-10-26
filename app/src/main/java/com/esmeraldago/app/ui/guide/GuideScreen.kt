package com.esmeraldago.app.ui.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.SearchField
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.WindowBox

/** Guía: mecánicas, objetos y estrategias, con búsqueda y filtro por tema. Cada entrada se abre al tocarla. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<GuideCategory?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Guía", onBack)
        Column(Modifier.padding(8.dp)) {
            SearchField(query, { query = it }, "Buscar", Modifier.fillMaxWidth())
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GuideCategory.entries.forEach { c ->
                    ToggleChip(c.label, category == c, { category = if (it) c else null })
                }
            }
        }
        val q = query.trim().lowercase()
        val shown = GUIDE.filter { e ->
            (category == null || e.category == category) &&
                (q.isEmpty() || e.title.lowercase().contains(q) || e.body.lowercase().contains(q))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
            if (shown.isEmpty()) item { GbaText("Sin resultados.", size = 13.sp, color = Pal.Muted, modifier = Modifier.padding(8.dp)) }
            shown.groupBy { it.category }.forEach { (cat, entries) ->
                if (category == null) item(key = "h${cat.name}") { SectionTitle(cat.label, Modifier.padding(top = 6.dp)) }
                items(entries, key = { it.title }) { e ->
                    // Con búsqueda, todo abierto: se ve dónde aparece el término sin tocar cada entrada.
                    val expanded = q.isNotEmpty() || open == e.title
                    WindowBox(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clickable { open = if (open == e.title) null else e.title }, padding = 8.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GbaText(e.title, size = 15.sp, modifier = Modifier.weight(1f))
                            GbaText(if (expanded) "▼" else "▶", size = 12.sp, color = Pal.Muted)
                        }
                        if (expanded) GbaText(e.body, size = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
