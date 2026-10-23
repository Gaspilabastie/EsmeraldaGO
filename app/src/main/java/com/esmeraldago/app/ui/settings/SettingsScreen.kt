package com.esmeraldago.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalUriHandler
import com.esmeraldago.app.data.AppRelease
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.data.UpdateState
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Labeled
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.WindowBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Marco de campo de texto (borde oscuro + fondo blanco), como en Editar. */
private fun Modifier.inputFrame(): Modifier = this
    .heightIn(min = 44.dp)
    .background(Pal.FrameOuter, com.esmeraldago.app.ui.theme.SmallPixelShape)
    .padding(2.dp)
    .background(androidx.compose.ui.graphics.Color.White, com.esmeraldago.app.ui.theme.SmallPixelShape)
    .padding(8.dp)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val settings by container.settings.state.collectAsState()
    val game by container.game.state.collectAsState()
    val status by container.game.updateStatus.collectAsState()
    val updating by container.game.updating.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TitleBar("Opciones", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppUpdateBox()

            WindowBox(Modifier.fillMaxWidth()) {
                SectionTitle("Datos del juego")
                (game as? GameState.Ready)?.idx?.data?.let { d ->
                    Labeled("Especies", d.species.size.toString())
                    Labeled("Movimientos", d.moves.size.toString())
                    Labeled("Dinamax / Gigamax", "${d.species.count { it.dynamax }} / ${d.species.count { it.gigantamax }}")
                    Labeled("Generados", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(d.generatedAt)))
                    Labeled("Origen", if (container.game.usingDownloadedData) "Descargados" else "Incluidos en la app")
                }
                GbaText("Actualizá cuando cambie la temporada, salgan movimientos nuevos o se rebalanceen ataques.",
                    size = 12.sp, color = Pal.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    PixelButton(if (updating) "ACTUALIZANDO…" else "ACTUALIZAR", { container.game.update() }, enabled = !updating)
                    if (container.game.usingDownloadedData) PixelButton("RESTAURAR", { container.game.resetToBundled() }, color = Pal.Muted)
                }
                status?.let { GbaText(it, size = 13.sp) }
                GbaText("Descarga ~22 MB (PvPoke + Game Master).", size = 11.sp, color = Pal.Muted)
            }

            WindowBox(Modifier.fillMaxWidth()) {
                SectionTitle("Entrenador")
                GbaText("Nivel de entrenador (opcional)", size = 14.sp)
                var text by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf(settings.trainerLevel.takeIf { it > 0 }?.toString() ?: "")
                }
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).inputFrame()
                ) {
                    if (text.isEmpty()) GbaText("ej. 42", size = 15.sp, color = Pal.Muted)
                    androidx.compose.foundation.text.BasicTextField(
                        text,
                        { v ->
                            text = v.filter(Char::isDigit).take(2)
                            container.settings.update { it.copy(trainerLevel = text.toIntOrNull()?.coerceIn(1, 80) ?: 0) }
                        },
                        textStyle = com.esmeraldago.app.ui.theme.gbaStyle(16.sp), singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                GbaText(
                    "Acota el nivel máximo de tus Pokémon al escanear (tu nivel + 10, máx. 50). No es imprescindible: " +
                        "el nivel lo fija sobre todo el costo de polvo del botón POWER UP.",
                    size = 12.sp, color = Pal.Muted,
                )
            }

            WindowBox(Modifier.fillMaxWidth()) {
                SectionTitle("Cálculos")
                GbaText("Nivel para rankings PvE / Dinamax", size = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(40.0, 50.0).forEach { l ->
                        ToggleChip("Nv ${l.toInt()}", settings.rankLevel == l, { container.settings.update { it.copy(rankLevel = l) } })
                    }
                }
                GbaText("Nivel máximo para PvP y consejos", size = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(40.0 to "40 (sin caramelos XL)", 50.0 to "50", 51.0 to "51 (mejor compañero)").forEach { (lvl, label) ->
                        ToggleChip(label, settings.pvpMaxLevel == lvl, {
                            container.settings.update { it.copy(pvpMaxLevel = lvl) }; container.game.refreshRecommender()
                        })
                    }
                }
                GbaText("Con 40, los rankings de IV y los consejos no asumen que tenés caramelos XL.", size = 12.sp, color = Pal.Muted)
                GbaText("Nivel de Max Attack (rankings Dinamax)", size = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..3).forEach { l ->
                        ToggleChip("Nv $l", settings.maxMoveLevel == l, { container.settings.update { it.copy(maxMoveLevel = l) } })
                    }
                }
            }

            WindowBox(Modifier.fillMaxWidth()) {
                SectionTitle("Créditos")
                GbaText(
                    "• Rankings PvP y movesets: PvPoke (licencia MIT)\n" +
                        "• Game Master: PokeMiners\n" +
                        "• Incursiones actuales: LeekDuck vía ScrapedDuck\n" +
                        "• Sprites: PokeAPI (Pokémon Esmeralda / Negro y Blanco)\n" +
                        "• Fuente: Galmuri11 de Lee Minseo (OFL)\n" +
                        "• Fórmula PvE: DPS/TDO \"comprehensive\" de GamePress\n\n" +
                        "App personal, no afiliada a Niantic, Nintendo ni The Pokémon Company.",
                    size = 12.sp,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppUpdateBox() {
    val updates = LocalContainer.current.updates
    val state by updates.state.collectAsState()
    val uriHandler = LocalUriHandler.current

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Versión de la app")
        Labeled("Instalada", updates.currentVersion)
        when (val s = state) {
            UpdateState.Idle -> {}
            UpdateState.Checking -> GbaText("Buscando…", size = 13.sp)
            UpdateState.UpToDate -> GbaText("Tenés la última versión.", size = 13.sp)
            is UpdateState.Failed -> GbaText(s.message, size = 13.sp, color = Pal.Red)
            is UpdateState.Available -> ReleaseInfo(s.release)
            is UpdateState.Downloading -> {
                ReleaseInfo(s.release)
                GbaText("Descargando… ${(s.progress * 100).toInt()}%", size = 13.sp)
            }
            is UpdateState.Ready -> ReleaseInfo(s.release)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp)) {
            when (val s = state) {
                is UpdateState.Available -> PixelButton("DESCARGAR E INSTALAR", { updates.download(s.release) }, color = Pal.Gold)
                is UpdateState.Downloading -> PixelButton("DESCARGANDO…", {}, enabled = false)
                is UpdateState.Ready -> PixelButton("INSTALAR", { updates.install(s.apk) }, color = Pal.Gold)
                else -> PixelButton(if (state == UpdateState.Checking) "BUSCANDO…" else "BUSCAR ACTUALIZACIÓN",
                    { updates.check() }, enabled = state != UpdateState.Checking)
            }
            PixelButton("HISTORIAL", { uriHandler.openUri(updates.repoUrl) }, color = Pal.Muted)
        }
        GbaText(
            "Se instala encima de la actual, así que no perdés tu colección. La primera vez Android te pide " +
                "permitir que EsmeraldaGO instale apps.",
            size = 11.sp, color = Pal.Muted,
        )
    }
}

@Composable
private fun ReleaseInfo(r: AppRelease) {
    GbaText("Nueva versión: ${r.version}", size = 14.sp, color = Pal.HeaderDark)
    if (r.notes.isNotBlank()) GbaText(r.notes, size = 12.sp)
}
