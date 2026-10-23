package com.esmeraldago.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.esmeraldago.app.BuildConfig
import com.esmeraldago.core.data.GameDataSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** update.json que release.ps1 adjunta a cada release. */
@Serializable
private data class UpdateManifest(val version: String, val notes: String = "", val apk: String, val size: Long = 0)

data class AppRelease(val version: String, val notes: String, val apkUrl: String, val size: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class Ready(val release: AppRelease, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Busca la última release del repo de GitHub ([BuildConfig.UPDATE_REPO]), descarga su APK y abre el
 * instalador de Android. Como el APK está firmado con la misma clave, se instala encima y se conservan los datos.
 */
class UpdateRepository(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val repoUrl: String get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases"

    /** [quiet]: chequeo automático al abrir la app; ante error no muestra nada. */
    fun check(quiet: Boolean = false, onAvailable: (AppRelease) -> Unit = {}) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            if (!quiet) _state.value = UpdateState.Checking
            runCatching { latest() }
                .onSuccess { r ->
                    if (isNewer(r.version, currentVersion)) {
                        _state.value = UpdateState.Available(r)
                        onAvailable(r)
                    } else if (!quiet) _state.value = UpdateState.UpToDate
                }
                .onFailure { if (!quiet) _state.value = UpdateState.Failed("No pude consultar GitHub: ${it.message}") }
        }
    }

    fun download(release: AppRelease) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = UpdateState.Downloading(release, 0f)
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "EsmeraldaGO-${release.version}.apk")
            runCatching { fetch(release, apk) }
                .onSuccess { _state.value = UpdateState.Ready(release, apk); install(apk) }
                .onFailure { apk.delete(); _state.value = UpdateState.Failed("Falló la descarga: ${it.message}") }
        }
    }

    /** Abre el instalador. La primera vez Android pide permitir "instalar apps desconocidas" desde EsmeraldaGO. */
    fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Lee el update.json de la última release. Se baja de github.com (redirección de releases/latest/download),
     * no de api.github.com, que limita a 60 consultas por hora por IP sin login.
     */
    private fun latest(): AppRelease {
        val base = "https://github.com/${BuildConfig.UPDATE_REPO}/releases"
        val text = GameDataSources.open("$base/latest/download/update.json").use { it.reader().readText() }
        val m = GameDataSources.json.decodeFromString(UpdateManifest.serializer(), text)
        return AppRelease(m.version, m.notes.trim(), "$base/download/v${m.version}/${m.apk}", m.size)
    }

    private fun fetch(release: AppRelease, dest: File) {
        val part = File(dest.parentFile, dest.name + ".part")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "EsmeraldaGO/${currentVersion}")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastShown = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val p = if (total > 0) read.toFloat() / total else 0f
                        if (p - lastShown >= 0.01f) { lastShown = p; _state.value = UpdateState.Downloading(release, p) }
                    }
                    if (total > 0 && read != total) throw IOException("descarga incompleta")
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(dest)) throw IOException("no se pudo guardar el APK")
    }

    companion object {
        /** Compara "0.10.2" vs "0.9.7" por partes numéricas. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}
