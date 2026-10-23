package com.esmeraldago.core.calc

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.model.Types

/**
 * Combates Dinamax (Max Battles). Para un Dynamax común el tipo del Max Attack lo define el ataque
 * rápido; un Gigamax usa SIEMPRE su movimiento G-Max, y algunas especies (Zacian/Zamazenta Coronados,
 * Eternatus) tienen un Max Attack exclusivo que también reemplaza al genérico.
 */
object MaxCalc {
    /** Poder del Max Attack por nivel (1..3). */
    val DYNAMAX_POWER = listOf(250.0, 300.0, 350.0)
    val GMAX_POWER = listOf(350.0, 400.0, 450.0)

    data class MaxResult(
        val species: Species,
        val gigantamax: Boolean,
        val fast: Move,
        val maxMoveName: String,
        val maxMoveType: String,
        /** Daño de un Max Attack. */
        val maxDamage: Double,
        /** DPS del ataque rápido (llena la barra Max). */
        val fastDps: Double,
        /** Aguante: Def × PS (más es mejor para tanquear con Max Guard). */
        val bulk: Double,
        /** Multiplicador de daño recibido del jefe (1 = neutro, < 1 = resiste). */
        val damageTakenMult: Double,
    ) {
        val usesElite get() = species.isElite(fast.id)
        val displayName get() = if (gigantamax) "${species.name} (G-Max)" else species.name
    }

    private fun variants(s: Species): List<Boolean> = buildList {
        if (s.dynamax) add(false)
        if (s.gigantamax) add(true)
    }

    /** Tipo del Max Attack que usaría esta variante con este rápido. */
    fun maxMoveType(s: Species, gmax: Boolean, fast: Move): String = when {
        gmax && s.gmaxType != null -> s.gmaxType
        !gmax && s.specialMaxType != null -> s.specialMaxType
        else -> fast.type
    }

    fun evaluate(
        s: Species, gmax: Boolean, fast: Move, idx: GameIndex,
        level: Double = 40.0, iv: Ivs = Ivs.PERFECT, maxMoveLevel: Int = 3,
        target: PveTarget = PveTarget(),
    ): MaxResult {
        val st = PveCalc.stats(s, level, iv, idx)
        val type = maxMoveType(s, gmax, fast)
        val usesGmax = gmax && s.gmaxType != null
        val power = (if (usesGmax) GMAX_POWER else DYNAMAX_POWER)[maxMoveLevel.coerceIn(1, 3) - 1]
        val maxDmg = PveCalc.damage(power, type, st.atk, target.def, s.types, target)
        val fastDmg = PveCalc.damage(fast.power, fast.type, st.atk, target.def, s.types, target)
        val taken = if (target.types.isEmpty()) 1.0 else bossDamageMultiplier(target, s.types)
        val name = when {
            usesGmax -> s.gmaxMove ?: "G-Max"
            !gmax && s.specialMaxMove != null -> s.specialMaxMove
            else -> idx.data.maxMoveNames[type] ?: "Max ${Types.es(type)}"
        }
        return MaxResult(
            species = s, gigantamax = gmax, fast = fast,
            maxMoveName = name, maxMoveType = type,
            maxDamage = maxDmg,
            fastDps = fastDmg / (fast.durationMs / 1000.0),
            bulk = st.def * st.hp,
            damageTakenMult = taken,
        )
    }

    /** Daño relativo que el jefe le hace a estos tipos (promedio de sus ataques conocidos). */
    private fun bossDamageMultiplier(target: PveTarget, defenderTypes: List<String>): Double {
        val moveTypes = (target.fastMoves + target.chargedMoves).map { it.type }.ifEmpty { target.types }
        return moveTypes.map { Types.effectiveness(it, defenderTypes) }.average()
    }

    /** Mejores atacantes Dinamax cuyo Max Attack es del tipo pedido. */
    fun topAttackers(type: String, idx: GameIndex, level: Double = 40.0, maxMoveLevel: Int = 3, limit: Int = 40): List<MaxResult> {
        val target = PveTarget(assumeWeakTo = type)
        return idx.released.flatMap { s ->
            Interrupt.check()
            variants(s).mapNotNull { gmax ->
                PveCalc.usableFast(s, idx).filter { maxMoveType(s, gmax, it) == type }
                    .map { evaluate(s, gmax, it, idx, level, maxMoveLevel = maxMoveLevel, target = target) }
                    .maxByOrNull { it.maxDamage * 1000 + it.fastDps }
            }
        }.sortedWith(compareByDescending<MaxResult> { it.maxDamage }.thenByDescending { it.fastDps }).take(limit)
    }

    /** Mejores tanques genéricos (Max Guard): mayor Def × PS. */
    fun topTanks(idx: GameIndex, level: Double = 40.0, limit: Int = 40): List<MaxResult> =
        idx.released.flatMap { s ->
            variants(s).mapNotNull { gmax ->
                PveCalc.usableFast(s, idx).firstOrNull()?.let { evaluate(s, gmax, it, idx, level) }
            }
        }.sortedByDescending { it.bulk }.take(limit)

    fun bossTarget(boss: Species, idx: GameIndex, bossCpm: Double = 0.79) = PveTarget(
        types = boss.types,
        def = (boss.def + 15) * bossCpm,
        atk = (boss.atk + 15) * bossCpm,
        fastMoves = PveCalc.usableFast(boss, idx),
        chargedMoves = PveCalc.usableCharged(boss, idx),
    )

    /** Contra un jefe Dinamax concreto: atacantes (por daño) y tanques (aguante ajustado por resistencias). */
    fun versusBoss(
        boss: Species, idx: GameIndex, bossCpm: Double = 0.79, level: Double = 40.0, maxMoveLevel: Int = 3,
    ): Pair<List<MaxResult>, List<MaxResult>> {
        val target = bossTarget(boss, idx, bossCpm)
        val results = idx.released.flatMap { s ->
            Interrupt.check()
            variants(s).flatMap { gmax ->
                PveCalc.usableFast(s, idx).map { evaluate(s, gmax, it, idx, level, maxMoveLevel = maxMoveLevel, target = target) }
            }
        }
        val grouped = results.groupBy { it.species.id to it.gigantamax }.values
        val attackers = grouped.map { list -> list.maxBy { it.maxDamage } }.sortedByDescending { it.maxDamage }
        val tanks = grouped.map { it.first() }.sortedByDescending { it.bulk / it.damageTakenMult }
        return attackers.take(30) to tanks.take(30)
    }
}
