package com.esmeraldago.core.data

import com.esmeraldago.core.model.GameData
import com.esmeraldago.core.model.League
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object GameDataSources {
    private const val PVPOKE = "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data"
    const val PVPOKE_GAMEMASTER = "$PVPOKE/gamemaster.min.json"
    const val POKEMINERS_GAMEMASTER = "https://raw.githubusercontent.com/PokeMiners/game_masters/master/latest/latest.json"
    const val POKEMINERS_TIMESTAMP = "https://raw.githubusercontent.com/PokeMiners/game_masters/master/latest/timestamp.txt"
    const val CURRENT_RAIDS = "https://raw.githubusercontent.com/bigfoott/ScrapedDuck/data/raids.min.json"
    /** Textos del juego en español (nombres de ataques y especies), para leer el cliente en español. */
    const val SPANISH_TEXTS = "https://raw.githubusercontent.com/PokeMiners/pogo_assets/master/Texts/Latest%20APK/JSON/i18n_spanish.json"

    fun rankings(league: League) = "$PVPOKE/rankings/all/overall/rankings-${league.cap}.json"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** Abre una URL; ante error HTTP desconecta y lanza. Quien la llama debe cerrar el stream. */
    fun open(url: String): InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "EsmeraldaGO/1.0")
        val code = try { conn.responseCode } catch (e: IOException) { conn.disconnect(); throw e }
        if (code !in 200..299) { conn.disconnect(); throw IOException("HTTP $code en $url") }
        return conn.inputStream.buffered()
    }

    /** Descarga a archivo (vía .part + rename), con reintentos ante errores de red / 429 / 5xx. */
    fun download(url: String, dest: File, attempts: Int = 3) {
        var last: Exception? = null
        repeat(attempts) { i ->
            val part = File(dest.parentFile, dest.name + ".part")
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "EsmeraldaGO/1.0")
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code en $url")
                    val expected = conn.contentLengthLong
                    conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (expected > 0 && part.length() != expected) throw IOException("Descarga incompleta de $url")
                } finally {
                    conn.disconnect()
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw IOException("No se pudo guardar ${dest.name}")
                return
            } catch (e: Exception) {
                last = e
                part.delete()
                if (i < attempts - 1) Thread.sleep(2000L shl i)
            }
        }
        throw last ?: IOException("Falló la descarga de $url")
    }

    /**
     * Descarga todo (en secuencia, a [cacheDir]) y construye [GameData]. Las descargas más nuevas que
     * [maxAgeMs] se reutilizan. Lanza si los datos resultantes no pasan [validate].
     */
    fun downloadAndBuild(cacheDir: File, maxAgeMs: Long = 0, progress: (String) -> Unit = {}): GameData {
        cacheDir.mkdirs()
        fun fetch(url: String, name: String): File {
            val f = File(cacheDir, name)
            val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs
            if (!fresh) {
                progress("Descargando $name…")
                download(url, f)
            }
            return f
        }

        val version = runCatching { open(POKEMINERS_TIMESTAMP).use { it.reader().readText().trim() } }.getOrDefault("")
        val rankingFiles = League.entries.associateWith { fetch(rankings(it), "rankings-${it.cap}.json") }
        val pvpokeFile = fetch(PVPOKE_GAMEMASTER, "pvpoke-gamemaster.json")
        val minersFile = fetch(POKEMINERS_GAMEMASTER, "pokeminers-gamemaster.json")
        // Opcional: sin los textos en español todo funciona igual, salvo leer el juego en español.
        val spanish = runCatching { parseTexts(fetch(SPANISH_TEXTS, "i18n-spanish.json")) }
            .onFailure { progress("No se bajaron los textos en español: ${it.message}") }.getOrDefault(emptyMap())

        progress("Procesando datos…")
        val builder = GameDataBuilder()
        val streams = rankingFiles.mapValues { it.value.inputStream().buffered() }
        val data = try {
            pvpokeFile.inputStream().buffered().use { pv ->
                minersFile.inputStream().buffered().use { mi -> builder.build(pv, mi, streams, version, spanish) }
            }
        } finally {
            streams.values.forEach { runCatching { it.close() } }
        }
        if (builder.missingPve.isNotEmpty()) progress("Sin datos PvE (se aproximan): ${builder.missingPve.joinToString()}")
        validate(data)
        return data
    }

    /**
     * Textos del juego (i18n de PokeMiners: {"data": [clave, texto, clave, texto…]}); se quedan solo los nombres
     * de ataques y especies (son ~70.000 textos en total).
     */
    fun parseTexts(file: File): Map<String, String> {
        val out = HashMap<String, String>()
        com.google.gson.stream.JsonReader(file.reader(Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "data") { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) {
                    val k = r.nextString()
                    if (!r.hasNext()) break
                    val v = r.nextString()
                    if (k.startsWith("move_name_") || k.startsWith("pokemon_name_")) out[k] = v
                }
                r.endArray()
            }
            r.endObject()
        }
        return out
    }

    /** Rechaza datos incompletos para no reemplazar unos buenos por unos rotos. */
    fun validate(data: GameData) {
        val problems = buildList {
            if (data.cpm.size != 101) add("tabla de CPM incompleta (${data.cpm.size})")
            if (data.species.size < 1000) add("pocas especies (${data.species.size})")
            if (data.moves.size < 300) add("pocos movimientos (${data.moves.size})")
            League.entries.forEach { l ->
                val n = data.pvp[l.key]?.size ?: 0
                if (n < 100) add("ranking ${l.label} incompleto ($n)")
            }
            if (data.moves.any { it.durationMs <= 0 }) add("movimientos con duración inválida")
        }
        if (problems.isNotEmpty()) throw IOException("Datos inválidos: " + problems.joinToString("; "))
    }

    fun encode(data: GameData): String = json.encodeToString(GameData.serializer(), data)
    fun decode(text: String): GameData = json.decodeFromString(GameData.serializer(), text)
}
