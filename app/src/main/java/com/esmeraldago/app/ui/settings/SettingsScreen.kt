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
import com.esmeraldago.app.live.LiveGestureService
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
fun SettingsScreen(onBack: () -> Unit, onChangelog: () -> Unit = {}) {
    val container = LocalContainer.current
    val settings by container.settings.state.collectAsState()
    val game by container.game.state.collectAsState()
    val status by container.game.updateStatus.collectAsState()
    val updating by container.game.updating.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TitleBar("Opciones", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppUpdateBox(onChangelog)
            BackupBox()

            LiveScanBox()

            FeedbackBox()

            WindowBox(Modifier.fillMaxWidth()) {
                SectionTitle("Datos del juego")
                (game as? GameState.Ready)?.idx?.data?.let { d ->
                    Labeled("Especies", d.species.size.toString())
                    Labeled("Movimientos", d.moves.size.toString())
                    Labeled("Dinamax / Gigamax", "${d.species.count { it.dynamax }} / ${d.species.count { it.gigantamax }}")
                    Labeled("Generados", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(d.generatedAt)))
                    Labeled("Origen", if (container.game.usingDownloadedData) "Descargados" else "Incluidos en la app")
                }
                GbaText("Conviene actualizar al cambiar la temporada o los ataques.", size = 12.sp, color = Pal.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    PixelButton(if (updating) "ACTUALIZANDO…" else "ACTUALIZAR", { container.game.update() }, enabled = !updating)
                    if (container.game.usingDownloadedData) PixelButton("RESTAURAR", { container.game.resetToBundled() }, color = Pal.Muted)
                }
                status?.let { GbaText(it, size = 13.sp) }
                GbaText("Descarga ~22 MB (PvPoke + Game Master).", size = 11.sp, color = Pal.Muted)
            }

            WindowBox(Modifier.fillMaxWidth()) {
                SectionTitle("Cálculos")
                GbaText("Nivel para rankings PvE / Dinamax", size = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(40.0, 50.0).forEach { l ->
                        ToggleChip("Nv ${l.toInt()}", settings.rankLevel == l, { container.settings.update { it.copy(rankLevel = l) } })
                    }
                }
                GbaText("Nivel máximo al subir Pokémon (PvP y consejos)", size = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(40.0 to "40 (sin caramelos XL)", 50.0 to "50", 51.0 to "51 (Mejor Compañero)").forEach { (lvl, label) ->
                        ToggleChip(label, settings.pvpMaxLevel == lvl, {
                            container.settings.update { it.copy(pvpMaxLevel = lvl) }; container.game.refreshRecommender()
                        })
                    }
                }
            }

            WindowBox(Modifier.fillMaxWidth()) {
                SectionTitle("Créditos")
                GbaText(
                    "• Rankings PvP y movesets: PvPoke (licencia MIT)\n" +
                        "• Game Master: PokeMiners\n" +
                        "• Incursiones y eventos: LeekDuck vía ScrapedDuck\n" +
                        "• Counters de incursión: Pokébattler\n" +
                        "• Sprites: PokeAPI (Pokémon Esmeralda / Negro y Blanco)\n" +
                        "• Fuente: Galmuri11 de Lee Minseo (OFL)\n" +
                        "• Fórmula PvE: DPS/TDO \"comprehensive\" de GamePress\n\n" +
                        "App personal, no afiliada a Niantic, Nintendo ni The Pokémon Company. " +
                        "Automatizar acciones en el juego va contra los términos de Niantic.",
                    size = 12.sp,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppUpdateBox(onChangelog: () -> Unit) {
    val container = LocalContainer.current
    val updates = container.updates
    val state by updates.state.collectAsState()
    val settings by container.settings.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    com.esmeraldago.app.ui.home.AskNotificationsWhileUpdating(state)

    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Versión de la app")
        Labeled("Instalada", updates.currentVersion)
        updates.installedNotes?.takeIf { it.isNotBlank() }?.let {
            GbaText("Novedades de esta versión", size = 13.sp, modifier = Modifier.padding(top = 4.dp))
            GbaText(it, size = 12.sp, color = Pal.Muted)
        }
        when (val s = state) {
            UpdateState.Idle -> {}
            UpdateState.Checking -> GbaText("Buscando…", size = 13.sp)
            UpdateState.UpToDate -> GbaText("Versión al día.", size = 13.sp)
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
            PixelButton("NOVEDADES", onChangelog, color = Pal.Muted)
        }
        ToggleChip("Actualizar sola con Wi-Fi", settings.autoUpdate, { v -> container.settings.update { it.copy(autoUpdate = v) } },
            Modifier.padding(top = 6.dp))
        GbaText(
            "Se instala sobre la versión actual, sin perder datos. La primera vez Android pide permiso para instalar; " +
                "después se actualiza sin preguntar.",
            size = 11.sp, color = Pal.Muted,
        )
    }
}

/**
 * Copia de la caja en un archivo (Drive, Descargas…): para pasarla a otro teléfono o no perderla si hubiera que
 * reinstalar. Android además hace su copia automática en la cuenta de Google si está activada.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BackupBox() {
    val container = LocalContainer.current
    val owned by container.collection.items.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(container.collection.exportJson().toByteArray()) } }
            .onSuccess { container.notify("Copia guardada: ${owned.size} Pokémon.") }
            .onFailure { container.notify("No se pudo guardar la copia: ${it.message}") }
    }
    val load = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { container.collection.importJson(context.contentResolver.openInputStream(uri)!!.use { it.reader().readText() }) }
            .onSuccess { n -> container.notify(if (n == 0) "La copia no tenía Pokémon nuevos." else "Se agregaron $n Pokémon de la copia.") }
            .onFailure { container.notify("El archivo elegido no es una copia de seguridad de EsmeraldaGO.") }
    }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Copia de seguridad")
        GbaText("Guarda la caja en un archivo, para restaurarla o pasarla a otro teléfono.", size = 12.sp, color = Pal.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            PixelButton("GUARDAR COPIA", { save.launch("esmeraldago-caja.json") }, enabled = owned.isNotEmpty())
            PixelButton("CARGAR COPIA", { load.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, color = Pal.Muted)
        }
    }
}

/**
 * Reportar un bug o sugerir algo: abre en el navegador un "issue" de GitHub ya completado con la versión y el
 * teléfono (editable antes de enviar). Se envía con tu cuenta de GitHub; la app no guarda ninguna clave.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FeedbackBox() {
    val updates = LocalContainer.current.updates
    val uriHandler = LocalUriHandler.current
    fun open(kind: String, title: String, label: String, body: String) {
        val info = "\n\n---\nEsmeraldaGO ${updates.currentVersion} · Android ${android.os.Build.VERSION.RELEASE} · " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val q = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        uriHandler.openUri("${updates.issuesUrl}/new?labels=${q(label)}&title=${q("$kind: $title")}&body=${q(body + info)}")
    }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Reportar o sugerir")
        GbaText("Avisar un error o proponer una mejora. Se abre GitHub con la versión y el modelo de teléfono ya cargados.",
            size = 12.sp, color = Pal.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp)) {
            PixelButton("REPORTAR BUG", {
                open("Bug", "", "bug", "**Qué pasó:**\n\n**Qué se esperaba:**\n\n**Pasos para repetirlo:**\n1. \n")
            }, color = Pal.Red)
            PixelButton("SUGERIR", {
                open("Idea", "", "enhancement", "**Idea:**\n\n**Para qué serviría:**\n")
            }, color = Pal.Gold)
        }
    }
}

/** Servicio de accesibilidad del escaneo en vivo: bajar la ficha para leer ataques y ocultar la burbuja fuera del juego. */
@Composable
private fun LiveScanBox() {
    val container = LocalContainer.current
    val settings by container.settings.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // El usuario lo activa en Ajustes del sistema: se vuelve a mirar al regresar a la app.
    var enabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(LiveGestureService.isEnabled(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) enabled = LiveGestureService.isEnabled(context)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    WindowBox(Modifier.fillMaxWidth()) {
        SectionTitle("Escaneo en vivo")
        GbaText("Baja la ficha para leer los ataques, abre la valoración y oculta la burbuja fuera de Pokémon GO.",
            size = 12.sp, color = Pal.Muted)
        // Android no deja que una app active su propio servicio de accesibilidad: la primera vez se abre su página en
        // Ajustes; ya activado, se prende y apaga acá mismo.
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!enabled) PixelButton("ACTIVAR", { openAccessibilityPage(context) })
            else ToggleChip("Escaneo asistido", settings.autoScroll, { v -> container.settings.update { it.copy(autoScroll = v) } })
        }
        GbaText("Lo que se lee completo (especie, PC e IV exactos) se guarda en la caja al tocar la burbuja.",
            size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 6.dp)) {
            ToggleChip("Guardar automáticamente", settings.autoSave, { v -> container.settings.update { it.copy(autoSave = v) } })
        }
        LiveTestExport()
    }
}

/**
 * TEMPORAL, mientras se ajusta el escaneo en vivo: guarda el registro de las pruebas (pasos, imágenes y texto leído)
 * en un .zip donde se elija, por ejemplo Google Drive, y lo borra del teléfono para que la próxima prueba empiece limpia.
 */
@Composable
private fun LiveTestExport() {
    val container = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)!!.use { com.esmeraldago.app.live.LiveTestLog.exportZip(context, it) } }
            .onSuccess { n -> com.esmeraldago.app.live.LiveTestLog.clear(context); container.notify("Prueba exportada: $n imágenes.") }
            .onFailure { container.notify("No se pudo exportar la prueba: ${it.message}") }
    }
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
    Row(Modifier.padding(top = 6.dp)) {
        PixelButton("EXPORTAR PRUEBA", { save.launch("esmeraldago-prueba-$stamp.zip") }, color = Pal.Muted)
    }
}

@Composable
private fun ReleaseInfo(r: AppRelease) {
    GbaText("Nueva versión: ${r.version}", size = 14.sp, color = Pal.HeaderDark)
    if (r.notes.isNotBlank()) GbaText(r.notes, size = 12.sp)
}

/**
 * Abre la página del servicio de accesibilidad de EsmeraldaGO en Ajustes (Android 13+ lo permite con su
 * componente); si el sistema no la ofrece, la lista general de Accesibilidad.
 */
internal fun openAccessibilityPage(context: android.content.Context) {
    val component = android.content.ComponentName(context, LiveGestureService::class.java).flattenToString()
    val details = android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(android.content.Intent.EXTRA_COMPONENT_NAME, component)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(details) }.onFailure {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}