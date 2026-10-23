package com.esmeraldago.app.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.util.AtomicFile
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.GameData
import com.esmeraldago.core.reco.Recommender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed interface GameState {
    data object Loading : GameState
    data class Ready(val idx: GameIndex, val reco: Recommender) : GameState
    data class Error(val message: String) : GameState
}

class GameRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow<GameState>(GameState.Loading)
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    /** Serializa carga / actualización / restauración para que no se pisen. */
    private val lock = Mutex()
    private val local = AtomicFile(File(context.filesDir, "gamedata.json"))
    private val prefs = context.getSharedPreferences("gamedata", Context.MODE_PRIVATE)

    private val appVersion: Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    val usingDownloadedData get() = local.baseFile.exists()

    fun load() = scope.launch(Dispatchers.IO) {
        lock.withLock { _state.value = loadLocked() }
    }

    private fun loadLocked(): GameState {
        // Los datos descargados con una versión anterior de la app pueden ser de otro esquema: se descartan.
        if (local.baseFile.exists() && prefs.getLong("forAppVersion", -1) != appVersion) local.delete()
        if (local.baseFile.exists()) {
            try {
                val data = GameDataSources.decode(local.readFully().toString(Charsets.UTF_8))
                GameDataSources.validate(data)
                return ready(data)
            } catch (t: Throwable) {
                // Archivo dañado: se aparta y se usan los datos incluidos en la app.
                local.baseFile.renameTo(File(local.baseFile.path + ".bad"))
                _updateStatus.value = "Los datos descargados estaban dañados; se usan los incluidos en la app."
            }
        }
        return try {
            ready(GameDataSources.decode(context.assets.open("gamedata.json").bufferedReader().use { it.readText() }))
        } catch (t: Throwable) {
            GameState.Error(t.message ?: "No se pudieron cargar los datos")
        }
    }

    private fun ready(data: GameData): GameState.Ready {
        val idx = GameIndex(data, includeUnreleased = settings.state.value.showUnreleased)
        return GameState.Ready(idx, Recommender(idx, settings.state.value.pvpMaxLevel))
    }

    /** Rehace el índice cuando cambia "Mostrar no lanzados" (los rankings y la Pokédex dependen de él). */
    fun refreshIndex() = scope.launch(Dispatchers.Default) {
        lock.withLock { (_state.value as? GameState.Ready)?.let { _state.value = ready(it.idx.data) } }
    }

    /** Recalcula el recomendador cuando cambian los ajustes (nivel máximo PvP). */
    fun refreshRecommender() {
        _state.update { s -> if (s is GameState.Ready) GameState.Ready(s.idx, Recommender(s.idx, settings.state.value.pvpMaxLevel)) else s }
    }

    /** Descarga las fuentes actualizadas (PvPoke + PokeMiners), valida y reemplaza los datos. */
    fun update() = scope.launch(Dispatchers.IO) {
        // compareAndSet: dos toques rápidos no encolan dos descargas de 22 MB.
        if (!_updating.compareAndSet(expect = false, update = true)) return@launch
        lock.withLock {
            try {
                val dir = File(context.cacheDir, "gamedata-dl")
                val data = GameDataSources.downloadAndBuild(dir, maxAgeMs = 30 * 60_000L) { _updateStatus.value = it }
                _updateStatus.value = "Guardando…"
                // Primero la marca de versión (síncrona): si el proceso muere después, el archivo nuevo no se descarta.
                prefs.edit().putLong("forAppVersion", appVersion).commit()
                val out = local.startWrite()
                try {
                    out.write(GameDataSources.encode(data).toByteArray(Charsets.UTF_8))
                    local.finishWrite(out)
                } catch (t: Throwable) {
                    local.failWrite(out); throw t
                }
                dir.deleteRecursively()
                _state.value = ready(data)
                _updateStatus.value = "Datos actualizados (${data.species.size} especies)"
            } catch (t: Throwable) {
                _updateStatus.value = "No se actualizó (se conservan los datos actuales): ${t.message}"
            } finally {
                _updating.value = false
            }
        }
    }

    fun resetToBundled() = scope.launch(Dispatchers.IO) {
        lock.withLock {
            local.delete()
            _state.value = loadLocked()
            _updateStatus.value = "Se restauraron los datos incluidos en la app."
        }
    }
}
