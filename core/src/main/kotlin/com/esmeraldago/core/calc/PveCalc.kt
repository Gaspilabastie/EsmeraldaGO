package com.esmeraldago.core.calc

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.model.Types
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** Nivel de incursión: multiplicador de CP del jefe. */
enum class RaidTier(val label: String, val cpm: Double, val shadow: Boolean = false) {
    T1("Nivel 1", 0.5974),
    T3("Nivel 3", 0.73),
    T5("Nivel 5", 0.79),
    MEGA("Mega", 0.79),
    MEGA_LEGENDARY("Mega legendaria", 0.79),
    SHADOW_T1("Oscura nivel 1", 0.5974, shadow = true),
    SHADOW_T3("Oscura nivel 3", 0.73, shadow = true),
    SHADOW_T5("Oscura nivel 5", 0.79, shadow = true),
}

data class BattleStats(val atk: Double, val def: Double, val hp: Int)

data class PveResult(
    val species: Species,
    val fast: Move,
    val charged: Move,
    val dps: Double,
    val tdo: Double,
    /** Puntaje combinado (DPS³·TDO)^¼, el mismo criterio que usan GamePress / GO Hub. */
    val score: Double,
    val level: Double,
    val ivs: Ivs,
) {
    /** Usa un movimiento que solo se enseña con MT Élite (legado / evento). */
    val usesElite get() = species.isElite(fast.id) || species.isElite(charged.id)
}

/**
 * Contra quién se pelea. [types] vacío = jefe genérico; en ese caso [assumeWeakTo] simula un jefe
 * débil a ese tipo (para los rankings "mejores atacantes de tipo X").
 */
data class PveTarget(
    val types: List<String> = emptyList(),
    val def: Double = GENERIC_DEF,
    val atk: Double? = null,
    val fastMoves: List<Move> = emptyList(),
    val chargedMoves: List<Move> = emptyList(),
    val assumeWeakTo: String? = null,
    val weatherBoosted: Set<String> = emptySet(),
) {
    companion object {
        /** Defensa típica de un jefe de nivel 5: (200 + 15) * 0.79 ≈ 170. */
        const val GENERIC_DEF = 170.0
    }
}

object PveCalc {
    private val EXCLUDED_MOVES = setOf("FRUSTRATION", "RETURN", "TRANSFORM", "STRUGGLE")

    /** Los Primal potencian también otros tipos además de los suyos. */
    private val PRIMAL_BOOST = mapOf(
        "kyogre_primal" to listOf("water", "electric", "bug"),
        "groudon_primal" to listOf("ground", "fire", "grass"),
    )

    fun stats(s: Species, level: Double, iv: Ivs, idx: GameIndex): BattleStats {
        val c = idx.cpm(level)
        val shadow = s.shadow
        return BattleStats(
            atk = (s.atk + iv.atk) * c * (if (shadow) PokeMath.SHADOW_ATK else 1.0),
            def = (s.def + iv.def) * c * (if (shadow) PokeMath.SHADOW_DEF else 1.0),
            hp = floor((s.sta + iv.sta) * c).toInt().coerceAtLeast(10),
        )
    }

    fun damage(
        power: Double, moveType: String, atk: Double, def: Double,
        attackerTypes: List<String>, target: PveTarget, megaTypes: List<String>? = null,
        defenderTypes: List<String> = target.types,
    ): Double {
        val stab = if (moveType in attackerTypes) 1.2 else 1.0
        val eff = when {
            defenderTypes.isNotEmpty() -> Types.effectiveness(moveType, defenderTypes)
            target.assumeWeakTo == moveType -> Types.SE
            else -> 1.0
        }
        val weather = if (moveType in target.weatherBoosted) 1.2 else 1.0
        // Un Mega activo potencia 1.1x todo y 1.3x los ataques de su mismo tipo.
        val mega = megaTypes?.let { if (moveType in it) 1.3 else 1.1 } ?: 1.0
        return floor(0.5 * power * atk / def * stab * eff * weather * mega) + 1
    }

    /** DPS que recibe el atacante. Sin jefe concreto se usa la convención de GamePress: 900 / Def. */
    fun incomingDps(attacker: Species, att: BattleStats, target: PveTarget): Double {
        val bossAtk = target.atk ?: return 900.0 / att.def
        val fasts = target.fastMoves.ifEmpty { return 900.0 / att.def }
        val chargeds = target.chargedMoves.ifEmpty { fasts }
        var sum = 0.0
        var n = 0
        for (f in fasts) for (c in chargeds) {
            // El jefe también tiene STAB y potencia por clima.
            val fd = damage(f.power, f.type, bossAtk, att.def, target.types, target, defenderTypes = attacker.types)
            val cd = damage(c.power, c.type, bossAtk, att.def, target.types, target, defenderTypes = attacker.types)
            val fastsPerCharge = if (c.fast || f.energy <= 0) 1.0 else ceil(c.energy.toDouble() / f.energy)
            // Los jefes esperan ~2 s entre ataques.
            val time = fastsPerCharge * (f.durationMs / 1000.0 + 2.0) + (c.durationMs / 1000.0 + 2.0)
            sum += (fd * fastsPerCharge + cd) / time
            n++
        }
        return (sum / n).coerceAtLeast(1.0)
    }

    fun evaluate(
        s: Species, fast: Move, charged: Move, level: Double, iv: Ivs,
        target: PveTarget, idx: GameIndex,
    ): PveResult {
        val st = stats(s, level, iv, idx)
        val megaTypes = if (s.mega) PRIMAL_BOOST[s.id] ?: s.types else null
        val fDmg = damage(fast.power, fast.type, st.atk, target.def, s.types, target, megaTypes)
        val cDmg = damage(charged.power, charged.type, st.atk, target.def, s.types, target, megaTypes)
        val fDur = fast.durationMs / 1000.0
        val cDur = charged.durationMs / 1000.0
        val fdps = fDmg / fDur
        val feps = fast.energy / fDur
        val cdps = cDmg / cDur
        val ceps = charged.energy / cDur
        val y = incomingDps(s, st, target)
        val dps = if (cdps <= fdps || feps <= 0) fdps else {
            val x = 0.5 * charged.energy + 0.5 * fast.energy
            val dps0 = (fdps * ceps + cdps * feps) / (ceps + feps)
            (dps0 + (cdps - fdps) / (ceps + feps) * (0.5 - x / st.hp) * y).coerceAtLeast(fdps)
        }
        val tdo = dps * st.hp / y
        return PveResult(s, fast, charged, dps, tdo, (dps.pow(3) * tdo).pow(0.25), level, iv)
    }

    fun usableFast(s: Species, idx: GameIndex) = s.fast.mapNotNull { idx.move(it) }.filter { it.power > 0 && it.durationMs > 0 && it.id !in EXCLUDED_MOVES }
    fun usableCharged(s: Species, idx: GameIndex) = s.charged.mapNotNull { idx.move(it) }.filter { it.power > 0 && it.durationMs > 0 && it.id !in EXCLUDED_MOVES }

    /**
     * Mejor moveset de la especie. [chargedType] fuerza el tipo del ataque cargado.
     * [allowElite] = false excluye movimientos que requieren MT Élite; [allowHiddenPower] = false descarta
     * Hidden Power (su tipo es aleatorio: no conviene asumirlo cuando no se conocen los movimientos).
     */
    fun best(
        s: Species, target: PveTarget, idx: GameIndex,
        level: Double = 40.0, iv: Ivs = Ivs.PERFECT,
        chargedType: String? = null, allowElite: Boolean = true, allowHiddenPower: Boolean = true,
        fastMoves: List<Move>? = null, chargedMoves: List<Move>? = null,
    ): PveResult? {
        val fs = (fastMoves ?: usableFast(s, idx))
            .filter { allowElite || !s.isElite(it.id) }
            .filter { allowHiddenPower || !it.id.startsWith("HIDDEN_POWER") }
        val cs = (chargedMoves ?: usableCharged(s, idx))
            .filter { chargedType == null || it.type == chargedType }
            .filter { allowElite || !s.isElite(it.id) }
        var best: PveResult? = null
        for (f in fs) for (c in cs) {
            val r = evaluate(s, f, c, level, iv, target, idx)
            if (best == null || r.score > best.score) best = r
        }
        return best
    }

    /**
     * Ranking de mejores atacantes de un tipo. Si el mejor moveset de una especie usa
     * movimientos de MT Élite, también se agrega su mejor alternativa "normal".
     */
    fun topAttackers(
        type: String, idx: GameIndex, level: Double = 40.0,
        includeShadow: Boolean = true, includeMega: Boolean = true, includeElite: Boolean = true,
        limit: Int = 60,
        includeNonBudget: Boolean = true,
    ): List<PveResult> {
        val target = PveTarget(assumeWeakTo = type)
        val out = mutableListOf<PveResult>()
        for (s in idx.released) {
            Interrupt.check()
            if (s.shadow && !includeShadow) continue
            if (s.mega && !includeMega) continue
            if (s.nonBudget && !includeNonBudget) continue
            val withSpecial = best(s, target, idx, level, chargedType = type, allowElite = includeElite) ?: continue
            out += withSpecial
            if (withSpecial.usesElite) {
                best(s, target, idx, level, chargedType = type, allowElite = false)?.let { out += it }
            }
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    fun bossTarget(boss: Species, tier: RaidTier, idx: GameIndex, weather: Set<String> = emptySet()): PveTarget {
        val shadowAtk = if (boss.shadow || tier.shadow) PokeMath.SHADOW_ATK else 1.0
        return PveTarget(
            types = boss.types,
            def = (boss.def + 15) * tier.cpm,
            atk = (boss.atk + 15) * tier.cpm * shadowAtk,
            fastMoves = usableFast(boss, idx),
            chargedMoves = usableCharged(boss, idx),
            weatherBoosted = weather,
        )
    }

    /** Mejores counters contra un jefe concreto, entre todas las especies. */
    fun counters(
        boss: Species, tier: RaidTier, idx: GameIndex, level: Double = 40.0,
        includeShadow: Boolean = true, includeMega: Boolean = true, includeElite: Boolean = true,
        weather: Set<String> = emptySet(), limit: Int = 40,
    ): List<PveResult> {
        val target = bossTarget(boss, tier, idx, weather)
        return idx.released.asSequence()
            .filter { (includeShadow || !it.shadow) && (includeMega || !it.mega) }
            .mapNotNull { Interrupt.check(); best(it, target, idx, level, allowElite = includeElite) }
            .sortedByDescending { it.score }
            .take(limit).toList()
    }
}
