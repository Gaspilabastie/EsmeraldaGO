package com.esmeraldago.core.critic

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.data.GameDataBuilder
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.model.Types
import com.esmeraldago.core.reco.Recommender
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Crítico de robustez / rendimiento. Solo mide e imprime; las aserciones documentan
 * comportamientos (incluidos bugs) observados.
 */
class RobustnessCriticTest {
    companion object {
        private fun file(rel: String) = listOf("../$rel", rel).map(::File).first { it.exists() }
        val gameText: String by lazy { file("app/src/main/assets/gamedata.json").readText() }
        val idx: GameIndex by lazy { GameIndex(GameDataSources.decode(gameText)) }
        private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000.0
        private fun usedMb(): Double {
            repeat(3) { System.gc(); Thread.sleep(50) }
            val r = Runtime.getRuntime()
            return (r.totalMemory() - r.freeMemory()) / 1048576.0
        }

        /** (Ronda 2) la caché ahora vive en GameIndex, acotada (LRU 400 tablas compactas). */
        object PvpCacheView {
            val size get() = idx.statProductCacheSize()
            fun clear() = idx.clearStatProductCache()
        }
        fun pvpCache() = PvpCacheView

        fun collection(n: Int, seed: Int = 7): List<OwnedPokemon> {
            val rnd = Random(seed)
            val pool = idx.released.filter { !it.shadow && !it.mega }
            return List(n) { i ->
                val s = pool[rnd.nextInt(pool.size)]
                val fast = PveCalc.usableFast(s, idx).randomOrNull(rnd)
                val ch = PveCalc.usableCharged(s, idx).randomOrNull(rnd)
                OwnedPokemon(
                    uid = "u$i", speciesId = s.id, cp = 1000, level = (2 + rnd.nextInt(80)) / 2.0,
                    ivAtk = rnd.nextInt(16), ivDef = rnd.nextInt(16), ivSta = rnd.nextInt(16),
                    fast = fast?.id, charged1 = ch?.id, shadow = s.shadowEligible && rnd.nextInt(5) == 0,
                    dynamax = s.dynamax && rnd.nextInt(3) == 0, addedAt = i.toLong(),
                )
            }
        }
    }

    // ------------------------------------------------------------------ arranque

    @Test
    fun startupDecodeAndIndex() {
        for (round in 1..3) {
            var t = System.nanoTime()
            val data = GameDataSources.decode(gameText)
            val tDecode = ms(t)
            t = System.nanoTime()
            val i = GameIndex(data)
            val tIndex = ms(t)
            t = System.nanoTime()
            i.findByName("charizard")
            val tName = ms(t)
            println("ROB startup round $round: decode=%.0f ms, GameIndex=%.0f ms, nameIndex(lazy)=%.0f ms, species=%d"
                .format(tDecode, tIndex, tName, data.species.size))
        }
        val before = usedMb()
        val keep = GameIndex(GameDataSources.decode(gameText))
        val after = usedMb()
        println("ROB retained heap GameIndex+GameData ≈ %.1f MB (text %d chars)".format(after - before, gameText.length))
        assertTrue(keep.species.isNotEmpty())
    }

    @Test
    fun corruptedOrTruncatedDataThrows() {
        // GameRepository.load no tiene fallback al asset: un gamedata.json descargado truncado deja la app en Error.
        assertFails { GameDataSources.decode(gameText.substring(0, gameText.length / 2)) }
        assertFails { GameDataSources.decode("") }
        // collection.json truncado -> CollectionRepository.load() lo transforma en lista vacía en silencio.
        val col = GameDataSources.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(OwnedPokemon.serializer()), collection(50))
        assertFails {
            GameDataSources.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(OwnedPokemon.serializer()), col.substring(0, col.length - 10))
        }
        println("ROB collection.json de 50 Pokémon = ${col.length} bytes; truncado -> excepción -> load() devuelve emptyList()")
    }

    @Test
    fun idsSafeForNavigationAndLazyKeys() {
        val bad = idx.data.species.map { it.id }.filter { !it.matches(Regex("[a-z0-9_]+")) }
        println("ROB ids de especie con caracteres no seguros para rutas: $bad")
        val dupSpecies = idx.data.species.groupBy { it.id }.filter { it.value.size > 1 }.keys
        println("ROB ids de especie duplicados: $dupSpecies")
        for ((k, list) in idx.data.pvp) {
            val dups = list.groupBy { it.id }.filter { it.value.size > 1 }.keys
            println("ROB ranking $k: ${list.size} entradas, ids duplicados (key de LazyColumn en PvpTab) = $dups")
        }
        val zeroDur = idx.data.moves.filter { it.durationMs <= 0 }
        println("ROB movimientos con durationMs<=0 (div/0 -> NaN en HpBar): ${zeroDur.map { it.id }}")
    }

    // ------------------------------------------------------------------ PvP tables

    @Test
    fun pvpTableCostAndMemory() {
        pvpCache().clear()
        val sp = idx.released.filter { !it.mega }.take(60)
        // calentar JIT
        PokeMath.pvpRankOf(sp[0], Ivs(1, 2, 3), 1500, 50.0, idx); pvpCache().clear()
        val times = mutableListOf<Double>()
        val before = usedMb()
        for (s in sp) for (l in League.entries) {
            val t = System.nanoTime()
            PokeMath.pvpRankOf(s, Ivs(1, 2, 3), l.cap, 50.0, idx)
            times += ms(t)
        }
        val after = usedMb()
        val n = pvpCache().size.coerceAtLeast(1)
        println("ROB pvpRankOf: %d tablas, media %.2f ms, máx %.2f ms (JVM desktop con JIT)".format(n, times.average(), times.max()))
        println("ROB caché PvP memoria retenida ≈ %.1f MB para %d tablas -> %.0f KB/tabla".format(after - before, n, (after - before) * 1024 / n))
        assertTrue((after - before) * 1024 / n < 64, "cada tabla en caché debería ocupar < 64 KB")
        // SpeciesDetailScreen calcula 3 de estas tablas en composición (hilo principal)
        val s = idx["registeel"]!!
        pvpCache().clear()
        val t = System.nanoTime()
        League.entries.forEach { PokeMath.pvpTable(s, it.cap, 50.0, idx) }
        println("ROB SpeciesDetail PvP (3 tablas, en el main thread) = %.1f ms en desktop".format(ms(t)))
    }

    /** Réplica del bloque PvP de OwnedDetailScreen, que corre dentro de la composición (main thread). */
    @Test
    fun ownedDetailPvpOnMainThread() {
        PokeMath.pvpTable(idx["machamp"]!!, 1500, 50.0, idx) // warm-up JIT
        for (id in listOf("eevee", "machop", "ralts")) {
            pvpCache().clear()
            val s = idx[id]!!
            val t = System.nanoTime()
            var n = 0
            League.entries.forEach { league ->
                (listOf(s) + idx.descendants(s)).distinctBy { it.id }.forEach { form ->
                    PokeMath.pvpRankOf(form, Ivs(1, 2, 3), league.cap, 50.0, idx); n++
                }
            }
            println("ROB OwnedDetail PvP $id: $n tablas en composición = %.0f ms (desktop JIT)".format(ms(t)))
        }
    }

    @Test
    fun pvpCacheIgnoresGameIndex_staleAfterUpdate() {
        val s = idx["azumarill"]!!
        val r1 = PokeMath.pvpRankOf(s, Ivs(0, 15, 15), 1500, 50.0, idx)!!
        // Simula una actualización de datos que cambia stats (rebalanceo) -> nuevo GameIndex.
        val newData = idx.data.copy(species = idx.data.species.map { if (it.id == "azumarill") it.copy(atk = it.atk + 40) else it })
        val idx2 = GameIndex(newData)
        val r2 = PokeMath.pvpRankOf(idx2["azumarill"]!!, Ivs(0, 15, 15), 1500, 50.0, idx2)!!
        val expected = PokeMath.bestLevelUnderCap(idx2["azumarill"]!!, Ivs(0, 15, 15), 1500, 50.0, idx2)
        println("ROB caché tras 'actualizar datos' (atk+40): viejo Nv${r1.level} rank#${r1.rank}; nuevo Nv${r2.level} rank#${r2.rank} (esperado Nv$expected)")
        assertEquals(expected, r2.level, "con el GameIndex nuevo no debe reutilizarse la caché vieja")
    }

    // ------------------------------------------------------------------ PvE / recomendador

    @Test
    fun topAttackersCost() {
        PveCalc.topAttackers("fighting", idx) // warm-up
        val t = System.nanoTime()
        for (type in Types.ALL) PveCalc.topAttackers(type, idx, 40.0, limit = 60)
        val all = ms(t)
        val t2 = System.nanoTime()
        for (type in Types.ALL) MaxCalc.topAttackers(type, idx, limit = 30)
        val mx = ms(t2)
        println("ROB PveCalc.topAttackers 18 tipos = %.0f ms (%.1f ms/tipo); MaxCalc 18 tipos = %.0f ms; released=%d"
            .format(all, all / 18, mx, idx.released.size))
        val t3 = System.nanoTime()
        PveCalc.counters(idx["kyogre"]!!, com.esmeraldago.core.calc.RaidTier.T5, idx)
        println("ROB PveCalc.counters(kyogre) = %.0f ms".format(ms(t3)))
    }

    @Test
    fun recommenderWithLargeCollection() {
        for (n in listOf(100, 500, 1500)) {
            pvpCache().clear()
            val col = collection(n)
            val r = Recommender(idx)
            val before = usedMb()
            var t = System.nanoTime()
            val pu = r.powerUpAdvice(col)
            val tPu = ms(t)
            t = System.nanoTime()
            val pu2 = r.powerUpAdvice(col)
            val tPu2 = ms(t)
            t = System.nanoTime()
            val ca = r.catchAdvice(col)
            val tCa = ms(t)
            t = System.nanoTime()
            r.pvpTeam(League.GREAT, col)
            val tTeam = ms(t)
            t = System.nanoTime()
            r.raidTeam(idx["kyogre"]!!, com.esmeraldago.core.calc.RaidTier.T5, col, size = 12)
            val tRaid = ms(t)
            val after = usedMb()
            println(("ROB colección n=%d: powerUpAdvice frío=%.0f ms / caliente=%.0f ms (%d consejos), catchAdvice=%.0f ms, " +
                "pvpTeam=%.0f ms, raidTeam=%.0f ms; pvpTables=%d entradas, heap retenido ≈ %.1f MB")
                .format(n, tPu, tPu2, pu.size + pu2.size * 0, tCa, tTeam, tRaid, pvpCache().size, after - before))
            assertTrue(ca.isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ actualización (GameDataBuilder)

    private fun cacheDir(): File? = listOf("../data-cache", "data-cache").map(::File).firstOrNull { File(it, "pokeminers-gamemaster.json").exists() }

    @Test
    fun builderTimeAndPeakHeap() {
        val dir = cacheDir() ?: return println("ROB sin data-cache, se omite")
        fun run(): Pair<Double, Double> {
            System.gc()
            val peakA = java.util.concurrent.atomic.AtomicLong(0)
            val stopA = java.util.concurrent.atomic.AtomicBoolean(false)
            val r = Runtime.getRuntime()
            val base = r.totalMemory() - r.freeMemory()
            val sampler = Thread { while (!stopA.get()) { peakA.accumulateAndGet(r.totalMemory() - r.freeMemory(), ::maxOf); Thread.sleep(2) } }
            sampler.start()
            val t = System.nanoTime()
            val data = GameDataBuilder().build(
                File(dir, "pvpoke-gamemaster.json").inputStream().buffered(),
                File(dir, "pokeminers-gamemaster.json").inputStream().buffered(),
                League.entries.associateWith { File(dir, "rankings-${it.cap}.json").inputStream().buffered() },
                "test",
            )
            val el = ms(t)
            stopA.set(true); sampler.join()
            val enc = GameDataSources.encode(data)
            println("ROB build: species=${data.species.size}, json=${enc.length / 1024} KB")
            return el to (peakA.get() - base) / 1048576.0
        }
        repeat(2) { val (t, p) = run(); println("ROB GameDataBuilder.build = %.0f ms, pico de heap sobre base ≈ %.0f MB (muestreo, sin GC forzado)".format(t, p)) }
    }

    /** Ejecuta el build en JVMs hijas con -Xmx limitado para encontrar el heap mínimo. */
    @Test
    fun builderMinimumHeap() {
        val dir = cacheDir() ?: return println("ROB sin data-cache, se omite")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val cp = System.getProperty("java.class.path")
        for (mb in listOf(32, 48, 64, 96)) {
            for (mode in listOf("build", "decode")) {
                val p = ProcessBuilder(java, "-Xmx${mb}m", "-cp", cp, "com.esmeraldago.core.critic.HeapProbe", mode, dir.absolutePath,
                    file("app/src/main/assets/gamedata.json").absolutePath)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(120, TimeUnit.SECONDS)
                val ok = out.contains("OK")
                println("ROB -Xmx${mb}m $mode: ${if (ok) "OK" else "FALLA (" + out.lineSequence().firstOrNull { "Error" in it || "Exception" in it } + ")"} ${out.lineSequence().firstOrNull { it.startsWith("OK") } ?: ""}")
            }
        }
    }

    @Test
    fun builderRejectsBrokenInputs() {
        val dir = cacheDir() ?: return println("ROB sin data-cache, se omite")
        fun s(text: String) = ByteArrayInputStream(text.toByteArray())
        val pvpoke = File(dir, "pvpoke-gamemaster.json").readText()
        val ranks = League.entries.associateWith { File(dir, "rankings-${it.cap}.json").readText() }
        // Game Master sin PLAYER_LEVEL_SETTINGS (respuesta incompleta pero JSON válido)
        val e1 = runCatching { GameDataBuilder().build(s(pvpoke), s("[]"), ranks.mapValues { s(it.value) }, "") }.exceptionOrNull()
        println("ROB miners vacío -> ${e1?.javaClass?.simpleName}: ${e1?.message}")
        // Descarga parcial (truncada)
        val e2 = runCatching { GameDataBuilder().build(s(pvpoke.take(pvpoke.length / 2)), File(dir, "pokeminers-gamemaster.json").inputStream().buffered(), ranks.mapValues { s(it.value) }, "") }.exceptionOrNull()
        println("ROB pvpoke truncado -> ${e2?.javaClass?.simpleName}: ${e2?.message?.take(100)}")
        // Rankings vacíos: JSON válido -> se acepta sin validación y se pierde PvP
        val d = GameDataBuilder().build(s(pvpoke), File(dir, "pokeminers-gamemaster.json").inputStream().buffered(), ranks.mapValues { s("[]") }, "")
        val v = runCatching { GameDataSources.validate(d) }.exceptionOrNull()
        println("ROB rankings '[]' -> build con pvp=${d.pvp.mapValues { it.value.size }}; validate() -> ${v?.message}")
        assertTrue(e1 != null && e2 != null)
        assertTrue(v != null, "validate() debe rechazar rankings vacíos")
        GameDataSources.validate(idx.data) // los datos incluidos deben pasar
    }
}

/** Proceso hijo para medir heap mínimo. */
object HeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[1])
        val t = System.nanoTime()
        if (args[0] == "build") {
            val d = GameDataBuilder().build(
                File(dir, "pvpoke-gamemaster.json").inputStream().buffered(),
                File(dir, "pokeminers-gamemaster.json").inputStream().buffered(),
                League.entries.associateWith { File(dir, "rankings-${it.cap}.json").inputStream().buffered() },
                "probe",
            )
            val enc = GameDataSources.encode(d)
            println("OK build species=${d.species.size} enc=${enc.length} t=${(System.nanoTime() - t) / 1_000_000} ms")
        } else {
            val i = GameIndex(GameDataSources.decode(File(args[2]).readText()))
            println("OK decode species=${i.species.size} t=${(System.nanoTime() - t) / 1_000_000} ms")
        }
    }
}
