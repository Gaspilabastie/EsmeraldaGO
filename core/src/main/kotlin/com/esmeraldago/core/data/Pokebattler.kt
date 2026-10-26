package com.esmeraldago.core.data

import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.Species
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Un counter según Pokébattler: su mejor par de ataques y los entrenadores que hacen falta (menos es mejor). */
@Serializable
data class PbCounter(val speciesId: String, val fast: String, val charged: String, val estimator: Double)

/** Counter ya resuelto contra los datos de la app. */
data class PbResolved(val species: Species, val fast: Move, val charged: Move, val estimator: Double)

/**
 * API pública de Pokébattler (https://www.pokebattler.com/developers): counters de incursión simulados, con el
 * "estimator" (entrenadores necesarios con equipos de ese counter) como métrica principal. Sin autenticación;
 * pide citar la fuente, cachear y mandar un User-Agent descriptivo.
 */
object Pokebattler {
    const val USER_AGENT = "EsmeraldaGO/1.0 (app privada; github.com/Gaspilabastie/EsmeraldaGO)"

    /** URLs a probar en orden: Pokébattler omite la forma por defecto (KELDEO, LANDORUS_SHADOW_FORM). */
    fun urls(boss: Species, tier: RaidTier, level: Int): List<String> =
        listOf(bossId(boss), bossId(boss, withForm = false)).distinct().map { url(it, tier, level) }

    fun url(boss: Species, tier: RaidTier, level: Int): String = url(bossId(boss), tier, level)

    private fun url(bossId: String, tier: RaidTier, level: Int): String =
        "https://fight.pokebattler.com/raids/defenders/$bossId/levels/${raidLevel(tier)}" +
            "/attackers/levels/$level/strategies/CINEMATIC_ATTACK_WHEN_POSSIBLE/DEFENSE_RANDOM_MC" +
            "?sort=ESTIMATOR&weatherCondition=NO_WEATHER&dodgeStrategy=DODGE_REACTION_TIME&aggregation=AVERAGE"

    fun raidLevel(t: RaidTier) = when (t) {
        RaidTier.T1 -> "RAID_LEVEL_1"; RaidTier.T3 -> "RAID_LEVEL_3"; RaidTier.T5 -> "RAID_LEVEL_5"
        RaidTier.MEGA -> "RAID_LEVEL_MEGA"; RaidTier.MEGA_LEGENDARY -> "RAID_LEVEL_MEGA_5"
        RaidTier.SHADOW_T1 -> "RAID_LEVEL_1_SHADOW"; RaidTier.SHADOW_T3 -> "RAID_LEVEL_3_SHADOW"
        RaidTier.SHADOW_T5 -> "RAID_LEVEL_5_SHADOW"
    }

    /** "giratina_origin" → GIRATINA_ORIGIN_FORM, "marowak_alolan" → MAROWAK_ALOLA_FORM, "mewtwo_shadow" → MEWTWO_SHADOW_FORM. */
    fun bossId(s: Species, withForm: Boolean = true): String {
        val base = if (s.shadow) s.id.removeSuffix("_shadow") else s.id
        if (s.mega) return base.uppercase()
        val formWord = s.name.substringAfter('(', "").substringBefore(')').takeIf { it.isNotEmpty() && it != "Shadow" }
        var id = if (!withForm && formWord != null) base.removeSuffix("_" + formWord.lowercase().replace(' ', '_')).uppercase() else base.uppercase()
        REGIONAL.forEach { (ours, theirs) -> if (id.endsWith(ours)) id = id.removeSuffix(ours) + theirs }
        // El nombre dice si es una forma ("Giratina (Origin)"); el id no, por Mr. Mime, Ho-Oh, Tapu Koko…
        val hasForm = withForm && formWord != null
        return when {
            s.shadow -> "${id}_SHADOW_FORM"
            hasForm -> "${id}_FORM"
            else -> id
        }
    }

    /** Ids de Pokébattler (MAROWAK_ALOLA_FORM, DARKRAI_SHADOW_FORM, MEWTWO_MEGA_Y) a especies de la app. */
    fun resolve(pbId: String, idx: GameIndex): Species? {
        var id = pbId.removeSuffix("_FORM")
        val shadow = id.endsWith("_SHADOW")
        id = id.removeSuffix("_SHADOW")
        REGIONAL.forEach { (ours, theirs) -> if (id.endsWith(theirs)) id = id.removeSuffix(theirs) + ours }
        val lower = id.lowercase()
        // Forma por defecto omitida (KELDEO → keldeo_ordinary, DARMANITAN → darmanitan_standard): por nombre o prefijo.
        val candidates = sequence {
            idx[lower]?.let { yield(it) }
            if (lower.endsWith("_normal")) idx[lower.substringBeforeLast('_')]?.let { yield(it) }
            idx.findByName(lower.replace('_', ' '))?.let { yield(it) }
            yieldAll(idx.data.species.asSequence().filter { c ->
                c.id.startsWith(lower + "_") && !c.shadow && !c.mega && c.released && REGIONAL.none { c.id.uppercase().contains(it.first) }
            })
        }
        return if (shadow) candidates.firstNotNullOfOrNull { idx.shadowOf(it) } else candidates.firstOrNull()
    }

    fun move(pbMove: String, idx: GameIndex): Move? {
        val id = pbMove.removeSuffix("_FAST")
        return idx.move(id) ?: idx.move(id.removeSuffix("_PLUS"))
    }

    /** Lee la respuesta de /raids/defenders: por counter, su mejor par de ataques (menor estimator). */
    fun parse(text: String): List<PbCounter> {
        val root = GameDataSources.json.parseToJsonElement(text).jsonObject
        val boss = root["attackers"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val defenders = boss["randomMove"]?.jsonObject?.get("defenders")?.jsonArray ?: return emptyList()
        return defenders.mapNotNull { d ->
            val o = d.jsonObject
            val id = o.str("pokemonId") ?: return@mapNotNull null
            val best = o["byMove"]?.jsonArray?.map { it.jsonObject }
                ?.minByOrNull { it["result"]?.jsonObject?.num("estimator") ?: Double.MAX_VALUE } ?: return@mapNotNull null
            val est = best["result"]?.jsonObject?.num("estimator") ?: return@mapNotNull null
            PbCounter(id, best.str("move1") ?: return@mapNotNull null, best.str("move2") ?: return@mapNotNull null, est)
        }.sortedBy { it.estimator }
    }

    fun resolveAll(list: List<PbCounter>, idx: GameIndex): List<PbResolved> = list.mapNotNull { c ->
        val s = resolve(c.speciesId, idx) ?: return@mapNotNull null
        PbResolved(s, move(c.fast, idx) ?: return@mapNotNull null, move(c.charged, idx) ?: return@mapNotNull null, c.estimator)
    }

    private val REGIONAL = listOf("_ALOLAN" to "_ALOLA", "_GALARIAN" to "_GALARIAN", "_HISUIAN" to "_HISUIAN", "_PALDEAN" to "_PALDEA")

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull
}
