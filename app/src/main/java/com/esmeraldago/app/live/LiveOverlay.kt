package com.esmeraldago.app.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.AppContainer
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.ui.common.ProvideContainer
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.common.rememberComputed
import com.esmeraldago.app.ui.theme.EmeraldTheme
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.model.League
import com.esmeraldago.core.scan.ScreenType

/** Burbuja flotante: tocar = escanear; mantener apretado = mostrar/ocultar el panel; arrastrar = moverla. */
@Composable
fun LiveBubble(container: AppContainer, service: LiveScanService, onDrag: (Float, Float) -> Unit) {
    val pending by container.scan.pending.collectAsState()
    val continuous by LiveScanService.continuous.collectAsState()
    val detected by LiveScanService.detected.collectAsState()
    val panel by LiveScanService.panelVisible.collectAsState()
    EmeraldTheme {
        Box(
            Modifier
                .size(62.dp)
                .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); onDrag(drag.x, drag.y) } }
                .pointerInput(panel) {
                    detectTapGestures(
                        onTap = { service.scanNow() },
                        onLongPress = { service.showPanel(!panel) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // "Esmeralda Ball" pixelada
            Box(
                Modifier.size(54.dp)
                    .clip(CircleShape) // sin esto las mitades se pintaban como un cuadrado
                    .drawBehind {
                        drawRect(Color(0xFF38C078), size = size.copy(height = size.height / 2))
                        drawRect(Color(0xFFF8F8F8), topLeft = Offset(0f, size.height / 2), size = size.copy(height = size.height / 2))
                        drawRect(Color(0xFF303030), topLeft = Offset(0f, size.height / 2 - 3.dp.toPx()), size = size.copy(height = 6.dp.toPx()))
                    }
                    .border(3.dp, Color(0xFF303030), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(16.dp).background(Color(0xFFF8F8F8), CircleShape).border(3.dp, Color(0xFF303030), CircleShape))
            }
            when {
                pending > 0 -> Badge("…", Pal.Blue, Modifier.align(Alignment.TopEnd))
                continuous -> Badge(detected.toString(), Pal.Dynamax, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier) {
    Box(modifier.background(color, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp)) {
        GbaText(text, size = 12.sp, color = Pal.TextLight)
    }
}

/** Panel con el último Pokémon leído: IV, ranks PvP y acciones rápidas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LivePanel(container: AppContainer, service: LiveScanService) {
    val drafts by container.scan.drafts.collectAsState()
    val latestKey by container.scan.latestKey.collectAsState()
    val failures by container.scan.failures.collectAsState()
    val pending by container.scan.pending.collectAsState()
    val status by LiveScanService.status.collectAsState()
    val continuous by LiveScanService.continuous.collectAsState()
    val detected by LiveScanService.detected.collectAsState()
    val game by container.game.state.collectAsState()
    var saved by remember { mutableStateOf<String?>(null) }
    val draft = drafts.firstOrNull { it.key == latestKey }

    EmeraldTheme {
        ProvideContainer(container) {
            WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp), padding = 8.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GbaText("ESCANEO EN VIVO", size = 15.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.size(40.dp).clickable { service.showPanel(false) }, contentAlignment = Alignment.Center) {
                        GbaText("×", size = 18.sp)
                    }
                }
                val info = status ?: when {
                    pending > 0 -> "Leyendo…"
                    saved != null -> saved
                    draft == null && failures.isNotEmpty() -> "No se reconoció: ${failures.last().reason}"
                    draft == null -> "Abrir un Pokémon en el juego y tocar la burbuja. La valoración da los IV exactos."
                    else -> null
                }
                info?.let { GbaText(it, size = 13.sp, color = Pal.Muted) }

                val ready = game as? GameState.Ready
                if (draft != null && ready != null) {
                    val p = draft.pokemon
                    val s = draft.result.species
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s != null) Sprite(p, s, 56.dp)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            GbaText(s?.displayName ?: "¿Especie?", size = 16.sp)
                            GbaText("PC ${draft.result.cp ?: "?"} · Nv ${fmtLevel(p.level)}" + (p.hp?.let { " · PS $it" } ?: ""), size = 13.sp)
                            GbaText(
                                when {
                                    draft.result.candidates.isEmpty() -> "IV sin solución: revisar en la app."
                                    draft.result.ivExact -> "IV ${p.ivAtk}/${p.ivDef}/${p.ivSta} (${p.ivPercent}%), exactos"
                                    else -> "IV ${draft.result.ivRangeText} · ${draft.result.candidates.size} opciones: falta la valoración"
                                },
                                size = 13.sp, color = if (draft.result.ivExact) Pal.Text else Pal.Warn,
                            )
                            if (draft.result.screen != ScreenType.APPRAISAL && draft.sources < 2 && draft.result.fast == null)
                                GbaText("Ataques fuera de pantalla: bajar la ficha.", size = 11.sp, color = Pal.Muted)
                        }
                    }
                    draft.result.problem?.let { GbaText(it, size = 12.sp, color = Pal.Warn) }
                    // Ranks PvP rápidos (también como su evolución)
                    val (pvp, _) = rememberComputed(p, ready.reco) {
                        League.entries.mapNotNull { lg ->
                            ready.reco.pvpCandidates(lg, listOf(p.copy(uid = "live"))).firstOrNull()?.let { c ->
                                "${lg.label}: IV #${c.iv.rank} · meta #${c.metaRank}" + if (c.needsEvolve) " (como ${c.species.displayName})" else ""
                            }
                        }
                    }
                    pvp?.forEach { GbaText(it, size = 12.sp) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)) {
                        PixelButton("GUARDAR", {
                            val ok = container.scan.saveOne(draft.key, container.collection)
                            saved = when {
                                ok != null -> "${s?.displayName ?: "Pokémon"} guardado en la caja."
                                draft.result.cp == null -> "Falta el PC: escanear la valoración o cargarlo en REVISAR."
                                else -> "No se pudo guardar."
                            }
                        }, color = Pal.Blue, enabled = s != null)
                        PixelButton("DESCARTAR", { container.scan.remove(draft.key); saved = null }, color = Pal.Muted)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp)) {
                    PixelButton("ESCANEAR", { saved = null; service.scanNow() })
                    ToggleChip("Continuo" + if (continuous) " ($detected)" else "", continuous, { service.setContinuous(it) })
                    PixelButton("REVISAR (${drafts.size})", { service.openApp() }, color = Pal.Header)
                    PixelButton("DETENER", { LiveScanService.stop(service) }, color = Pal.Red)
                }
            }
        }
    }
}
