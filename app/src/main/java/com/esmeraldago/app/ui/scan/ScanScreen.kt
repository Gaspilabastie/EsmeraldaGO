package com.esmeraldago.app.ui.scan

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.esmeraldago.app.live.LiveGestureService
import com.esmeraldago.app.live.LiveScanService
import com.esmeraldago.app.live.ivColor
import com.esmeraldago.app.live.openPokemonGo
import com.esmeraldago.app.live.rememberLiveScanStarter
import com.esmeraldago.app.scan.DraftStatus
import com.esmeraldago.app.scan.ScanDraft
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.SpeciesPickerDialog
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.settings.openAccessibilityPage
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.SmallPixelShape
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.GameIndex
import kotlinx.coroutines.launch

/**
 * Escanear: el modo en vivo arriba; debajo, lo que falta completar o decidir (se conserva aunque se detenga el
 * escaneo o se cierre la app) y lo que ya se guardó en esta sesión.
 */
@Composable
fun ScanScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val session = container.scan
    val drafts by session.drafts.collectAsState()
    val failures by session.failures.collectAsState()
    val pending by session.pending.collectAsState()
    val processed by session.processed.collectAsState()
    val boxItems by container.collection.items.collectAsState()
    var pickingFor by remember { mutableStateOf<Int?>(null) }
    var collapsed by remember { mutableStateOf(setOf<Int>()) }
    val liveRunning by LiveScanService.running.collectAsState()
    val startLive = rememberLiveScanStarter()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // El usuario activa la accesibilidad en Ajustes del sistema: se vuelve a mirar al regresar.
    var assisted by remember { mutableStateOf(LiveGestureService.isEnabled(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) assisted = LiveGestureService.isEnabled(context) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        session.enqueue(uris)
    }
    val open = drafts.filter { !it.saved }
    val done = drafts.filter { it.saved }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Escanear", onBack = onBack)
        WithGame { idx, _ ->
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (pending > 0) item { DialogBox("Leyendo capturas: $processed de ${processed + pending}.", Modifier.padding(8.dp)) }
                    item {
                        WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            GbaText(if (liveRunning) "En vivo: activo" else "En vivo sobre Pokémon GO", size = 16.sp)
                            if (!liveRunning) GbaText(
                                "Al tocar la burbuja se lee el Pokémon en pantalla. Si la lectura está completa, se guarda en la caja.",
                                size = 12.sp, color = Pal.Muted,
                            )
                            Spacer(Modifier.heightIn(min = 6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (liveRunning) {
                                    PixelButton("ABRIR POKéMON GO", { openPokemonGo(context) }, Modifier.weight(2f))
                                    PixelButton("DETENER", { LiveScanService.stop(context) }, Modifier.weight(1f), color = Pal.Red)
                                } else {
                                    PixelButton("EMPEZAR EN VIVO", startLive, Modifier.weight(1f), color = Pal.Dynamax)
                                }
                            }
                            if (!assisted) {
                                // Recomendado, no obligatorio: sin él la valoración se elige a mano.
                                GbaText("Escaneo asistido (recomendado): lee los ataques y la valoración solo. Sin él, la valoración se elige a mano.",
                                    size = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                Row(Modifier.padding(top = 4.dp)) { PixelButton("ACTIVAR", { openAccessibilityPage(context) }) }
                                GbaText("Si Android no permite activarlo: Información de la app › ⋮ › Permitir ajustes restringidos.",
                                    size = 11.sp, color = Pal.Muted, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    if (open.isNotEmpty()) item { SectionTitle("Por completar (${open.size})", Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                    items(open, key = { it.key }) { d ->
                        OpenCard(
                            d, idx,
                            expanded = d.key !in collapsed,
                            duplicateName = d.duplicateOf?.let { uid -> boxItems.firstOrNull { it.uid == uid } }?.let { idx[it.speciesId]?.displayName },
                            onToggle = { collapsed = if (d.key in collapsed) collapsed - d.key else collapsed + d.key },
                            onPickSpecies = { pickingFor = d.key },
                            onSetCp = { cp -> session.setCp(d, cp, idx) },
                            onSave = { scope.launch { session.settle(d.key, idx) } },
                        )
                    }
                    if (done.isNotEmpty() || failures.isNotEmpty())
                        item { SectionTitle("En esta sesión (${done.size + failures.size})", Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                    items(done, key = { it.key }) { d -> DoneRow(d) { session.undo(d.key) } }
                    items(failures) { f ->
                        WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), padding = 6.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    GbaText("No reconocida", size = 14.sp)
                                    GbaText(f.reason, size = 12.sp, color = Pal.Muted)
                                }
                                IconX { session.removeFailure(f) }
                            }
                        }
                    }
                    item { Spacer(Modifier.heightIn(min = 90.dp)) }
                }
            }
            // Barra inferior fija
            val ready = open.count { it.status == DraftStatus.READY }
            WindowBox(Modifier.fillMaxWidth().padding(8.dp), padding = 6.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelButton("DESDE CAPTURAS", {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, Modifier.weight(1f))
                    if (ready > 0) PixelButton("GUARDAR $ready", {
                        scope.launch {
                            val n = session.saveReady()
                            container.notify(if (n == 1) "1 Pokémon guardado en la caja." else "$n Pokémon guardados en la caja.")
                        }
                    }, Modifier.weight(1f), color = Pal.Blue, enabled = pending == 0)
                }
            }

            pickingFor?.let { key ->
                val d = drafts.firstOrNull { it.key == key } ?: return@let
                val options = d.result.speciesOptions
                SpeciesPickerDialog(
                    idx, if (options.size > 1) "Posibles: ${options.take(4).joinToString { it.name }}" else "Elegir especie",
                    preferred = options,
                    onDismiss = { pickingFor = null },
                ) { s ->
                    session.changeSpecies(d, s, idx)
                    pickingFor = null
                }
            }
        }
    }
}

@Composable
private fun IconX(onClick: () -> Unit) {
    Box(Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        GbaText("×", size = 18.sp, color = Pal.Muted)
    }
}

/** Resumen de una fila: sprite, especie, PC, IV% y en qué quedó. */
@Composable
private fun Summary(d: ScanDraft, statusText: String, statusColor: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val p = d.pokemon
    val s = d.result.species
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (s != null) Sprite(p, s, 44.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row {
                GbaText((s?.displayName ?: "¿Especie?") + " · PC ${d.result.cp ?: "—"} · ", size = 14.sp)
                val exact = d.result.ivExact || d.ivPicked
                GbaText(if (exact) "${p.ivPercent}%" else d.result.ivRangeText, size = 14.sp, color = if (exact) ivColor(p) else Pal.Warn)
            }
            GbaText(statusText, size = 12.sp, color = statusColor)
        }
    }
}

@Composable
private fun DoneRow(d: ScanDraft, onUndo: () -> Unit) {
    WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), padding = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val updated = d.status == DraftStatus.UPDATED
            Summary(d, if (updated) "Actualizado en la caja." else "Guardado.", if (updated) Pal.Blue else Pal.Good, Modifier.weight(1f))
            PixelButton(if (updated) "REVERTIR" else "DESHACER", onUndo, color = Pal.Muted)
        }
    }
}

@Composable
private fun CpField(current: Int?, onSetCp: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current?.toString() ?: "") }
    fun apply() { text.toIntOrNull()?.takeIf { it in 10..9999 }?.let(onSetCp) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        GbaText("PC:", size = 14.sp)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.width(96.dp).heightIn(min = 40.dp)
                .background(Pal.FrameOuter, SmallPixelShape).padding(2.dp)
                .background(androidx.compose.ui.graphics.Color.White, SmallPixelShape).padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (text.isEmpty()) GbaText("ej. 2445", size = 14.sp, color = Pal.Muted)
            androidx.compose.foundation.text.BasicTextField(
                text, { v -> text = v.filter(Char::isDigit).take(4) },
                textStyle = com.esmeraldago.app.ui.theme.gbaStyle(15.sp), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { apply() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(6.dp))
        PixelButton("LISTO", { apply() }, enabled = text.length >= 2)
    }
}

/** Un Pokémon sin guardar: lo que falta se completa acá mismo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenCard(
    d: ScanDraft, idx: GameIndex, expanded: Boolean, duplicateName: String?,
    onToggle: () -> Unit, onPickSpecies: () -> Unit, onSetCp: (Int) -> Unit, onSave: () -> Unit,
) {
    val session = LocalContainer.current.scan
    val p = d.pokemon
    val s = d.result.species
    val (statusText, statusColor) = when (d.status) {
        DraftStatus.DUPLICATE -> "Posible duplicado de ${duplicateName ?: "otro"} de la caja." to Pal.Warn
        DraftStatus.INCOMPLETE -> (d.missing ?: "") to Pal.Warn
        else -> (if (d.ivPicked) "IV elegidos a mano." else "Lectura completa.") to Pal.Muted
    }
    WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Summary(d, statusText, statusColor, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle))
        if (!expanded) return@WindowBox

        Row(Modifier.padding(top = 4.dp)) { PixelButton("CAMBIAR ESPECIE", onPickSpecies, color = Pal.Muted) }
        if (d.result.speciesAmbiguous || s == null) d.result.problem?.let { GbaText(it, size = 12.sp, color = Pal.Warn) }
        // PC tapado o reconstruido: se escribe y se recalcula todo con lo que ya se leyó.
        if (d.result.cp == null || d.result.cpInferred) CpField(d.result.cp, onSetCp)
        val c = d.result.candidates
        when {
            s == null || c.isEmpty() -> Unit
            d.result.ivExact -> GbaText("IV ${p.ivAtk}/${p.ivDef}/${p.ivSta} · Nv ${fmtLevel(p.level)}", size = 14.sp)
            else -> {
                GbaText("Combinaciones posibles:", size = 12.sp, modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    c.sortedByDescending { it.ivs.total }.take(8).forEach { cand ->
                        val sel = d.ivPicked && cand.ivs.atk == p.ivAtk && cand.ivs.def == p.ivDef && cand.ivs.sta == p.ivSta && cand.level == p.level
                        Box(
                            Modifier.heightIn(min = 44.dp)
                                .background(if (sel) Pal.Select else Pal.Window, SmallPixelShape)
                                .clickable { session.pickIvs(d.key, cand) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) { GbaText("${cand.ivs} · Nv ${fmtLevel(cand.level)} · ${cand.ivs.percent}%", size = 13.sp) }
                    }
                }
            }
        }
        if (s != null) {
            listOfNotNull(idx.move(p.fast)).plus(p.chargedMoves.mapNotNull { idx.move(it) }).forEach { MoveLabel(it, s, 12) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp)) {
            fun change(f: (com.esmeraldago.core.model.OwnedPokemon) -> com.esmeraldago.core.model.OwnedPokemon) =
                session.update(d.key) { cur -> cur.copy(pokemon = f(cur.pokemon)) }
            ToggleChip("Oscuro", p.shadow, { v -> change { it.copy(shadow = v, purified = false) } })
            ToggleChip("Purificado", p.purified, { v -> change { it.copy(purified = v, shadow = false) } })
            ToggleChip("Con suerte", p.lucky, { v -> change { it.copy(lucky = v) } })
            ToggleChip("Shiny", p.shiny, { v -> change { it.copy(shiny = v) } })
            ToggleChip("Dinamax", p.dynamax, { v -> change { it.copy(dynamax = v, gigantamax = false) } })
            ToggleChip("Gigamax", p.gigantamax, { v -> change { it.copy(gigantamax = v, dynamax = false) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (d.status == DraftStatus.DUPLICATE) {
                PixelButton("REEMPLAZAR", { session.replace(d.key) }, Modifier.weight(1f))
                PixelButton("AGREGAR", { session.addAnyway(d.key) }, Modifier.weight(1f))
            } else {
                PixelButton("GUARDAR", onSave, Modifier.weight(2f), color = Pal.Blue, enabled = d.missing == null)
            }
            PixelButton("DESCARTAR", { session.remove(d.key) }, Modifier.weight(1f), color = Pal.Red)
        }
    }
}
