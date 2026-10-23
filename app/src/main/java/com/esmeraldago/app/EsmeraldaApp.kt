package com.esmeraldago.app

import android.app.Application
import com.esmeraldago.app.data.CollectionRepository
import com.esmeraldago.app.data.GameRepository
import com.esmeraldago.app.data.RaidRepository
import com.esmeraldago.app.data.SettingsRepository
import com.esmeraldago.app.data.UpdateRepository
import com.esmeraldago.app.scan.OcrScanner
import com.esmeraldago.app.scan.ScanSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EsmeraldaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.game.load()
        container.updates.check(quiet = true) { r ->
            container.notify("¡Hay una versión nueva de EsmeraldaGO (${r.version})!", "VER") {
                container.pendingNavigation.value = "settings"
            }
        }
    }
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
