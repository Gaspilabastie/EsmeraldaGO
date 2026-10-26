package com.esmeraldago.core.reco

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.Interrupt
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.calc.PowerUpCost
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.calc.PveResult
import com.esmeraldago.core.calc.PveTarget
import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.model.Types
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

enum class AdviceKind(val label: String) {
    PVE("Incursiones"), PVP("PvP"), MAX("Dinamax"), EVOLVE("Evolucionar"), CATCH("Atrapar")
}

/** Modo de juego al que apunta un consejo (para filtrar). */
enum class GameMode(val label: String) { PVE("PvE"), PVP("PvP"), MAX("Dinamax") }

data class Advice(
    val kind: AdviceKind,
    val species: Species,
    val owned: OwnedPokemon?,
    val title: String,
    val detail: String,
    val priority: Double,
    val cost: PowerUpCost? = null,
    /** Modos de juego en los que destaca. */
    val modes: Set<GameMode> = emptySet(),
    /** Tipos relevantes: tipo de ataque en PvE, tipo de Max Attack en Dinamax, tipos de la especie en PvP. */
    val types: Set<String> = emptySet(),
)

/** [assumedMoves] = no se conocen sus ataques y se asumió un moveset normal (sin MT Élite). */
data class OwnedPve(val owned: OwnedPokemon, val result: PveResult, val asMega: Boolean, val assumedMoves: Boolean = false)

data class PvpCandidate(
    val owned: OwnedPokemon,
    /** Especie con la que compite (puede ser una evolución). */
    val species: Species,
    val league: League,
    val metaRank: Int,
    val metaScore: Double,
    val iv: PokeMath.PvpIvResult,
    val needsEvolve: Boolean,
    val cost: PowerUpCost,
    val moveset: List<String>,
    /** Movimientos del moveset meta que el Pokémon no tiene (solo si se conocen sus ataques). */
    val missingMoves: List<String> = emptyList(),
    /** Alguno de los faltantes requiere MT Élite. */
    val needsEliteTm: Boolean = false,
) {
    /** Valor combinado: relevancia en el meta × calidad de IV, penalizado si le falta un ataque clave. */
    val value: Double
        get() = metaScore * (iv.percentOfBest / 100.0) *
            (if (needsEliteTm) 0.85 else if (missingMoves.isNotEmpty()) 0.95 else 1.0)
}

data class OwnedMax(val owned: OwnedPokemon, val result: MaxCalc.MaxResult, val role: String = "")

/**
 * @param maxLevel nivel máximo para PvP: 40 = sin caramelos XL, 50, o 51 con Mejor Compañero.
 */
class Recommender(val idx: GameIndex, val maxLevel: Double = 50.0) {
    val allowXl get() = maxLevel > 40.0

    // ------------------------------------------------------------------ utilidades

    fun battleSpecies(p: OwnedPokemon): Species? = idx[p.battleId] ?: idx[p.speciesId]
    fun ivs(p: OwnedPokemon) = Ivs(p.ivAtk, p.ivDef, p.ivSta)

    private fun knownFast(p: OwnedPokemon): List<Move>? = idx.move(p.fast)?.let { listOf(it) }
    private fun knownCharged(p: OwnedPokemon): List<Move>? =
        p.chargedMoves.mapNotNull { idx.move(it) }.filter { it.power > 0 }.ifEmpty { null }
    private fun movesKnown(p: OwnedPokemon) = knownFast(p) != null && knownCharged(p) != null

    /** Mejor resultado PvE del Pokémon; si no se conocen sus ataques asume un moveset normal. */
    private fun bestFor(
        p: OwnedPokemon, s: Species, target: PveTarget, level: Double = p.level, chargedType: String? = null,
    ): PveResult? {
        val known = movesKnown(p)
        return PveCalc.best(
            s, target, idx, level, ivs(p), chargedType = chargedType,
            allowElite = known, allowHiddenPower = known,
            fastMoves = knownFast(p), chargedMoves = knownCharged(p),
        )
    }

    private val topByType = ConcurrentHashMap<String, List<PveResult>>()
    fun topAttackers(type: String): List<PveResult> =
        topByType.getOrPut(type) { PveCalc.topAttackers(type, idx, 40.0, limit = 60) }

    /** Puesto que tendría este puntaje dentro del top del tipo (1 = mejor). */
    fun rankAmong(type: String, score: Double): Int = topAttackers(type).count { it.score > score + 1e-9 } + 1

    private val maxTopByType = ConcurrentHashMap<String, List<MaxCalc.MaxResult>>()
    fun maxTop(type: String): List<MaxCalc.MaxResult> =
        maxTopByType.getOrPut(type) { MaxCalc.topAttackers(type, idx, limit = 30) }

    // ------------------------------------------------------------------ PvE

    /** Tus mejores Pokémon contra un jefe de incursión (máximo un Mega por equipo). */
    fun raidTeam(
        boss: Species, tier: RaidTier, collection: List<OwnedPokemon>,
        weather: Set<String> = emptySet(), size: Int = 6,
    ): List<OwnedPve> {
        val target = PveCalc.bossTarget(boss, tier, idx, weather)
        val options = collection.flatMap { p ->
            Interrupt.check()
            val s = battleSpecies(p) ?: return@flatMap emptyList()
            val assumed = !movesKnown(p)
            val normal = bestFor(p, s, target)?.let { OwnedPve(p, it, false, assumed) }
            // Si la especie tiene Mega (y no es shadow), considerar megaevolucionarlo.
            val megas = if (p.shadow) emptyList() else idx.megasOf(s).mapNotNull { m ->
                bestFor(p, m, target)?.let { OwnedPve(p, it, true, assumed) }
            }
            listOfNotNull(normal) + megas
        }.sortedByDescending { it.result.score * (if (it.assumedMoves) 0.9 else 1.0) }

        val team = mutableListOf<OwnedPve>()
        for (o in options) {
            if (team.size == size) break
            if (team.any { it.owned.uid == o.owned.uid }) continue
            if (o.asMega && team.any { it.asMega }) continue
            team += o
        }
        return team
    }

    // ------------------------------------------------------------------ PvP

    /** Memo por (liga, colección): powerUpAdvice, pvpTeam y catchAdvice piden lo mismo varias veces. */
    private val candidateMemo = object : LinkedHashMap<Pair<League, Int>, List<PvpCandidate>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<League, Int>, List<PvpCandidate>>?) = size > 24
    }

    fun pvpCandidates(league: League, collection: List<OwnedPokemon>): List<PvpCandidate> {
        // Clave = contenido (hash) + tamaño + identidad del primer/último uid: evita colisiones prácticas.
        val key = league to (collection.hashCode() * 31 + collection.size)
        synchronized(candidateMemo) { candidateMemo[key] }?.let { cached ->
            if (cached.isEmpty() || collection.any { it.uid == cached.first().owned.uid }) return cached
        }
        return computePvpCandidates(league, collection).also { synchronized(candidateMemo) { candidateMemo[key] = it } }
    }

    private fun computePvpCandidates(league: League, collection: List<OwnedPokemon>): List<PvpCandidate> =
        collection.flatMap { p ->
            Interrupt.check()
            val s = idx[p.speciesId] ?: return@flatMap emptyList()
            val forms = listOf(s) + idx.descendants(s)
            val owned = (listOfNotNull(p.fast) + p.chargedMoves).toSet()
            forms.mapNotNull { form ->
                val t = idx.sameVariant(form, p.shadow)
                val (rank, meta) = idx.pvpRank(league, t.id) ?: return@mapNotNull null
                val iv = PokeMath.pvpRankOf(t, ivs(p), league.cap, maxLevel, idx) ?: return@mapNotNull null
                // Un Pokémon no puede bajar de nivel: si ya se pasó del tope, no sirve en esa liga.
                if (league != League.MASTER && p.level > iv.level + 1e-9) return@mapNotNull null
                val needsEvolve = t.baseId != s.baseId
                // Tras evolucionar los ataques cambian: solo se comparan si es la misma especie.
                val missing = if (owned.isEmpty() || needsEvolve) emptyList() else meta.moveset.filter { it !in owned }
                PvpCandidate(
                    owned = p, species = t, league = league, metaRank = rank, metaScore = meta.score, iv = iv,
                    needsEvolve = needsEvolve,
                    cost = PokeMath.powerUpCost(p.level, iv.level, p.shadow, p.purified, p.lucky),
                    moveset = meta.moveset,
                    missingMoves = missing,
                    needsEliteTm = missing.any { t.isElite(it) },
                )
            }
        }.sortedByDescending { it.value }

    /**
     * Arma un equipo de 3 evitando debilidades compartidas y cubriendo a los counters del primero.
     * Usa el mismo criterio de IV que los consejos y penaliza lo caro (polvo y sobre todo XL).
     */
    fun pvpTeam(league: League, collection: List<OwnedPokemon>): List<PvpCandidate> {
        val all = pvpCandidates(league, collection)
        val pool = (all.filter { ivGoodFor(league, it) }.ifEmpty { all }).take(60)
        val team = mutableListOf<PvpCandidate>()
        while (team.size < 3) {
            val next = pool
                .filter { c -> team.none { it.owned.uid == c.owned.uid || it.species.baseId == c.species.baseId } }
                .maxByOrNull { c -> c.value / sqrt(costFactor(c.cost)) + synergy(team, c) } ?: break
            team += next
        }
        return team
    }

    private fun weaknessWeights(types: List<String>): Map<String, Double> =
        Types.ALL.associateWith { Types.effectiveness(it, types) }.filterValues { it > 1.0 }
            .mapValues { if (it.value > 2.0) 2.0 else 1.0 } // doble debilidad pesa el doble

    private fun synergy(team: List<PvpCandidate>, c: PvpCandidate): Double {
        if (team.isEmpty()) return 0.0
        val cw = weaknessWeights(c.species.types)
        var bonus = 0.0
        for (m in team) {
            if (m.species.types.toSet() == c.species.types.toSet()) bonus -= 30.0 // mismo tipado
            val mw = weaknessWeights(m.species.types)
            bonus -= cw.entries.sumOf { (t, w) -> (mw[t] ?: 0.0) * w } * 8.0
            // Bonus si resiste lo que le pega supereficaz al compañero.
            bonus += mw.keys.count { Types.effectiveness(it, c.species.types) < 1.0 } * 3.0
            // Cubre a los que le ganan a un compañero (según PvPoke).
            val meta = idx.pvpRank(c.league, m.species.id)?.second
            val mine = idx.pvpRank(c.league, c.species.id)?.second
            if (meta != null && mine != null) bonus += meta.losses.count { it in mine.wins } * 5.0
        }
        return bonus
    }

    // ------------------------------------------------------------------ Dinamax

    fun maxOptions(collection: List<OwnedPokemon>, boss: Species? = null): Pair<List<OwnedMax>, List<OwnedMax>> {
        val mine = collection.filter { it.dynamax || it.gigantamax }
        val target = boss?.let { MaxCalc.bossTarget(it, idx) } ?: PveTarget()
        val evaluated = mine.mapNotNull { p ->
            Interrupt.check()
            val s = idx[p.speciesId] ?: return@mapNotNull null
            val fasts = knownFast(p) ?: PveCalc.usableFast(s, idx)
            fasts.map { MaxCalc.evaluate(s, p.gigantamax, it, idx, p.level, ivs(p), target = target) }
                .maxByOrNull { it.maxDamage }?.let { OwnedMax(p, it) }
        }
        val attackers = evaluated.sortedByDescending { it.result.maxDamage }
        val tanks = evaluated.sortedByDescending { it.result.bulk / it.result.damageTakenMult }
        return attackers to tanks
    }

    /** Equipo Dinamax de 3: un tanque (Max Guard) y los dos mejores atacantes, sin repetir Pokémon. */
    fun maxTeam(collection: List<OwnedPokemon>, boss: Species? = null): List<OwnedMax> {
        val (attackers, tanks) = maxOptions(collection, boss)
        val tank = tanks.firstOrNull() ?: return emptyList()
        val hitters = attackers.filter { it.owned.uid != tank.owned.uid }.take(2)
        return hitters.map { it.copy(role = "Atacante") } + tank.copy(role = "Tanque")
    }

    // ------------------------------------------------------------------ Qué subir

    /** Costo "percibido": el polvo y sobre todo los XL pesan; sin XL permitidos, lo que requiere XL queda fuera. */
    private fun costFactor(c: PowerUpCost?): Double =
        c?.let { 1.0 + it.stardust / 250_000.0 + it.xlCandy / 60.0 + it.candy / 400.0 } ?: 1.0

    fun powerUpAdvice(collection: List<OwnedPokemon>): List<Advice> {
        val out = mutableListOf<Advice>()

        // PvE: para cada tipo, tus 2 mejores ejemplares que entran en el top 20 a nivel 40.
        for (type in Types.ALL) {
            Interrupt.check()
            collection.mapNotNull { p ->
                if (p.level >= 40) return@mapNotNull null
                val s = battleSpecies(p) ?: return@mapNotNull null
                // Sin ataques conocidos solo se evalúan sus tipos (STAB): no se le inventa cobertura.
                if (!movesKnown(p) && type !in s.types) return@mapNotNull null
                val r = bestFor(p, s, PveTarget(assumeWeakTo = type), 40.0, chargedType = type) ?: return@mapNotNull null
                val rank = rankAmong(type, r.score)
                if (rank > 20) null else Triple(p, s, rank to r)
            }.sortedByDescending { it.third.second.score }.take(2).forEach { (p, s, rr) ->
                val (rank, r) = rr
                val cost = PokeMath.powerUpCost(p.level, 40.0, p.shadow, p.purified, p.lucky)
                val assumed = if (movesKnown(p)) "" else " (ataques estimados)"
                out += Advice(
                    AdviceKind.PVE, s, p,
                    "Subir ${p.nickname ?: s.name} a nivel 40",
                    "Atacante ${Types.es(type)} ~#$rank con ${r.fast.name} / ${r.charged.name}$assumed.",
                    priority = (100.0 - rank * 2) / sqrt(costFactor(cost)),
                    cost = cost,
                    modes = setOf(GameMode.PVE), types = setOf(type),
                )
            }
        }

        // PvP: buen meta e IV adecuados para la liga.
        for (league in League.entries) {
            Interrupt.check()
            pvpCandidates(league, collection)
                .filter { c -> c.metaRank <= 100 && ivGoodFor(league, c) }
                .filter { c -> !c.cost.isZero || c.needsEvolve } // si ya está listo no hay nada que hacer
                .distinctBy { it.species.id }
                .take(6)
                .forEach { c ->
                    val evo = if (c.needsEvolve) "evolucionar a ${c.species.name} y " else ""
                    val action = if (c.cost.isZero) "ya tiene el nivel justo" else "subir a nivel ${fmt(c.iv.level)} (PC ${c.iv.cp})"
                    val moves = when {
                        c.needsEliteTm -> " Requiere MT Élite: ${c.missingMoves.filter { c.species.isElite(it) }.joinToString { idx.move(it)?.name ?: it }}."
                        c.missingMoves.isNotEmpty() -> " Ataques por enseñar: ${c.missingMoves.joinToString { idx.move(it)?.name ?: it }}."
                        else -> ""
                    }
                    val estimated = if (c.owned.ivExact) "" else " (IV estimados)"
                    out += Advice(
                        if (c.needsEvolve) AdviceKind.EVOLVE else AdviceKind.PVP, c.species, c.owned,
                        "${league.label}: ${c.owned.nickname ?: c.species.name}",
                        "$evo$action".replaceFirstChar { it.uppercase() } +
                            ". Especie #${c.metaRank} en la liga · IV #${c.iv.rank} (${"%.1f".format(c.iv.percentOfBest)} %)$estimated.$moves",
                        priority = c.value / sqrt(costFactor(c.cost)),
                        cost = c.cost.takeUnless { it.isZero },
                        modes = setOf(GameMode.PVP), types = c.species.types.toSet(),
                    )
                }
        }

        // Dinamax: tus Dynamax que están en el top de su tipo de Max Attack.
        collection.filter { it.dynamax || it.gigantamax }.forEach { p ->
            val s = idx[p.speciesId] ?: return@forEach
            val fasts = knownFast(p) ?: PveCalc.usableFast(s, idx)
            val bestRank = fasts.mapNotNull { f ->
                val type = MaxCalc.maxMoveType(s, p.gigantamax, f)
                maxTop(type).indexOfFirst { it.species.id == s.id && it.gigantamax == p.gigantamax }
                    .takeIf { it >= 0 }?.let { it + 1 to type }
            }.minByOrNull { it.first } ?: return@forEach
            if (bestRank.first > 15) return@forEach
            val cost = PokeMath.powerUpCost(p.level, 40.0, p.shadow, p.purified, p.lucky).takeUnless { it.isZero }
            out += Advice(
                AdviceKind.MAX, s, p,
                "Dinamax: ${p.nickname ?: s.name}${if (p.gigantamax) " (G-Max)" else ""}",
                "Top #${bestRank.first} atacante Dinamax ${Types.es(bestRank.second)}. " +
                    (if (p.level < 40) "Subir a nivel 40 y mejorar el Max Attack." else "Mejorar el Max Attack."),
                priority = (80.0 - bestRank.first * 2) / sqrt(costFactor(cost)),
                cost = cost,
                modes = setOf(GameMode.MAX), types = setOf(bestRank.second),
            )
        }

        // Un solo consejo por Pokémon: el más valioso, con los demás usos como alternativa.
        val byOwner = out.groupBy { it.owned?.uid }
        return byOwner.flatMap { (uid, list) ->
            if (uid == null) list else {
                val sorted = list.sortedByDescending { it.priority }
                val top = sorted.first()
                val others = sorted.drop(1)
                // Si otra opción implica otra evolución (o subir más allá), son excluyentes: "O bien".
                val exclusive = others.any { it.species.baseId != top.species.baseId || it.kind == AdviceKind.EVOLVE }
                val label = if (exclusive) "\nO bien: " else "\nTambién sirve para: "
                val text = others.joinToString("; ") { it.title.substringBefore(":") + " — " + it.detail.substringBefore(".") }
                // Para filtrar, el consejo agrupado conserva todos los modos y tipos de sus alternativas.
                val merged = top.copy(modes = list.flatMap { it.modes }.toSet(), types = list.flatMap { it.types }.toSet())
                listOf(if (others.isEmpty()) merged else merged.copy(detail = top.detail + label + text))
            }
        }.sortedByDescending { it.priority }
    }

    /** ¿Vale la pena invertir en estos IV para la liga? */
    private fun ivGoodFor(league: League, c: PvpCandidate): Boolean = when (league) {
        // En Master todo IV decente da >95% de stat product: lo que importa es el ataque y casi-hundo.
        League.MASTER -> c.owned.ivAtk >= 14 && c.owned.ivDef >= 13 && c.owned.ivSta >= 13
        else -> c.iv.rank <= 300 || c.iv.percentOfBest >= 98.0
    }

    // ------------------------------------------------------------------ Qué atrapar

    /** Formas que no se atrapan: se consiguen transformando/fusionando otra especie. */
    private fun formOrigin(s: Species): String? = when {
        s.id.contains("_crowned") -> "${s.name}: se obtiene con un objeto (Espada/Escudo Oxidado) desde ${s.name.substringBefore(" (")}"
        s.id.startsWith("kyurem_") || s.id.startsWith("necrozma_") -> "${s.name}: se obtiene fusionando ${s.name.substringBefore(" (")}"
        s.id.endsWith("_origin") || s.id.endsWith("_therian") || s.id.endsWith("_complete") || s.id.endsWith("_sky") ->
            "${s.name}: cambio de forma de ${s.name.substringBefore(" (")}"
        else -> null
    }

    fun whereToGet(s: Species, inRaidsNow: Set<String>): String = when {
        s.id in inRaidsNow || s.baseId in inRaidsNow -> "En incursiones ahora"
        s.shadow && (s.legendary || s.mythical) -> "Incursiones oscuras o Giovanni"
        s.shadow -> "Derrotando al Team GO Rocket"
        s.mythical -> "Investigación especial / evento (mítico)"
        s.legendary || s.ultraBeast -> "Incursiones de nivel 5 cuando vuelva a rotación"
        else -> "Salvaje, huevos o investigación"
    }

    fun catchAdvice(collection: List<OwnedPokemon>, inRaidsNow: Set<String> = emptySet()): List<Advice> {
        val ownedBattleIds = collection.map { it.battleId }.toSet()
        val ownedFamilies = collection.mapNotNull { p -> idx[p.speciesId]?.family?.let { it to p } }

        class Reasons(val species: Species) {
            val pve = LinkedHashMap<String, Int>()
            var pveNeedsElite = false
            var viaMega = false
            val pvp = mutableListOf<String>()
            val max = LinkedHashMap<String, Int>()
            val maxTypes = LinkedHashSet<String>()
            /** Formas especiales (fusión/objeto/cambio de forma) por las que se lo recomienda. */
            val origins = LinkedHashSet<String>()
            var priority = 0.0
        }
        val byId = LinkedHashMap<String, Reasons>()

        // Si es una forma que no se atrapa, la recomendación va a la forma base (recordando cuál era).
        fun catchable(s: Species): Species = when {
            s.mega -> idx[s.baseId] ?: s
            formOrigin(s) != null -> idx.byDex[s.dex]?.firstOrNull { formOrigin(it) == null } ?: s
            else -> s
        }
        fun reasons(original: Species): Reasons {
            val r = byId.getOrPut(catchable(original).id) { Reasons(catchable(original)) }
            formOrigin(original)?.let { r.origins += it }
            return r
        }

        for (type in Types.ALL) {
            Interrupt.check()
            topAttackers(type).distinctBy { it.species.id }.take(6).forEachIndexed { i, r ->
                val s = r.species
                val have = if (s.mega) collection.any { it.speciesId == s.baseId && !it.shadow } else s.id in ownedBattleIds
                if (have) return@forEachIndexed
                val rs = reasons(s)
                if (type !in rs.pve) rs.pve[type] = i + 1
                if (r.usesElite) rs.pveNeedsElite = true
                if (s.mega) rs.viaMega = true
                rs.priority = maxOf(rs.priority, 70.0 - i * 5) + 3
            }
        }
        for (league in League.entries) {
            // Una sola vez por liga, sobre la colección completa (memoizado): no se desplaza la memo.
            val goodIds = pvpCandidates(league, collection).filter { ivGoodFor(league, it) }.map { it.species.id }.toSet()
            idx.pvpRanking(league).take(20).forEachIndexed { i, r ->
                val s = idx[r.id] ?: return@forEachIndexed
                if (s.id in goodIds) return@forEachIndexed
                val best = PokeMath.pvpTable(s, league.cap, maxLevel, idx, limit = 1).firstOrNull()
                val rs = reasons(s)
                rs.pvp += "#${i + 1} ${league.label}" + (if (formOrigin(s) != null) " como ${s.name}" else "") +
                    (best?.let { " (IV ideales ${it.ivs})" } ?: "")
                rs.priority = maxOf(rs.priority, 60.0 - i * 2) + 3
            }
        }
        for (type in Types.ALL) {
            maxTop(type).take(3).forEachIndexed { i, r ->
                val have = collection.any { it.speciesId == r.species.id && (it.dynamax || it.gigantamax) && it.gigantamax == r.gigantamax }
                if (have) return@forEachIndexed
                val rs = reasons(r.species)
                val label = Types.es(type) + if (r.gigantamax) " G-Max" else ""
                if (label !in rs.max) rs.max[label] = i + 1
                rs.maxTypes += type
                rs.priority = maxOf(rs.priority, 55.0 - i * 5) + 3
            }
        }

        // Los míticos no se pueden salir a buscar (investigaciones/eventos): solo si están en incursiones.
        byId.values.removeAll { it.species.mythical && it.species.id !in inRaidsNow && it.species.baseId !in inRaidsNow }

        return byId.values.map { rs ->
            val s = rs.species
            // ¿Ya tenés uno de la familia para evolucionar? (mismo estado shadow). Evolucionar uno común
            // no da un Dinamax/G-Max: si el único motivo es Dinamax, hace falta que el tuyo ya lo sea.
            val maxOnly = rs.pve.isEmpty() && rs.pvp.isEmpty()
            val preEvo = ownedFamilies.firstOrNull { (fam, p) ->
                fam == s.family && p.speciesId != s.baseId && p.shadow == s.shadow &&
                    (!maxOnly || p.dynamax || p.gigantamax) &&
                    idx[p.speciesId]?.let { b -> idx.descendants(b).any { it.baseId == s.baseId } } == true
            }?.second
            val lines = buildList {
                if (rs.pve.isNotEmpty()) add(
                    "Atacante: " + rs.pve.entries.joinToString(", ") { "${Types.es(it.key)} #${it.value}" } +
                        (if (rs.viaMega) " (como Mega)" else "") +
                        (if (rs.pveNeedsElite) ". Sus mejores ataques requieren MT Élite" else "")
                )
                if (rs.pvp.isNotEmpty()) add("PvP: " + rs.pvp.joinToString(", "))
                if (rs.max.isNotEmpty()) add("Dinamax: " + rs.max.entries.joinToString(", ") { "${it.key} #${it.value}" })
                rs.origins.forEach { add(it) }
                if (preEvo != null) add("En la caja hay un ${idx[preEvo.speciesId]?.name ?: preEvo.speciesId} (IV ${preEvo.ivPercent} %): conviene evolucionarlo")
                else add(whereToGet(s, inRaidsNow))
            }
            // Legendarios fuera de rotación y míticos bajan de prioridad: no se pueden buscar a voluntad.
            val unavailable = (s.legendary || s.mythical || s.ultraBeast) && s.id !in inRaidsNow && s.baseId !in inRaidsNow
            val priority = rs.priority * (if (unavailable) 0.6 else 1.0) + (if (preEvo != null) 10 else 0)
            // Cada renglón termina en un solo signo de puntuación.
            val text = lines.joinToString("\n") { l -> l.trimEnd('.', ' ').let { if (it.endsWith("!")) it else "$it." } }
            val modes = buildSet {
                if (rs.pve.isNotEmpty()) add(GameMode.PVE)
                if (rs.pvp.isNotEmpty()) add(GameMode.PVP)
                if (rs.max.isNotEmpty()) add(GameMode.MAX)
            }
            val types = rs.pve.keys + rs.maxTypes + (if (rs.pvp.isNotEmpty()) s.types else emptyList())
            Advice(if (preEvo != null) AdviceKind.EVOLVE else AdviceKind.CATCH, s, preEvo, s.name, text, priority,
                modes = modes, types = types.toSet())
        }.sortedByDescending { it.priority }
    }

    /**
     * ¿En qué rankings destaca lo que se obtiene de este jefe de incursión (él o sus evoluciones)?
     * Para jefes Mega se considera también la forma Mega (la que se usa) y la especie base (la que se atrapa).
     * Devuelve textos cortos ordenados por relevancia.
     */
    fun raidHighlights(boss: Species, maxItems: Int = 3): List<String> {
        val caught = if (boss.mega) idx[boss.baseId] ?: boss else boss
        val family = (listOf(caught) + idx.descendants(idx[caught.baseId] ?: caught).map { idx.sameVariant(it, caught.shadow) })
            .distinctBy { it.id } + (if (boss.mega) listOf(boss) else emptyList())
        data class H(val text: String, val weight: Int)
        val out = mutableListOf<H>()
        // El propio jefe (o lo que se atrapa de él) no necesita aclaración: "(como Charizard (Mega X))" sobraba.
        fun who(s: Species) = if (s.id == caught.id || s.id == boss.id) "" else " (como ${s.name})"
        for (s in family) {
            for (type in Types.ALL) {
                val rank = topAttackers(type).distinctBy { it.species.id }.indexOfFirst { it.species.id == s.id } + 1
                if (rank in 1..10) out += H("Top $rank atacante ${Types.es(type)}${who(s)}", 100 - rank * 5)
                // Sin rankings Dinamax: lo que se atrapa en una incursión (normal, mega u oscura) nunca puede
                // Dinamaxizar; los Dinamax solo salen de las batallas Max.
            }
            for (league in League.entries) {
                val rank = idx.pvpRank(league, s.id)?.first ?: continue
                if (rank <= 30) out += H("#$rank ${league.label}${who(s)}", 90 - rank * 2)
            }
        }
        return out.sortedByDescending { it.weight }.map { it.text }.distinct().take(maxItems)
    }

    companion object {
        fun fmt(level: Double) = if (level % 1.0 == 0.0) level.toInt().toString() else level.toString()
    }
}
