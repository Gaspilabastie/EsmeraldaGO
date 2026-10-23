package com.esmeraldago.app.ui.scan

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.scan.ScanDraft
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.SpeciesPickerDialog
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.SmallPixelShape
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.app.ui.theme.YesNoDialog
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.scan.ScreenType

@Composable
fun ScanScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val session = container.scan
    val drafts by session.drafts.collectAsState()
    val failures by session.failures.collectAsState()
    val pending by session.pending.collectAsState()
    val processed by session.processed.collectAsState()
    var pickingFor by remember { mutableStateOf<Int?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(drafts.isEmpty()) }
    val liveRunning by com.esmeraldago.app.live.LiveScanService.running.collectAsState()
    val startLive = com.esmeraldago.app.live.rememberLiveScanStarter()
    val context = androidx.compose.ui.platform.LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        session.enqueue(uris)
    }
    // Volver sin guardar: preguntar (los borradores igual se conservan en la sesión).
    BackHandler(enabled = drafts.isNotEmpty() || pending > 0) { confirmExit = true }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Escanear", onBack = { if (drafts.isNotEmpty() || pending > 0) confirmExit = true else onBack() })
        WithGame { idx, _ ->
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        DialogBox(
                            when {
                                pending > 0 -> "Leyendo capturas… ($processed listas, faltan $pending)"
                                drafts.isEmpty() && failures.isEmpty() -> "Elegí capturas de tus Pokémon. El juego tiene que estar en INGLÉS."
                                else -> "Revisá lo detectado: los marcados con ■ se guardan. Los IV sin confirmar quedan desmarcados."
                            },
                            Modifier.padding(8.dp),
                        )
                    }
                    item {
                        // Escaneo en vivo: burbuja flotante sobre Pokémon GO (como Poke Genie).
                        WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            GbaText("EN VIVO SOBRE POKéMON GO", size = 16.sp)
                            GbaText(
                                if (liveRunning) "La burbuja está activa. Tocala sobre un Pokémon para leerlo; mantenela apretada para ver el panel. " +
                                    "En modo continuo, deslizá por tu colección y se van agregando solos."
                                else "Sin capturas: una burbuja flotante lee la pantalla del juego. Android te va a pedir permiso para " +
                                    "mostrarse sobre otras apps y para capturar la pantalla.",
                                size = 12.sp, color = Pal.Muted,
                            )
                            Spacer(Modifier.heightIn(min = 6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (liveRunning) {
                                    PixelButton("ABRIR POKéMON GO", { com.esmeraldago.app.live.openPokemonGo(context) })
                                    PixelButton("DETENER", { com.esmeraldago.app.live.LiveScanService.stop(context) }, color = Pal.Red)
                                } else {
                                    PixelButton("EMPEZAR EN VIVO", startLive, color = Pal.Dynamax)
                                }
                            }
                        }
                    }
                    item {
                        WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable { showHelp = !showHelp }) {
                            GbaText((if (showHelp) "▼ " else "▶ ") + "¿Qué capturas sacar?", size = 15.sp)
                            if (showHelp) GbaText(
                                "1. Pantalla del Pokémon, scrolleada hasta ver PC, PS, el botón POWER UP con su costo de polvo " +
                                    "y los ataques.\n" +
                                    "2. Su VALORACIÓN (menú ☰ → Appraise) mostrando las 3 barras: con esta los IV son exactos.\n" +
                                    "Podés elegirlas en cualquier orden: se emparejan por PC y PS.\n" +
                                    "Tip: desde la galería, Compartir → EsmeraldaGO.",
                                size = 13.sp,
                            )
                        }
                    }
                    if (failures.isNotEmpty()) item {
                        WindowBox(Modifier.fillMaxWidth().padding(8.dp)) {
                            SectionTitle("No reconocidas (${failures.size})")
                            failures.forEach { GbaText("Captura ${it.index}: ${it.reason}", size = 12.sp, color = Pal.Red) }
                        }
                    }
                    items(drafts, key = { it.key }) { d ->
                        DraftCard(d, idx,
                            onChange = { transform -> session.update(d.key, transform) },
                            onRemove = { session.remove(d.key) },
                            onPickSpecies = { pickingFor = d.key })
                    }
                    item { Spacer(Modifier.heightIn(min = 90.dp)) }
                }
            }
            // Barra inferior fija
            val n = drafts.count { it.include }
            WindowBox(Modifier.fillMaxWidth().padding(8.dp), padding = 6.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelButton("ELEGIR CAPTURAS", {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, Modifier.weight(1f))
                    PixelButton(if (n > 0) "GUARDAR $n" else "GUARDAR", {
                        val saved = session.save(container.collection)
                        container.notify("¡$saved POKéMON enviados a la CAJA!")
                        onBack()
                    }, Modifier.weight(1f), color = Pal.Blue, enabled = n > 0 && pending == 0)
                }
            }

            pickingFor?.let { key ->
                val d = drafts.firstOrNull { it.key == key } ?: return@let
                val options = d.result.speciesOptions
                SpeciesPickerDialog(
                    idx, if (options.size > 1) "Posibles: ${options.take(4).joinToString { it.name }}" else "Elegí la especie",
                    preferred = options,
                    onDismiss = { pickingFor = null },
                ) { s ->
                    session.changeSpecies(d, s, idx)
                    pickingFor = null
                }
            }
        }
    }

    if (confirmExit) YesNoDialog(
        "Hay Pokémon sin guardar. ¿Salir igual? (quedan acá para cuando vuelvas)",
        onYes = { confirmExit = false; onBack() },
        onNo = { confirmExit = false },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftCard(
    d: ScanDraft, idx: GameIndex, onChange: ((ScanDraft) -> ScanDraft) -> Unit, onRemove: () -> Unit,
    onPickSpecies: () -> Unit,
) {
    val p = d.pokemon
    val s = d.result.species
    WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                    .toggleable(d.include, role = Role.Checkbox) { v -> onChange { it.copy(include = v) } },
                contentAlignment = Alignment.Center,
            ) { GbaText(if (d.include) "■" else "□", size = 24.sp) }
            if (s != null) Sprite(p, s, 56.dp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f).clickable(onClick = onPickSpecies).padding(vertical = 4.dp)) {
                GbaText((s?.name ?: "¿Especie?") + " ▼", size = 16.sp)
                GbaText("PC ${d.result.cp ?: "?"} · PS ${d.result.hp ?: "?"} · Nv ${fmtLevel(p.level)}", size = 13.sp)
                p.nickname?.let { GbaText("Apodo: $it", size = 12.sp, color = Pal.Muted) }
                GbaText(
                    when {
                        d.sources > 1 -> "Detalle + valoración"
                        d.result.screen == ScreenType.APPRAISAL -> "Solo valoración (faltan los ataques)"
                        else -> "Solo detalle (falta la valoración)"
                    },
                    size = 12.sp, color = Pal.Muted,
                )
            }
            Box(Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                GbaText("✕", size = 18.sp, color = Pal.Red)
            }
        }
        d.result.problem?.let { GbaText(it, size = 12.sp, color = Pal.Red) }
        val c = d.result.candidates
        when {
            s == null || c.isEmpty() -> Unit
            d.result.ivExact -> GbaText("IV ${p.ivAtk}/${p.ivDef}/${p.ivSta} (${p.ivPercent}%) ✔", size = 15.sp)
            else -> {
                GbaText("IV ${d.result.ivRangeText} · ${c.size} combinaciones posibles: elegí una o agregá la captura de valoración.",
                    size = 12.sp, color = Pal.Red)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    c.sortedByDescending { it.ivs.total }.take(8).forEach { cand ->
                        val sel = cand.ivs.atk == p.ivAtk && cand.ivs.def == p.ivDef && cand.ivs.sta == p.ivSta && cand.level == p.level
                        Box(
                            Modifier.heightIn(min = 40.dp)
                                .background(if (sel) Pal.Select else Pal.Window, SmallPixelShape)
                                .clickable {
                                    // Elegir una combinación a mano cuenta como confirmación.
                                    onChange { cur -> cur.copy(include = true, pokemon = cur.pokemon.copy(level = cand.level, ivAtk = cand.ivs.atk,
                                        ivDef = cand.ivs.def, ivSta = cand.ivs.sta, ivExact = false)) }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) { GbaText("${cand.ivs} Nv${fmtLevel(cand.level)}", size = 13.sp) }
                    }
                }
            }
        }
        if (s != null) {
            listOfNotNull(idx.move(p.fast)).plus(p.chargedMoves.mapNotNull { idx.move(it) }).forEach { MoveLabel(it, s, 12) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleChip("Oscuro", p.shadow, { v -> onChange { cur -> cur.copy(pokemon = cur.pokemon.copy(shadow = v, purified = false)) } })
            ToggleChip("Purificado", p.purified, { v -> onChange { cur -> cur.copy(pokemon = cur.pokemon.copy(purified = v, shadow = false)) } })
            ToggleChip("Con suerte", p.lucky, { v -> onChange { cur -> cur.copy(pokemon = cur.pokemon.copy(lucky = v)) } })
            ToggleChip("Shiny", p.shiny, { v -> onChange { cur -> cur.copy(pokemon = cur.pokemon.copy(shiny = v)) } })
            ToggleChip("Dinamax", p.dynamax, { v -> onChange { cur -> cur.copy(pokemon = cur.pokemon.copy(dynamax = v, gigantamax = false)) } })
            ToggleChip("Gigamax", p.gigantamax, { v -> onChange { cur -> cur.copy(pokemon = cur.pokemon.copy(gigantamax = v, dynamax = false)) } })
        }
    }
}
