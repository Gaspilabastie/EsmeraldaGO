package com.esmeraldago.app.scan

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.util.AtomicFile
import com.esmeraldago.app.data.CollectionRepository
import com.esmeraldago.app.data.GameRepository
import com.esmeraldago.app.data.GameState
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.scan.ScanResult
import com.esmeraldago.core.scan.ScreenParser
import com.esmeraldago.core.scan.ScreenReading
import com.esmeraldago.core.scan.ScreenType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** En qué quedó cada Pokémon leído. */
enum class DraftStatus {
    /** Completo, sin guardar (importado de capturas, modo continuo o guardado automático apagado). */
    READY,
    /** Falta un dato para poder guardarlo. */
    INCOMPLETE,
    /** Se parece a uno de la caja: se decide si es el mismo. */
    DUPLICATE,
    SAVED,
    /** Era uno de la caja y se actualizó. */
    UPDATED,
}

/** Un Pokémon detectado. */
data class ScanDraft(
    val key: Int,
    val result: ScanResult,
    val pokemon: OwnedPokemon,
    val include: Boolean,
    /** Capturas combinadas (detalle + valoración = 2). */
    val sources: Int = 1,
    /** Especie elegida a mano (se respeta al recalcular, p. ej. al escribir el PC). */
    val pickedSpecies: Species? = null,
    /** Combinación de IV elegida a mano: alcanza para guardar. */
    val ivPicked: Boolean = false,
    /** Registro de la caja que corresponde a esta lectura (guardado o actualizado). */
    val savedUid: String? = null,
    /** Cómo estaba el registro antes de actualizarlo (para revertir). */
    val before: OwnedPokemon? = null,
    /** Registro de la caja que podría ser el mismo Pokémon. */
    val duplicateOf: String? = null,
    /** Se eligió agregarlo aunque se pareciera a otro de la caja. */
    val forceNew: Boolean = false,
) {
    /** Lo que falta para poder guardarlo, o null. */
    val missing: String? get() = missingOf(result, ivPicked)

    val status: DraftStatus get() = when {
        savedUid != null && before != null -> DraftStatus.UPDATED
        savedUid != null -> DraftStatus.SAVED
        duplicateOf != null -> DraftStatus.DUPLICATE
        missing != null -> DraftStatus.INCOMPLETE
        else -> DraftStatus.READY
    }

    val saved get() = savedUid != null

    companion object {
        fun missingOf(r: ScanResult, ivPicked: Boolean): String? = when {
            r.species == null -> "Falta la especie."
            r.speciesAmbiguous -> "Falta confirmar la especie."
            r.cp == null -> "Falta el PC."
            // Deducido de la valoración y los PS es exacto; reparado o armado con fotogramas, no.
            r.cpInferred && r.reading?.cp != null -> "Falta confirmar el PC."
            ivPicked -> null
            r.appraisalConflict -> "La valoración no coincide con el PC."
            r.candidates.isEmpty() -> "Los IV no cuadran con el PC."
            !r.ivExact -> "Falta la valoración."
            else -> null
        }
    }
}

data class ScanFailure(val index: Int, val reason: String)

/**
 * Sesión de escaneo con alcance de app: los borradores sobreviven a salir de la pantalla y
 * las capturas se procesan de a una en un único worker (sin carreras entre lotes).
 * Recibe capturas de la galería (Uri) y fotogramas de la transmisión en vivo (Bitmap).
 * Lo que queda sin guardar se conserva en disco hasta completarlo o descartarlo.
 */
class ScanSession(
    private val scope: CoroutineScope,
    private val ocr: OcrScanner,
    private val game: GameRepository,
    private val collection: CollectionRepository,
    /** Nivel máximo posible de tus Pokémon (según tu nivel de entrenador). */
    private val levelCap: () -> Double = { 51.0 },
    private val pendingFile: File? = null,
) {
    private sealed interface Source {
        data class FromUri(val uri: Uri) : Source
        class FromBitmap(val bitmap: Bitmap, val origin: String) : Source
    }

    private val _drafts = MutableStateFlow<List<ScanDraft>>(emptyList())
    val drafts: StateFlow<List<ScanDraft>> = _drafts.asStateFlow()
    private val _failures = MutableStateFlow<List<ScanFailure>>(emptyList())
    val failures: StateFlow<List<ScanFailure>> = _failures.asStateFlow()
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()
    private val _processed = MutableStateFlow(0)
    val processed: StateFlow<Int> = _processed.asStateFlow()
    /** Último borrador creado o actualizado (lo muestra el cuadro flotante). */
    private val _latestKey = MutableStateFlow<Int?>(null)
    val latestKey: StateFlow<Int?> = _latestKey.asStateFlow()

    private val queue = Channel<Source>(Channel.UNLIMITED)
    private val mergeLock = Mutex()
    private var nextKey = 0
    private var parser: Pair<GameIndex, ScreenParser>? = null

    init {
        // Worker supervisado: un error en una captura no puede matar la cola ni la app.
        scope.launch {
            for (src in queue) {
                try {
                    process(src)
                } catch (c: CancellationException) {
                    if (!isActive) throw c
                    _failures.update { it + ScanFailure(_processed.value, "Se canceló la lectura de la imagen.") }
                } catch (t: Throwable) {
                    _failures.update { it + ScanFailure(_processed.value, "Error inesperado: ${t.message ?: t.javaClass.simpleName}") }
                }
            }
        }
        // Lo que quedó sin guardar en una sesión anterior vuelve; recién después se empieza a escribir el archivo
        // (si no, la lista vacía del arranque lo pisaría).
        if (pendingFile != null) scope.launch(Dispatchers.IO) {
            runCatching { restore(pendingFile) }
            _drafts.collectLatest { list -> delay(400); runCatching { persist(pendingFile, list) } }
        }
    }

    /** Total de capturas recibidas en la vida del proceso (diagnóstico / tests). */
    private val _enqueuedTotal = MutableStateFlow(0)
    val enqueuedTotal: StateFlow<Int> = _enqueuedTotal.asStateFlow()

    fun enqueue(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _enqueuedTotal.update { it + uris.size }
        _pending.update { it + uris.size }
        uris.forEach { queue.trySend(Source.FromUri(it)) }
    }

    /** Un fotograma de la transmisión en vivo (la sesión lo recicla al terminar). */
    fun enqueue(bitmap: Bitmap, origin: String = "En vivo") {
        _enqueuedTotal.update { it + 1 }
        _pending.update { it + 1 }
        queue.trySend(Source.FromBitmap(bitmap, origin))
    }

    suspend fun idx(): GameIndex = (game.state.first { it is GameState.Ready } as GameState.Ready).idx

    fun parserFor(idx: GameIndex): ScreenParser =
        (parser?.takeIf { it.first === idx }?.second ?: ScreenParser(idx).also { parser = idx to it })
            .also { it.levelCap = levelCap() }

    private suspend fun process(src: Source) {
        val index = _processed.value + 1
        try {
            val idx = idx()
            val p = parserFor(idx)
            val a = try {
                when (src) {
                    is Source.FromUri -> ocr.analyze(src.uri, p)
                    is Source.FromBitmap -> try {
                        ocr.analyze(src.bitmap, p)
                    } finally {
                        src.bitmap.recycle()
                    }
                }
            } catch (c: CancellationException) {
                if (!kotlin.coroutines.coroutineContext.isActive) throw c
                _failures.update { it + ScanFailure(index, "Se canceló la lectura de la imagen.") }
                return
            } catch (t: Throwable) {
                _failures.update { it + ScanFailure(index, "No se pudo leer la imagen (${t.message ?: t.javaClass.simpleName}).") }
                return
            }
            submit(a, idx, p)
        } finally {
            _processed.update { it + 1 }
            _pending.update { (it - 1).coerceAtLeast(0) }
        }
    }

    /** Agrega un análisis ya hecho (lo usa también el modo en vivo). Devuelve el borrador afectado, o null si no se reconoció. */
    suspend fun submit(a: OcrScanner.Analysis, idx: GameIndex, parser: ScreenParser): Int? {
        if (a.result.screen == ScreenType.UNKNOWN) {
            _failures.update { it + ScanFailure(_processed.value + 1, a.result.problem ?: "No se reconoció la pantalla.") }
            return null
        }
        return mergeLock.withLock { merge(a.result, idx, parser) }
    }

    private fun sameMon(a: ScanResult, b: ScanResult) =
        a.hp != null && a.hp == b.hp && (a.cp == b.cp || a.cp == null || b.cp == null) &&
            (a.species == null || b.species == null || a.species?.dex == b.species?.dex)

    /**
     * Ubica la lectura: el mismo Pokémon leído otra vez en esta sesión actualiza su borrador; detalle ↔ valoración
     * del mismo Pokémon (mismos PS y PC, o PC ilegible en una) se juntan en cualquier orden; si no, es uno nuevo.
     */
    private fun merge(r: ScanResult, idx: GameIndex, parser: ScreenParser): Int {
        val list = _drafts.value
        // Releído: misma pantalla y el mismo PC leído en las dos (con el PC tapado no alcanza para saberlo).
        val again = if (r.cp == null) null else list.lastOrNull { it.result.screen == r.screen && it.result.cp == r.cp && sameMon(it.result, r) }
        val partner = again ?: list.lastOrNull { d -> d.sources == 1 && d.result.screen != r.screen && sameMon(d.result, r) }

        if (partner == null) {
            val d = ScanDraft(nextKey++, r, toPokemon(r, idx, null), include = ScanDraft.missingOf(r, false) == null)
            _drafts.update { it + d }
            _latestKey.value = d.key
            return d.key
        }
        // La lectura nueva primero: sus datos ganan.
        val merged = parser.combine(r, partner.result).let { if (it.appraisal != null) it.copy(screen = ScreenType.APPRAISAL) else it }
        val d = partner.copy(
            result = merged, pokemon = toPokemon(merged, idx, partner.pokemon).copy(shiny = partner.pokemon.shiny),
            include = ScanDraft.missingOf(merged, partner.ivPicked) == null,
            sources = if (again != null) partner.sources else 2,
        )
        _drafts.update { l -> l.map { if (it.key == partner.key) d else it } }
        _latestKey.value = d.key
        return d.key
    }

    private fun draft(key: Int) = _drafts.value.firstOrNull { it.key == key }

    /**
     * Guarda el borrador si está completo: si ya tiene registro en la caja lo actualiza; si se parece a uno de la caja
     * (misma familia y mismos IV) lo deja como posible duplicado; si no, lo agrega.
     */
    suspend fun settle(key: Int, idx: GameIndex): ScanDraft? = mergeLock.withLock {
        val d = draft(key) ?: return@withLock null
        when {
            d.savedUid != null -> collection.get(d.savedUid)?.let { cur ->
                val m = mergeInto(cur, d.pokemon)
                if (m != cur) collection.upsert(m)
            }
            d.missing != null || d.duplicateOf != null -> Unit
            else -> {
                val dup = if (d.forceNew) null else findDuplicate(d.pokemon, idx)
                if (dup != null) update(key) { it.copy(duplicateOf = dup.uid, include = false) }
                else {
                    val uid = collection.add(d.pokemon)
                    update(key) { it.copy(savedUid = uid, include = false) }
                }
            }
        }
        draft(key)
    }

    /** Un Pokémon de la caja de la misma familia y con los mismos IV exactos. */
    fun findDuplicate(p: OwnedPokemon, idx: GameIndex): OwnedPokemon? {
        val s = idx[p.speciesId] ?: return null
        val family = (idx.family(s).map { it.id } + s.id).toSet()
        return collection.items.value.firstOrNull { o ->
            o.speciesId in family && o.ivExact && o.ivAtk == p.ivAtk && o.ivDef == p.ivDef && o.ivSta == p.ivSta
        }
    }

    /** DESHACER: un guardado se quita de la caja; una actualización vuelve a como estaba. */
    fun undo(key: Int) {
        val d = draft(key) ?: return
        val uid = d.savedUid ?: return
        if (d.before != null) collection.upsert(d.before) else collection.delete(uid)
        update(key) { it.copy(savedUid = null, before = null, include = false) }
    }

    /** Es el mismo que el de la caja: se actualiza ese registro (también si evolucionó). */
    fun replace(key: Int) {
        val d = draft(key) ?: return
        val old = d.duplicateOf?.let { collection.get(it) } ?: return
        collection.upsert(mergeInto(old, d.pokemon))
        update(key) { it.copy(savedUid = old.uid, before = old, duplicateOf = null, include = false) }
    }

    /** Es otro Pokémon: se agrega aparte. */
    fun addAnyway(key: Int) {
        val d = draft(key) ?: return
        val uid = collection.add(d.pokemon)
        update(key) { it.copy(savedUid = uid, duplicateOf = null, forceNew = true, include = false) }
    }

    /** Guarda todos los completos sin guardar; devuelve cuántos se guardaron (los parecidos a otro quedan para decidir). */
    suspend fun saveReady(): Int {
        val idx = idx()
        var n = 0
        _drafts.value.filter { it.status == DraftStatus.READY }.forEach { d ->
            if (settle(d.key, idx)?.saved == true) n++
        }
        return n
    }

    fun setShiny(key: Int, shiny: Boolean) {
        update(key) { it.copy(pokemon = it.pokemon.copy(shiny = shiny)) }
        draft(key)?.savedUid?.let { uid -> collection.get(uid)?.let { collection.upsert(it.copy(shiny = shiny)) } }
    }

    /** El usuario eligió una de las combinaciones de IV posibles. */
    fun pickIvs(key: Int, c: PokeMath.IvCandidate) = update(key) { cur ->
        cur.copy(include = true, ivPicked = true, pokemon = cur.pokemon.copy(level = c.level, ivAtk = c.ivs.atk,
            ivDef = c.ivs.def, ivSta = c.ivs.sta, ivExact = false))
    }

    /**
     * Al detener el modo en vivo: lo ya guardado sale de la lista (sin emparejamientos con lecturas viejas);
     * lo que falta completar o decidir queda.
     */
    fun endLive() {
        _drafts.update { list -> list.filterNot { it.saved } }
        _failures.value = emptyList()
        _latestKey.value = null
    }

    /** El último escaneo no fue un Pokémon: el cuadro en vivo no debe seguir mostrando el anterior como si fuera este. */
    fun clearLatest() { _latestKey.value = null }

    /** Aplica un cambio sobre la versión ACTUAL del borrador (no pisa una fusión que llegó mientras tanto). */
    fun update(key: Int, transform: (ScanDraft) -> ScanDraft) =
        _drafts.update { list -> list.map { if (it.key == key) transform(it) else it } }

    fun remove(key: Int) {
        _drafts.update { list -> list.filterNot { it.key == key } }
        if (_latestKey.value == key) _latestKey.value = null
    }

    fun removeFailure(f: ScanFailure) = _failures.update { it - f }

    /** El usuario corrigió la especie: recalcular IV con lo leído (fuera del hilo principal). */
    fun changeSpecies(d: ScanDraft, s: Species, idx: GameIndex) = scope.launch(Dispatchers.Default) {
        val r = parserFor(idx).resolveFor(s, d.result)
        update(d.key) { cur ->
            cur.copy(result = r, pokemon = toPokemon(r, idx, cur.pokemon).copy(shiny = cur.pokemon.shiny),
                include = ScanDraft.missingOf(r, false) == null, pickedSpecies = s, ivPicked = false)
        }
    }

    /** El usuario escribió el PC (tapado en la captura): se recalcula todo con las demás pistas leídas. */
    fun setCp(d: ScanDraft, cp: Int, idx: GameIndex) = scope.launch(Dispatchers.Default) {
        val reading = (d.result.reading ?: ScreenReading(hp = d.result.hp, dust = d.result.dust, shadow = d.result.shadow, lucky = d.result.lucky))
            .copy(cp = cp, cpDigits = cp.toString(), cpLabeled = true, cpReconstructed = false)
        val r = parserFor(idx).resolve(reading, d.result.appraisal, forced = d.pickedSpecies)
        update(d.key) { cur ->
            cur.copy(result = r, pokemon = toPokemon(r, idx, cur.pokemon).copy(shiny = cur.pokemon.shiny),
                include = ScanDraft.missingOf(r, false) == null, ivPicked = false)
        }
    }

    fun clear() {
        _drafts.value = emptyList()
        _failures.value = emptyList()
        _processed.value = 0
        _latestKey.value = null
    }

    val hasUnsaved get() = _drafts.value.any { !it.saved }

    // ------------------------------------------------------------------ lo que queda sin guardar, en disco

    @Serializable
    private data class PendingDraft(
        val pokemon: OwnedPokemon,
        val reading: ScreenReading? = null,
        val appraisal: List<Int>? = null,
        val pickedSpecies: String? = null,
        val ivPicked: Boolean = false,
        val duplicateOf: String? = null,
        val forceNew: Boolean = false,
        val sources: Int = 1,
    )

    private val pendingSerializer = ListSerializer(PendingDraft.serializer())

    private fun persist(file: File, list: List<ScanDraft>) {
        val out = list.filter { !it.saved }.map { d ->
            PendingDraft(d.pokemon, d.result.reading, d.result.appraisal?.let { listOf(it.atk, it.def, it.sta) },
                d.pickedSpecies?.id, d.ivPicked, d.duplicateOf, d.forceNew, d.sources)
        }
        val af = AtomicFile(file)
        if (out.isEmpty()) { af.delete(); return }
        val s = af.startWrite()
        try {
            s.write(GameDataSources.json.encodeToString(pendingSerializer, out).toByteArray(Charsets.UTF_8))
            af.finishWrite(s)
        } catch (t: Throwable) {
            af.failWrite(s)
        }
    }

    private suspend fun restore(file: File) {
        val af = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return
        val saved = GameDataSources.json.decodeFromString(pendingSerializer, af.readFully().toString(Charsets.UTF_8))
        if (saved.isEmpty()) return
        val idx = idx()
        val parser = parserFor(idx)
        mergeLock.withLock {
            val restored = saved.mapNotNull { p ->
                val picked = p.pickedSpecies?.let { idx[it] }
                val appraisal = p.appraisal?.takeIf { it.size == 3 }?.let { Ivs(it[0], it[1], it[2]) }
                val r = p.reading?.let { parser.resolve(it, appraisal, picked) }
                    ?: idx[p.pokemon.speciesId]?.let { s -> ScanResult(ScreenType.DETAIL, species = s, cp = p.pokemon.cp, hp = p.pokemon.hp) }
                    ?: return@mapNotNull null
                // Si el registro parecido ya no está en la caja, deja de ser un posible duplicado.
                val dup = p.duplicateOf?.takeIf { collection.get(it) != null }
                ScanDraft(nextKey++, r, p.pokemon, include = false, sources = p.sources, pickedSpecies = picked,
                    ivPicked = p.ivPicked, duplicateOf = dup, forceNew = p.forceNew)
            }
            _drafts.update { restored + it }
        }
    }

    companion object {
        /**
         * Junta lo leído con lo que había en la caja: nunca se pisa un dato bueno con uno peor. PC, PS y nivel van
         * juntos y solo si no bajan (una captura vieja no baja el nivel); los IV, si los nuevos son al menos tan
         * exactos; el apodo de la caja se conserva; purificar apaga "oscuro".
         */
        fun mergeInto(old: OwnedPokemon, new: OwnedPokemon): OwnedPokemon {
            val ivs = new.ivExact || !old.ivExact
            val newer = ivs && new.level >= old.level
            return old.copy(
                speciesId = new.speciesId.ifBlank { old.speciesId },
                cp = if (newer) new.cp else old.cp,
                hp = if (newer) new.hp ?: old.hp else old.hp,
                level = if (newer) new.level else old.level,
                ivAtk = if (ivs) new.ivAtk else old.ivAtk,
                ivDef = if (ivs) new.ivDef else old.ivDef,
                ivSta = if (ivs) new.ivSta else old.ivSta,
                ivExact = if (ivs) new.ivExact else old.ivExact,
                fast = new.fast ?: old.fast,
                charged1 = if (new.charged1 != null) new.charged1 else old.charged1,
                charged2 = if (new.charged1 != null) new.charged2 else old.charged2,
                shadow = !new.purified && (old.shadow || new.shadow),
                purified = new.purified || (old.purified && !new.shadow),
                lucky = old.lucky || new.lucky,
                shiny = old.shiny || new.shiny,
                dynamax = (old.dynamax || new.dynamax) && !(old.gigantamax || new.gigantamax),
                gigantamax = old.gigantamax || new.gigantamax,
                nickname = old.nickname ?: new.nickname,
            )
        }

        fun toPokemon(r: ScanResult, idx: GameIndex, previous: OwnedPokemon?): OwnedPokemon {
            // Con varias combinaciones posibles se toma la más probable "media", no la más optimista.
            val sorted = r.candidates.sortedBy { it.ivs.total }
            val best = sorted.getOrNull(sorted.size / 2)
            val s = r.species
            return OwnedPokemon(
                uid = "",
                speciesId = s?.baseId?.takeIf { idx[it] != null } ?: s?.id ?: previous?.speciesId ?: "",
                cp = r.cp ?: previous?.cp ?: 10,
                hp = r.hp ?: previous?.hp,
                level = best?.level ?: previous?.level ?: 20.0,
                ivAtk = best?.ivs?.atk ?: previous?.ivAtk ?: 0,
                ivDef = best?.ivs?.def ?: previous?.ivDef ?: 0,
                ivSta = best?.ivs?.sta ?: previous?.ivSta ?: 0,
                ivExact = r.ivExact,
                fast = r.fast ?: previous?.fast,
                charged1 = r.charged.getOrNull(0) ?: previous?.charged1,
                charged2 = r.charged.getOrNull(1) ?: previous?.charged2,
                shadow = r.shadow || previous?.shadow == true,
                purified = !r.shadow && (r.purified || previous?.purified == true),
                lucky = r.lucky || previous?.lucky == true,
                shiny = previous?.shiny == true,
                dynamax = (r.dynamax && !r.gigantamax) || previous?.dynamax == true,
                gigantamax = r.gigantamax || previous?.gigantamax == true,
                nickname = r.nickname ?: previous?.nickname,
            )
        }
    }
}
