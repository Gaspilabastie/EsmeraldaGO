package com.esmeraldago.core.calc

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.model.Species
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Ivs(val atk: Int, val def: Int, val sta: Int) {
    val total get() = atk + def + sta
    val percent get() = (total * 100.0 / 45).roundToInt()
    override fun toString() = "$atk/$def/$sta"

    companion object {
        val PERFECT = Ivs(15, 15, 15)
        val ALL: List<Ivs> = buildList {
            for (a in 0..15) for (d in 0..15) for (s in 0..15) add(Ivs(a, d, s))
        }
    }
}

data class PowerUpCost(val stardust: Int, val candy: Int, val xlCandy: Int) {
    operator fun plus(o: PowerUpCost) = PowerUpCost(stardust + o.stardust, candy + o.candy, xlCandy + o.xlCandy)
    val isZero get() = stardust == 0 && candy == 0 && xlCandy == 0
}

object PokeMath {
    const val SHADOW_ATK = 1.2
    const val SHADOW_DEF = 5.0 / 6.0

    fun cp(s: Species, level: Double, iv: Ivs, idx: GameIndex): Int {
        val m = idx.cpm(level)
        val v = (s.atk + iv.atk) * sqrt((s.def + iv.def).toDouble()) * sqrt((s.sta + iv.sta).toDouble()) * m * m / 10.0
        return max(10, floor(v).toInt())
    }

    fun hp(s: Species, level: Double, iv: Ivs, idx: GameIndex): Int =
        max(10, floor((s.sta + iv.sta) * idx.cpm(level)).toInt())

    // ------------------------------------------------------------- Costos de power-up

    /** Polvo estelar para subir desde [level] a level + 0.5. */
    fun dustFor(level: Double): Int {
        val l = floor(level).toInt()
        return when {
            l < 3 -> 200; l < 5 -> 400; l < 7 -> 600; l < 9 -> 800; l < 11 -> 1000
            l < 13 -> 1300; l < 15 -> 1600; l < 17 -> 1900; l < 19 -> 2200; l < 21 -> 2500
            l < 23 -> 3000; l < 25 -> 3500; l < 27 -> 4000; l < 29 -> 4500; l < 31 -> 5000
            l < 33 -> 6000; l < 35 -> 7000; l < 37 -> 8000; l < 39 -> 9000; l < 41 -> 10000
            l < 43 -> 11000; l < 45 -> 12000; l < 47 -> 13000; l < 49 -> 14000
            else -> 15000
        }
    }

    /** (caramelos, caramelos XL) para subir desde [level] a level + 0.5. */
    fun candyFor(level: Double): Pair<Int, Int> {
        val l = floor(level).toInt()
        return when {
            l < 11 -> 1 to 0; l < 21 -> 2 to 0; l < 26 -> 3 to 0; l < 31 -> 4 to 0
            l < 33 -> 6 to 0; l < 35 -> 8 to 0; l < 37 -> 10 to 0; l < 39 -> 12 to 0; l < 40 -> 15 to 0
            l < 42 -> 0 to 10; l < 44 -> 0 to 12; l < 46 -> 0 to 15; l < 48 -> 0 to 17
            else -> 0 to 20
        }
    }

    fun powerUpCost(
        from: Double, to: Double,
        shadow: Boolean = false, purified: Boolean = false, lucky: Boolean = false,
    ): PowerUpCost {
        if (to <= from) return PowerUpCost(0, 0, 0)
        val mult = when {
            shadow -> 1.2
            purified -> 0.9
            else -> 1.0
        }
        var dust = 0.0; var candy = 0.0; var xl = 0.0
        var l = from
        while (l < to - 1e-9) {
            dust += dustFor(l) * mult * (if (lucky) 0.5 else 1.0)
            val (c, x) = candyFor(l)
            candy += Math.ceil(c * mult)
            xl += Math.ceil(x * mult)
            l += 0.5
        }
        return PowerUpCost(dust.roundToInt(), candy.toInt(), xl.toInt())
    }

    /** Niveles cuyo costo de power-up coincide con el polvo mostrado en pantalla. */
    fun levelsForDustCost(dust: Int, shadow: Boolean, purified: Boolean, lucky: Boolean, maxLevel: Double): List<Double> {
        val mult = (if (shadow) 1.2 else if (purified) 0.9 else 1.0) * (if (lucky) 0.5 else 1.0)
        val out = mutableListOf<Double>()
        var l = 1.0
        // Con polvo solo se sube hasta 50 (el 51 es por Mejor Compañero).
        while (l < minOf(maxLevel, 50.0)) {
            if ((dustFor(l) * mult).roundToInt() == dust) out += l
            l += 0.5
        }
        return out
    }

    // ------------------------------------------------------------- Solver de IV

    data class IvCandidate(val level: Double, val ivs: Ivs)

    /**
     * Combinaciones (nivel, IV) compatibles con lo observado. Cualquier restricción en null se ignora.
     * [minIv] sirve para intercambios con suerte (12), purificados (2: purificar suma +2) o atrapados de incursión (10).
     */
    fun solveIvs(
        s: Species, cp: Int, idx: GameIndex,
        hp: Int? = null,
        levels: List<Double>? = null,
        appraisal: Ivs? = null,
        minIv: Int = 0,
    ): List<IvCandidate> {
        val lvls = levels ?: generateSequence(1.0) { it + 0.5 }.takeWhile { it <= idx.maxLevel }.toList()
        val ivs = appraisal?.let { listOf(it) } ?: Ivs.ALL.filter { it.atk >= minIv && it.def >= minIv && it.sta >= minIv }
        val out = mutableListOf<IvCandidate>()
        for (l in lvls) for (iv in ivs) {
            if (hp != null && hp(s, l, iv, idx) != hp) continue
            if (cp(s, l, iv, idx) == cp) out += IvCandidate(l, iv)
        }
        return out
    }

    // ------------------------------------------------------------- PvP (stat product)

    data class PvpIvResult(
        val ivs: Ivs,
        val level: Double,
        val cp: Int,
        val statProduct: Double,
        val rank: Int,
        /** % del stat product respecto del IV #1. */
        val percentOfBest: Double,
    )

    /** Mejor nivel bajo el tope de CP (o null si ni a nivel 1 entra). */
    fun bestLevelUnderCap(s: Species, iv: Ivs, cap: Int, maxLevel: Double, idx: GameIndex): Double? {
        fun lvl(i: Int) = 1.0 + i / 2.0
        if (cp(s, 1.0, iv, idx) > cap) return null
        // Trabajamos con índices de medio nivel: cp(lo) <= cap < cp(hi)
        var lo = 0
        var hi = ((maxLevel - 1) * 2).roundToInt()
        if (cp(s, lvl(hi), iv, idx) <= cap) return lvl(hi)
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cp(s, lvl(mid), iv, idx) <= cap) lo = mid else hi = mid
        }
        return lvl(lo)
    }

    fun statProduct(s: Species, level: Double, iv: Ivs, idx: GameIndex): Double {
        val c = idx.cpm(level)
        return (s.atk + iv.atk) * c * (s.def + iv.def) * c * floor((s.sta + iv.sta) * c)
    }

    /**
     * Tabla completa de los IV ordenada por stat product. Arma 4096 objetos: no se cachea y solo
     * conviene para mostrar los primeros puestos (para un rank puntual usar [pvpRankOf]).
     */
    fun pvpTable(s: Species, cap: Int, maxLevel: Double, idx: GameIndex, limit: Int = Int.MAX_VALUE): List<PvpIvResult> {
        val rows = Ivs.ALL.mapNotNull { iv ->
            val lvl = bestLevelUnderCap(s, iv, cap, maxLevel, idx) ?: return@mapNotNull null
            Triple(iv, lvl, statProduct(s, lvl, iv, idx))
        }.sortedByDescending { it.third }
        val best = rows.firstOrNull()?.third ?: 1.0
        return rows.asSequence().take(limit).mapIndexed { i, (iv, lvl, sp) ->
            PvpIvResult(iv, lvl, cp(s, lvl, iv, idx), sp, i + 1, sp / best * 100)
        }.toList()
    }

    /** Stat products de todos los IV que entran en la liga, ordenados de mayor a menor (cacheado en el índice). */
    private fun sortedStatProducts(s: Species, cap: Int, maxLevel: Double, idx: GameIndex): FloatArray =
        idx.cachedStatProducts("${s.id}|$cap|$maxLevel") {
            Ivs.ALL.mapNotNull { iv ->
                bestLevelUnderCap(s, iv, cap, maxLevel, idx)?.let { statProduct(s, it, iv, idx).toFloat() }
            }.sortedDescending().toFloatArray()
        }

    /** Rank PvP de un IV puntual: el puesto es 1 + cuántos IV tienen mayor stat product. */
    fun pvpRankOf(s: Species, iv: Ivs, cap: Int, maxLevel: Double, idx: GameIndex): PvpIvResult? {
        val lvl = bestLevelUnderCap(s, iv, cap, maxLevel, idx) ?: return null
        val sp = statProduct(s, lvl, iv, idx)
        val sorted = sortedStatProducts(s, cap, maxLevel, idx)
        val spF = sp.toFloat()
        // Búsqueda binaria en orden descendente: primer índice con valor <= sp (mismo redondeo a Float).
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] > spF) lo = mid + 1 else hi = mid
        }
        return PvpIvResult(iv, lvl, cp(s, lvl, iv, idx), sp, lo + 1, sp / sorted[0] * 100)
    }
}
