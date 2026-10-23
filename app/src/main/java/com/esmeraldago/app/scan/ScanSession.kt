package com.esmeraldago.app.scan

import android.graphics.Bitmap
import android.net.Uri
import com.esmeraldago.app.data.CollectionRepository
import com.esmeraldago.app.data.GameRepository
import com.esmeraldago.app.data.GameState
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.scan.OcrLine
import com.esmeraldago.core.scan.ScanResult
import com.esmeraldago.core.scan.ScreenParser
import com.esmeraldago.core.scan.ScreenReading
import com.esmeraldago.core.scan.ScreenType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Un Pokémon detectado, pendiente de confirmar. */
data class ScanDraft(
    val key: Int,
    val result: ScanResult,
    val pokemon: OwnedPokemon,
    val include: Boolean,
    /** Capturas combinadas (detalle + valoración = 2). */
    val sources: Int = 1,
    /** Especie elegida a mano (se respeta al recalcular, p. ej. al escribir el PC). */
    val pickedSpecies: Species? = null,
)

data class ScanFailure(val index: Int, val reason: String)

/**
 * Sesión de escaneo con alcance de app: los borradores sobreviven a salir de la pantalla y
 * las capturas se procesan de a una en un único worker (sin carreras entre lotes).
 * Recibe capturas de la galería (Uri) y fotogramas de la transmisión en vivo (Bitmap).
 */
class ScanSession(
    private val scope: CoroutineScope,
    private val ocr: OcrScanner,
    private val game: GameRepository,
    /** Nivel máximo posible de tus Pokémon (según tu nivel de entrenador). */
    private val levelCap: () -> Double = { 51.0 },
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
    /** Último borrador creado o actualizado (lo muestra el panel flotante). */
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

    /** Agrega un análisis ya hecho (lo usa también el modo continuo). Devuelve false si no se reconoció. */
    suspend fun submit(a: OcrScanner.Analysis, idx: GameIndex, parser: ScreenParser): Boolean {
        if (a.result.screen == ScreenType.UNKNOWN) {
            _failures.update { it + ScanFailure(_processed.value + 1, a.result.problem ?: "No se reconoció la pantalla.") }
            return false
        }
        mergeLock.withLock { merge(a.result, idx, parser) }
        return true
    }

    /**
     * Empareja detalle ↔ valoración del MISMO Pokémon, en cualquier orden: mismos PS y mismo PC
     * (o PC ilegible en una de las dos, p. ej. tapado por el modelo) y misma especie si ambas la saben.
     */
    private fun merge(r: ScanResult, idx: GameIndex, parser: ScreenParser) {
        val partner = if (r.hp != null) _drafts.value.lastOrNull { d ->
            d.sources == 1 && d.result.screen != r.screen && d.result.hp == r.hp &&
                (d.result.cp == r.cp || d.result.cp == null || r.cp == null) &&
                (r.species == null || d.result.species == null || d.result.species?.dex == r.species?.dex)
        } else null

        if (partner == null) {
            val d = ScanDraft(nextKey++, r, toPokemon(r, idx, null), include = canSave(r) && r.ivExact)
            _drafts.update { it + d }
            _latestKey.value = d.key
            return
        }
        val merged = parser.combine(partner.result, r).let { if (it.appraisal != null) it.copy(screen = ScreenType.APPRAISAL) else it }
        val d = partner.copy(
            result = merged, pokemon = toPokemon(merged, idx, partner.pokemon),
            include = canSave(merged) && merged.ivExact, sources = 2,
        )
        _drafts.update { list -> list.map { if (it.key == partner.key) d else it } }
        _latestKey.value = d.key
    }

    fun update(d: ScanDraft) = _drafts.update { list -> list.map { if (it.key == d.key) d else it } }

    /** El último escaneo no fue un Pokémon: el panel en vivo no debe seguir mostrando el anterior como si fuera este. */
    fun clearLatest() { _latestKey.value = null }

    /** Aplica un cambio sobre la versión ACTUAL del borrador (no pisa una fusión que llegó mientras tanto). */
    fun update(key: Int, transform: (ScanDraft) -> ScanDraft) =
        _drafts.update { list -> list.map { if (it.key == key) transform(it) else it } }

    fun remove(key: Int) {
        _drafts.update { list -> list.filterNot { it.key == key } }
        if (_latestKey.value == key) _latestKey.value = null
    }

    /** El usuario corrigió la especie: recalcular IV con lo leído (fuera del hilo principal). */
    fun changeSpecies(d: ScanDraft, s: Species, idx: GameIndex) = scope.launch(Dispatchers.Default) {
        val r = parserFor(idx).resolveFor(s, d.result)
        update(d.key) { cur -> cur.copy(result = r, pokemon = toPokemon(r, idx, cur.pokemon), include = canSave(r) && r.ivExact, pickedSpecies = s) }
    }

    /** El usuario escribió el PC (tapado en la captura): se recalcula todo con las demás pistas leídas. */
    fun setCp(d: ScanDraft, cp: Int, idx: GameIndex) = scope.launch(Dispatchers.Default) {
        val reading = (d.result.reading ?: ScreenReading(hp = d.result.hp, dust = d.result.dust, shadow = d.result.shadow, lucky = d.result.lucky))
            .copy(cp = cp, cpDigits = cp.toString(), cpLabeled = true)
        val r = parserFor(idx).resolve(reading, d.result.appraisal, forced = d.pickedSpecies)
        update(d.key) { cur -> cur.copy(result = r, pokemon = toPokemon(r, idx, cur.pokemon), include = canSave(r) && r.ivExact) }
    }

    /** Guarda los marcados y limpia la sesión; devuelve cuántos se guardaron. */
    fun save(collection: CollectionRepository): Int {
        val chosen = _drafts.value.filter { it.include && it.pokemon.speciesId.isNotBlank() && canSave(it.result) }.map { it.pokemon }
        collection.addAll(chosen)
        clear()
        return chosen.size
    }

    /** Guarda un solo borrador (desde el panel flotante). */
    fun saveOne(key: Int, collection: CollectionRepository): OwnedPokemon? {
        val d = _drafts.value.firstOrNull { it.key == key } ?: return null
        if (d.pokemon.speciesId.isBlank() || !canSave(d.result)) return null
        collection.addAll(listOf(d.pokemon))
        remove(key)
        return d.pokemon
    }

    fun clear() {
        _drafts.value = emptyList()
        _failures.value = emptyList()
        _processed.value = 0
        _latestKey.value = null
    }

    val hasUnsaved get() = _drafts.value.isNotEmpty()

    companion object {
        /** Sin especie o sin PC (ni leído ni deducido) se guardaría un Pokémon inventado. */
        fun canSave(r: ScanResult) = r.species != null && r.cp != null

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
