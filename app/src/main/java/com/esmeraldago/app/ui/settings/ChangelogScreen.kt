package com.esmeraldago.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.data.ReleaseNote
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.LoadingBox
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.Tag
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.WindowBox

/** Novedades: las notas de cada versión publicada, de la más nueva a la más vieja; la instalada, marcada. */
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val updates = LocalContainer.current.updates
    var attempt by remember { mutableIntStateOf(0) }
    val history by produceState<Result<List<ReleaseNote>>?>(null, attempt) { value = null; value = updates.history() }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Novedades", onBack)
        val result = history ?: return@Column LoadingBox("Consultando GitHub")
        val notes = result.getOrNull()
        if (notes == null) {
            DialogBox("No se pudo consultar GitHub.", Modifier.padding(8.dp))
            Row(Modifier.padding(horizontal = 8.dp)) { PixelButton("REINTENTAR", { attempt++ }) }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(notes, key = { it.version }) { r ->
                WindowBox(Modifier.fillMaxWidth(), padding = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GbaText(r.version, size = 16.sp)
                        GbaText(shortDate(r.date), size = 12.sp, color = Pal.Muted, modifier = Modifier.weight(1f))
                        if (r.version == updates.currentVersion) Tag("INSTALADA", Pal.Good)
                    }
                    if (r.notes.isNotBlank()) GbaText(r.notes, size = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** "2026-10-28T01:59:36Z" → "27/10/2026" en la zona horaria del teléfono. */
private fun shortDate(iso: String): String = runCatching {
    java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}.getOrDefault("")
