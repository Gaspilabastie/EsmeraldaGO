package com.esmeraldago.app.critic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.esmeraldago.app.EsmeraldaApp
import com.esmeraldago.app.MainActivity
import com.esmeraldago.app.data.CollectionRepository
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.reco.Recommender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobustnessCriticAppTest {
    private val app get() = ApplicationProvider.getApplicationContext<EsmeraldaApp>()
    private val ser = ListSerializer(OwnedPokemon.serializer())

    private fun mon(i: Int) = OwnedPokemon("u$i", "machamp", 1000 + i, null, 20.0, 1, 2, 3, nickname = "n".repeat(40) + i)

    /** Un cambio de configuración (modo oscuro, idioma, tamaño de fuente…) recrea la Activity con el mismo intent de compartir. */
    @Test
    fun shareIntentIsReprocessedOnRecreate() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
        val before = app.container.scan.enqueuedTotal.value
        Robolectric.buildActivity(MainActivity::class.java, intent).create()
        val afterFirst = app.container.scan.enqueuedTotal.value - before
        // Recreación: savedInstanceState != null, mismo intent.
        Robolectric.buildActivity(MainActivity::class.java, intent).create(Bundle())
        val afterRecreate = app.container.scan.enqueuedTotal.value - before
        println("ROB-APP share (ronda 2): tras crear=$afterFirst URIs, tras recrear=$afterRecreate URIs")
        assertEquals("la recreación no debe re-encolar la misma captura", 1, afterRecreate)
    }

    /**
     * Ronda 2: el test anterior pasa también por la "consumición" del Intent (se muta el MISMO objeto).
     * Tras muerte de proceso el sistema re-entrega el intent ORIGINAL (sin mutar): aislar el guard de savedInstanceState.
     */
    @Test
    fun shareGuardWithPristineIntentOnRestore() {
        fun share() = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/media/77"))
        val before = app.container.scan.enqueuedTotal.value
        Robolectric.buildActivity(MainActivity::class.java, share()).create()
        Robolectric.buildActivity(MainActivity::class.java, share()).create(Bundle()) // intent intacto + estado guardado
        val n = app.container.scan.enqueuedTotal.value - before
        println("ROB-APP (ronda 2) restore con intent original: encoladas=$n")
        assertEquals(1, n)
    }

    /** Ronda 2: la carga es asíncrona y la UI no espera `loaded`. ¿Qué pasa si se guarda antes de que termine? */
    @Test
    fun upsertBeforeLoadCompletes() {
        val f = File(app.filesDir, "collection.json")
        var lost = 0; var trials = 0
        repeat(15) {
            listOf(f, File(f.path + ".bak"), File(f.path + ".new")).forEach { it.delete() }
            f.writeText(GameDataSources.json.encodeToString(ser, List(300) { mon(it) }))
            val repo = CollectionRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO))
            val raced = !repo.loaded.value
            repo.upsert(mon(5000)) // p.ej. GUARDAR del escaneo en un arranque en frío
            val deadline = System.currentTimeMillis() + 3000
            while (!repo.loaded.value && System.currentTimeMillis() < deadline) Thread.sleep(5)
            Thread.sleep(300)
            val mem = repo.items.value
            val newer = File(f.path + ".new") // host Windows: el rename .new->base falla
            val disk = runCatching { GameDataSources.json.decodeFromString(ser, (if (newer.exists()) newer else f).readText()) }.getOrNull()
            val ok = mem.size == 301 && disk?.size == 301
            if (raced) trials++
            if (raced && !ok) { lost++; if (lost == 1) println("ROB-APP carrera load/upsert: memoria=${mem.size}, disco=${disk?.size} (esperado 301)") }
        }
        println("ROB-APP (ronda 2) upsert antes de terminar load: $trials carreras, $lost con pérdida de datos")
    }

    /** Ronda 2: gamedata.json descargado dañado -> se aparta como .bad y se usa el asset. */
    @Test
    fun corruptDownloadedDataFallsBackToAsset() {
        val local = File(app.filesDir, "gamedata.json")
        local.writeText("{\"generatedAt\":1,") // truncado
        val ver = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
        app.getSharedPreferences("gamedata", android.content.Context.MODE_PRIVATE).edit().putLong("forAppVersion", ver).commit()
        val repo = com.esmeraldago.app.data.GameRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO), app.container.settings)
        repo.load()
        val deadline = System.currentTimeMillis() + 30_000
        while (repo.state.value is com.esmeraldago.app.data.GameState.Loading && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val bad = File(local.path + ".bad")
        println("ROB-APP (ronda 2) gamedata dañado -> estado=${repo.state.value::class.simpleName}, .bad=${bad.exists()}, status='${repo.updateStatus.value}'")
        assertTrue(repo.state.value is com.esmeraldago.app.data.GameState.Ready)
        assertTrue(bad.exists() && !local.exists())
        bad.delete()
    }

    @Test
    fun truncatedCollectionIsSilentlyDropped() {
        val f = File(app.filesDir, "collection.json")
        val good = GameDataSources.json.encodeToString(ser, List(30) { mon(it) })
        f.writeText(good.substring(0, good.length - 20)) // escritura cortada (proceso muerto / disco lleno)
        app.filesDir.listFiles { x -> x.name.startsWith("collection.json.corrupt") }?.forEach { it.delete() }
        val repo = CollectionRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val deadline = System.currentTimeMillis() + 5000
        while (!repo.loaded.value && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val aside = app.filesDir.listFiles { x -> x.name.startsWith("collection.json.corrupt") }.orEmpty()
        println("ROB-APP (ronda 2) collection truncada -> items=${repo.items.value.size}, error='${repo.error.value}', apartado=${aside.map { it.name }}")
        assertTrue("se debe avisar al usuario", repo.error.value != null)
        assertEquals("el archivo dañado se aparta (no se pisa)", 1, aside.size)
        assertEquals("la copia apartada conserva los datos originales", good.length - 20, aside.first().readText().length)
        repo.upsert(mon(999))
        Thread.sleep(500)
        assertEquals(1, GameDataSources.json.decodeFromString(ser, f.readText()).size)
    }

    /** (Ronda 2) ráfagas de cambios con el CollectionRepository real: el archivo final debe ser la última versión. */
    @Test
    fun concurrentPersistSimulation() = runBlocking {
        val f = File(app.filesDir, "collection.json")
        var ok = 0; var bad = 0
        val trials = 20
        repeat(trials) {
            f.delete(); File(f.path + ".bak").delete(); File(f.path + ".new").delete()
            val repo = CollectionRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO))
            while (!repo.loaded.value) delay(5)
            repo.addAll(List(400) { mon(it) })
            repeat(6) { repo.delete(repo.items.value.first().uid) } // borrar varios seguidos
            val expected = repo.items.value.size
            delay(400)
            // En Android rename(2) reemplaza el destino; en un host Windows falla y la última versión queda en ".new".
            // Lo que se verifica es el ORDEN: la última escritura completa es la versión final.
            val windowsHost = System.getProperty("os.name").startsWith("Windows")
            val newer = File(f.path + ".new")
            val target = if (windowsHost && newer.exists()) newer else f
            val decoded = runCatching { GameDataSources.json.decodeFromString(ser, target.readText()) }
            if (decoded.getOrNull()?.size == expected) ok++ else {
                bad++
                if (bad == 1) println("ROB-APP detalle: esperado=$expected, leído=${decoded.getOrNull()?.size}, " +
                    "error=${decoded.exceptionOrNull()?.javaClass?.simpleName}, archivos=${app.filesDir.list()?.filter { it.startsWith("collection") }}")
            }
        }
        println("ROB-APP (ronda 2) persist en ráfaga ($trials pruebas): ok=$ok, versión vieja/corrupta=$bad")
        assertEquals(trials, ok)
    }

    /** produceState cancela la corrutina, pero el cálculo en Dispatchers.Default no es cooperativo. */
    @Test
    fun heavyComputationIgnoresCancellation() = runBlocking {
        val text = listOf("src/main/assets/gamedata.json", "app/src/main/assets/gamedata.json").map(::File).first { it.exists() }.readText()
        val idx = GameIndex(GameDataSources.decode(text))
        val rnd = Random(3)
        val pool = idx.released.filter { !it.shadow && !it.mega }
        val col = List(400) { OwnedPokemon("c$it", pool[rnd.nextInt(pool.size)].id, 1000, null, 20.0, rnd.nextInt(16), rnd.nextInt(16), rnd.nextInt(16)) }
        val scope = CoroutineScope(SupervisorJob())
        val reco = Recommender(idx, 51.0) // clave de caché distinta -> trabajo en frío
        // (Ronda 2) mismo mecanismo que la app: compute() = withContext + runInterruptible, y el core chequea Interrupt.
        val job = scope.launch { com.esmeraldago.app.ui.common.compute { reco.powerUpAdvice(col) } }
        delay(30)
        val t = System.nanoTime()
        job.cancelAndJoin()
        val waited = (System.nanoTime() - t) / 1_000_000
        println("ROB-APP (ronda 2) cancel() de powerUpAdvice: siguió %d ms después de cancelar".format(waited))
        assertTrue("debe cortar rápido al cancelar", waited < 300)
    }
}
