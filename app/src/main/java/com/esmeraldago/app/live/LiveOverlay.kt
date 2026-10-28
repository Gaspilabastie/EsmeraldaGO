package com.esmeraldago.app.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.esmeraldago.app.scan.DraftStatus
import com.esmeraldago.app.scan.DupKind
import com.esmeraldago.app.ui.common.ProvideContainer
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.displayName
import com.esmeraldago.app.ui.theme.EmeraldTheme
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.model.OwnedPokemon

/** Color del IV% (el mismo que en la caja): verde los casi perfectos; el resto, texto normal. */
internal fun ivColor(p: OwnedPokemon) = if (p.ivPercent >= 98) Pal.Good else Pal.Text

/** La pregunta cuando se parece a uno de la caja. */
internal fun duplicateQuestion(kind: DupKind?, name: String?): String {
    val n = name ?: "Pokémon"
    return when (kind) {
        DupKind.EVOLVED -> "¿Es el $n de la caja, evolucionado?"
        DupKind.PURIFIED -> "¿Es el $n oscuro de la caja, ya purificado?"
        else -> "¿Es el mismo $n de la caja?"
    }
}

internal fun updatedText(change: String?) = "Ya estaba en la caja; se actualizó" + (change?.let { ": $it." } ?: ".")

/** Burbuja flotante: tocar = escanear; mantener apretado = menú; arrastrar = moverla. */
@Composable
fun LiveBubble(service: LiveScanService, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val mark by LiveScanService.mark.collectAsState()
    EmeraldTheme {
        Box(
            Modifier
                .size(62.dp)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = onDragEnd) { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { service.scanNow() }, onLongPress = { service.toggleMenu() })
                },
            contentAlignment = Alignment.Center,
        ) {
            // "Esmeralda Ball" pixelada; el centro muestra cómo terminó el último escaneo.
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
                if (mark == null) {
                    Box(Modifier.size(16.dp).background(Color(0xFFF8F8F8), CircleShape).border(3.dp, Color(0xFF303030), CircleShape))
                } else {
                    Box(
                        Modifier.size(26.dp).background(Color(0xFFF8F8F8), CircleShape).border(2.dp, Color(0xFF303030), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { GbaText(mark!!, size = 15.sp, color = Color(0xFF303030)) }
                }
            }
        }
    }
}

/** Menú de la burbuja (mantener apretado): modo continuo y resultados. Detener queda en la notificación y en la app. */
@Composable
fun LiveMenu(container: AppContainer, service: LiveScanService) {
    val continuous by LiveScanService.continuous.collectAsState()
    val drafts by container.scan.drafts.collectAsState()
    EmeraldTheme {
        WindowBox(Modifier.width(196.dp), padding = 4.dp) {
            MenuRow("CONTINUO", if (continuous) "SÍ" else "NO") { service.setContinuous(!continuous); service.toggleMenu() }
            MenuRow("RESULTADOS" + if (drafts.isNotEmpty()) " (${drafts.size})" else "", null) { service.toggleMenu(); service.openApp() }
        }
    }
}

@Composable
private fun MenuRow(label: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GbaText(label, size = 16.sp, modifier = Modifier.weight(1f))
        value?.let { GbaText(it, size = 14.sp, color = Pal.Muted) }
    }
}

/**
 * Cuadro con el resultado del último escaneo, a la altura del modelo 3D (no tapa el nombre ni el PC). Queda hasta el
 * próximo escaneo o hasta cerrarlo. Lleva el nombre del Pokémon: así queda claro a cuál se aplica DESHACER.
 */
@Composable
fun LiveResult(container: AppContainer, service: LiveScanService) {
    val drafts by container.scan.drafts.collectAsState()
    val latestKey by container.scan.latestKey.collectAsState()
    val status by LiveScanService.status.collectAsState()
    val game by container.game.state.collectAsState()
    val boxItems by container.collection.items.collectAsState()
    val draft = drafts.firstOrNull { it.key == latestKey }
    val idx = (game as? GameState.Ready)?.idx

    EmeraldTheme {
        ProvideContainer(container) {
            WindowBox(Modifier.fillMaxWidth(), padding = 8.dp) {
                if (draft == null || idx == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GbaText(status ?: "Sin lectura.", size = 14.sp, modifier = Modifier.weight(1f))
                        CloseButton { service.showPanel(false) }
                    }
                    return@WindowBox
                }
                val p = draft.pokemon
                val s = draft.result.species
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s != null) Sprite(p, s, 48.dp)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        GbaText(s?.displayName ?: "Especie sin reconocer", size = 16.sp)
                        Row {
                            GbaText("PC ${draft.result.cp ?: "—"} · IV ", size = 14.sp)
                            GbaText(
                                if (draft.result.ivExact || draft.ivPicked) "${p.ivPercent}%" else draft.result.ivRangeText,
                                size = 14.sp, color = if (draft.result.ivExact || draft.ivPicked) ivColor(p) else Pal.Warn,
                            )
                        }
                    }
                    ToggleChip("SHINY", p.shiny, { v -> container.scan.setShiny(draft.key, v) })
                    CloseButton { service.showPanel(false) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    when (draft.status) {
                        DraftStatus.SAVED -> {
                            GbaText("Guardado en la caja.", size = 14.sp, color = Pal.Good, modifier = Modifier.weight(1f))
                            PixelButton("DESHACER", { container.scan.undo(draft.key) }, color = Pal.Muted)
                        }
                        DraftStatus.UPDATED -> {
                            GbaText(updatedText(draft.change), size = 14.sp, color = Pal.Blue, modifier = Modifier.weight(1f))
                            PixelButton("DESHACER", { container.scan.undo(draft.key) }, color = Pal.Muted)
                        }
                        DraftStatus.IN_BOX -> GbaText("Ya está en la caja.", size = 14.sp, color = Pal.Good, modifier = Modifier.weight(1f))
                        DraftStatus.READY -> {
                            GbaText("Lectura completa.", size = 14.sp, modifier = Modifier.weight(1f))
                            PixelButton("GUARDAR", { service.save(draft.key) }, color = Pal.Blue)
                        }
                        DraftStatus.INCOMPLETE -> {
                            GbaText(draft.missing ?: "", size = 14.sp, color = Pal.Warn, modifier = Modifier.weight(1f))
                            PixelButton("COMPLETAR", { service.openApp() })
                        }
                        DraftStatus.DUPLICATE -> {
                            val other = boxItems.firstOrNull { it.uid == draft.duplicateOf }
                            Column(Modifier.weight(1f)) {
                                GbaText(duplicateQuestion(draft.dupKind, other?.let { idx[it.speciesId]?.displayName }), size = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    PixelButton("ES EL MISMO", { container.scan.replace(draft.key) }, Modifier.weight(1f))
                                    PixelButton("ES OTRO", { container.scan.addAnyway(draft.key) }, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        GbaText("×", size = 20.sp)
    }
}
