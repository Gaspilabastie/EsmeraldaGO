package com.esmeraldago.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.esmeraldago.app.data.CollectionRepository
import com.esmeraldago.app.data.GameRepository
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.data.RaidRepository
import com.esmeraldago.app.data.SettingsRepository
import com.esmeraldago.app.data.SpritePrefetcher
import com.esmeraldago.app.data.UpdateRepository
import com.esmeraldago.app.data.onUnmeteredNetwork
import com.esmeraldago.app.scan.OcrScanner
import com.esmeraldago.app.scan.ScanSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EsmeraldaApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.game.load()
        // En los tests (Robolectric) no se toca la red.
        if (android.os.Build.FINGERPRINT == "robolectric") return
        // Recién actualizada: se cierra el aviso de "se actualizó" y se ofrecen las novedades.
        getSystemService(android.app.NotificationManager::class.java).cancel(com.esmeraldago.app.data.UpdatedReceiver.NOTIFICATION_ID)
        container.updates.takeFreshNotes()?.let { (v, _) ->
            container.notify("EsmeraldaGO se actualizó a $v.", "NOVEDADES") { container.pendingNavigation.value = "changelog" }
        }
        // Versión nueva: con Wi-Fi se baja e instala sola (la pantalla de inicio muestra el avance); con datos
        // móviles, se ofrece.
        container.updates.check(quiet = true) { r ->
            if (container.settings.state.value.autoUpdate && onUnmeteredNetwork()) container.updates.download(r)
            else container.notify("Hay una versión nueva de EsmeraldaGO (${r.version}).", "ACTUALIZAR") { container.updates.download(r) }
        }
        container.scope.launch {
            val ready = container.game.state.first { it !is GameState.Loading } as? GameState.Ready ?: return@launch
            // Datos del juego viejos (temporada nueva, ataques rebalanceados): se actualizan solos con Wi-Fi.
            container.game.refreshIfStale()
            runCatching { SpritePrefetcher.run(this@EsmeraldaApp, ready.idx) }
        }
    }

    /**
     * Los sprites vienen de raw.githubusercontent.com, que pide revalidar cada 5 minutos: Coil le hacía caso
     * y, pasado ese rato, cada sprite de una lista volvía a consultar la red aunque estuviera guardado (las
     * listas tardaban en cargar). Un sprite no cambia: el caché en disco vale para siempre.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("sprites")).maxSizeBytes(200L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()
}

/** Mensaje breve estilo diálogo de Esmeralda, con acción opcional (ej. DESHACER). */
data class Notice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null, val id: Long = System.nanoTime())

/** Dependencias compartidas de toda la app. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val game = GameRepository(app, scope, settings)
    val collection = CollectionRepository(app, scope)
    val raids = RaidRepository(scope)
    val events = com.esmeraldago.app.data.EventsRepository(app, scope)
    val pokebattler = com.esmeraldago.app.data.PokebattlerRepository(app)
    val updates = UpdateRepository(app, scope)
    val ocr = OcrScanner(app)
    val scan = ScanSession(
        scope, ocr, game,
        levelCap = { settings.state.value.pokemonLevelCap },
    )

    /** Pantalla a la que hay que ir (por ejemplo "scan" al recibir capturas compartidas). */
    val pendingNavigation = MutableStateFlow<String?>(null)

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    fun notify(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _notice.value = Notice(text, actionLabel, action)
    }
    fun dismissNotice() { _notice.value = null }
}
