package com.esmeraldago.app.data

import android.content.Context
import androidx.core.util.AtomicFile
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.OwnedPokemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * Tu colección, guardada como JSON en el almacenamiento privado de la app.
 * - Escritura atómica (AtomicFile mantiene un backup hasta que la nueva versión está completa).
 * - Un único escritor: cada guardado escribe SIEMPRE el último estado, en orden.
 * - Si el archivo está corrupto se aparta (no se pisa) y se avisa.
 */
class CollectionRepository(context: Context, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "collection.json"))
    private val serializer = ListSerializer(OwnedPokemon.serializer())
    private val writeLock = Mutex()

    private val _items = MutableStateFlow<List<OwnedPokemon>>(emptyList())
    val items: StateFlow<List<OwnedPokemon>> = _items.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    private fun load() {
        val base = file.baseFile
        if (!base.exists() && !File(base.path + ".bak").exists()) { _loaded.value = true; return }
        try {
            val text = file.readFully().toString(Charsets.UTF_8)
            val decoded = GameDataSources.json.decodeFromString(serializer, text)
            // Fusionar: si el usuario agregó algo antes de que terminara la carga, no se pierde.
            _items.update { added -> decoded + added.filter { n -> decoded.none { it.uid == n.uid } } }
        } catch (t: Throwable) {
            // Nunca descartar en silencio: se aparta el archivo y se avisa.
            val aside = File(base.parentFile, "collection.json.corrupt-${System.currentTimeMillis()}")
            base.renameTo(aside)
            _error.value = "Tu colección no se pudo leer (copia guardada en ${aside.name}). Empezás con la caja vacía."
        } finally {
            _loaded.value = true
        }
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                // Nunca escribir antes de cargar: se guardaría una lista parcial encima de la colección.
                _loaded.first { it }
                val latest = _items.value
                val out = file.startWrite()
                try {
                    out.write(GameDataSources.json.encodeToString(serializer, latest).toByteArray(Charsets.UTF_8))
                    file.finishWrite(out)
                } catch (t: Throwable) {
                    file.failWrite(out)
                    _error.value = "No se pudo guardar la colección: ${t.message}"
                }
            }
        }
    }

    fun get(uid: String) = _items.value.firstOrNull { it.uid == uid }

    fun upsert(p: OwnedPokemon) {
        val withId = if (p.uid.isBlank()) p.copy(uid = UUID.randomUUID().toString(), addedAt = System.currentTimeMillis()) else p
        _items.update { list ->
            val i = list.indexOfFirst { it.uid == withId.uid }
            if (i >= 0) list.toMutableList().also { it[i] = withId } else list + withId
        }
        persist()
    }

    fun addAll(ps: List<OwnedPokemon>) {
        val now = System.currentTimeMillis()
        _items.update { it + ps.map { p -> p.copy(uid = UUID.randomUUID().toString(), addedAt = now) } }
        persist()
    }

    /** Borra y devuelve el Pokémon (para poder deshacer). */
    fun delete(uid: String): OwnedPokemon? {
        val removed = get(uid)
        _items.update { list -> list.filterNot { it.uid == uid } }
        persist()
        return removed
    }

    fun dismissError() { _error.value = null }

    /** La caja como JSON, para guardar una copia fuera de la app (Opciones → Copia de seguridad). */
    fun exportJson(): String = GameDataSources.json.encodeToString(serializer, _items.value)

    /**
     * Agrega los Pokémon de una copia: los que ya están (mismo uid) se dejan como están. Devuelve cuántos sumó.
     * Lanza si el archivo no es una copia de EsmeraldaGO.
     */
    fun importJson(text: String): Int {
        val incoming = GameDataSources.json.decodeFromString(serializer, text)
        var added = 0
        _items.update { list ->
            val known = list.map { it.uid }.toSet()
            val fresh = incoming.filter { it.uid.isNotBlank() && it.uid !in known }
            added = fresh.size
            list + fresh
        }
        persist()
        return added
    }
}
