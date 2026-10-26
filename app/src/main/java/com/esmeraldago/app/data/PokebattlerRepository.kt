package com.esmeraldago.app.data

import android.content.Context
import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.data.PbCounter
import com.esmeraldago.core.data.Pokebattler
import com.esmeraldago.core.model.Species
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
 * Counters de incursión de Pokébattler. Cada respuesta pesa ~1,7 MB: se guarda solo la lista resumida (unos KB)
 * y se reutiliza un día, como pide su página para desarrolladores. Una consulta por jefe y nivel.
 */
class PokebattlerRepository(context: Context) {
    @Serializable
    private data class Cached(val at: Long, val counters: List<PbCounter>)

    private val dir = File(context.cacheDir, "pokebattler").apply { mkdirs() }
    private val memory = HashMap<String, List<PbCounter>>()
    private val lock = Mutex()

    suspend fun counters(boss: Species, tier: RaidTier, level: Int): Result<List<PbCounter>> = withContext(Dispatchers.IO) {
        val key = "${boss.id}-${tier.name}-$level"
        lock.withLock {
            memory[key]?.let { return@withContext Result.success(it) }
            val file = File(dir, "$key.json")
            val cached = runCatching { GameDataSources.json.decodeFromString(Cached.serializer(), file.readText()) }.getOrNull()
            if (cached != null && System.currentTimeMillis() - cached.at < DAY) {
                memory[key] = cached.counters
                return@withContext Result.success(cached.counters)
            }
            runCatching {
                val text = Pokebattler.urls(boss, tier, level).firstNotNullOfOrNull { fetch(it) }
                    ?: throw IOException("Pokébattler no tiene a este jefe")
                Pokebattler.parse(text).also { list ->
                    memory[key] = list
                    file.writeText(GameDataSources.json.encodeToString(Cached.serializer(), Cached(System.currentTimeMillis(), list)))
                }
            }.recoverCatching { e ->
                // Sin conexión: mejor un dato viejo que ninguno.
                cached?.counters ?: throw e
            }
        }
    }

    /** null si el jefe no existe con ese id (404); otro error de red se propaga. */
    private fun fetch(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 90_000
        conn.setRequestProperty("User-Agent", Pokebattler.USER_AGENT)
        try {
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
