package com.esmeraldago.app.data

import android.content.Context
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.data.RegionalGroup
import com.esmeraldago.core.data.Regionals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pokémon regionales según Bulbapedia. La lista cambia poco (algún intercambio por año): se guarda una semana y,
 * sin conexión, se usa la última guardada.
 */
class RegionalRepository(context: Context) {
    @Serializable
    private data class Cached(val at: Long, val groups: List<RegionalGroup>)

    private val file = File(context.filesDir, "regionals.json")
    private var memory: Cached? = null
    private val lock = Mutex()

    /** Las filas y cuándo se consultaron. */
    suspend fun load(force: Boolean = false): Result<Pair<List<RegionalGroup>, Long>> = withContext(Dispatchers.IO) {
        lock.withLock {
            val cached = memory ?: runCatching { GameDataSources.json.decodeFromString(Cached.serializer(), file.readText()) }.getOrNull()
            if (!force && cached != null && System.currentTimeMillis() - cached.at < WEEK) {
                memory = cached
                return@withContext Result.success(cached.groups to cached.at)
            }
            runCatching {
                val groups = Regionals.parse(Regionals.wikitextOf(fetch(Regionals.URL)))
                if (groups.size < 10) throw IOException("La página cambió de formato")
                val fresh = Cached(System.currentTimeMillis(), groups)
                memory = fresh
                file.writeText(GameDataSources.json.encodeToString(Cached.serializer(), fresh))
                groups to fresh.at
            }.recoverCatching { e -> cached?.let { it.groups to it.at } ?: throw e }
        }
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "EsmeraldaGO (personal app; github.com/Gaspilabastie/EsmeraldaGO)")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val WEEK = 7 * 24 * 60 * 60 * 1000L
    }
}
