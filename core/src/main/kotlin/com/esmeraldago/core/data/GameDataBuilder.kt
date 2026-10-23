package com.esmeraldago.core.data

import com.esmeraldago.core.model.GameData
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.PvpRank
import com.esmeraldago.core.model.Species
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.sqrt

/**
 * Combina las fuentes:
 *  - PvPoke gamemaster: especies, movesets (incluye legado/élite), shadow, mega, stats PvP de movimientos.
 *  - PokeMiners game master: stats PvE de movimientos, CPM, Dynamax/Gigamax.
 *  - PvPoke rankings por liga.
 */
class GameDataBuilder {

    private class PveMove(val power: Double, val durationMs: Int, val energyDelta: Int)

    private class MinersData {
        val pveMoves = HashMap<String, PveMove>()
        /** Nombre del movimiento en el GM -> su número (V0013_MOVE_WRAP -> 13), la clave de los textos traducidos. */
        val moveNumbers = HashMap<String, Int>()
        var cpmWhole: List<Double> = emptyList()
        /** (pokemonId, form?) con modo Dynamax normal. */
        val dynamax = HashSet<Pair<String, String?>>()
        /** (pokemonId, form?) -> movimiento VN_BM del G-Max. */
        val gmax = HashMap<Pair<String, String?>, String>()
        /** tipo -> movimiento VN_BM genérico. */
        val typeMaxMove = HashMap<String, String>()
        /** VN_BM_xxx -> (tipo, vfxName). */
        val maxMoves = HashMap<String, Pair<String, String>>()
        /** (pokemonId, form?) habilitados como Gigamax ("allowedSourdoughPokemon"). */
        val allowedGmax = HashSet<Pair<String, String?>>()
    }

    /** Ids de PvPoke cuyo nombre en el Game Master es distinto. */
    private val pveAliases = mapOf(
        "PYRO_BALL" to "PYROBALL",
        "FUTURE_SIGHT" to "FUTURESIGHT",
        "TECHNO_BLAST_DOUSE" to "TECHNO_BLAST_WATER",
    )

    private fun minersKey(id: String) = when {
        id.startsWith("HIDDEN_POWER") -> "HIDDEN_POWER" // el GM tiene un solo Hidden Power
        else -> pveAliases[id] ?: id
    }

    private fun pveFor(id: String, fast: Boolean, miners: MinersData): PveMove? {
        val key = minersKey(id)
        return if (fast) miners.pveMoves["${key}_FAST"] ?: miners.pveMoves[key] else miners.pveMoves[key]
    }

    private fun moveNumber(id: String, fast: Boolean, miners: MinersData): Int? {
        val key = minersKey(id)
        return (if (fast) miners.moveNumbers["${key}_FAST"] else null) ?: miners.moveNumbers[key]
    }

    /** Movimientos aprendibles que quedaron sin datos PvE (se informan al generar). */
    val missingPve = mutableListOf<String>()

    fun build(
        pvpokeGamemaster: InputStream,
        pokeminersGamemaster: InputStream,
        rankings: Map<League, InputStream>,
        sourceVersion: String,
        /** Textos del juego en español (i18n de PokeMiners): clave -> texto. Para leer el cliente en español. */
        spanish: Map<String, String> = emptyMap(),
    ): GameData {
        val miners = parseMiners(pokeminersGamemaster)
        val pvpoke = pvpokeGamemaster.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

        val moves = parseMoves(pvpoke["moves"].asJsonArray, miners, spanish)
        val species = parseSpecies(pvpoke["pokemon"].asJsonArray, miners, spanish)
        val pvp = rankings.mapKeys { it.key.key }.mapValues { (_, s) -> parseRankings(s) }

        return GameData(
            generatedAt = System.currentTimeMillis(),
            sourceVersion = sourceVersion,
            cpm = halfLevelCpm(miners.cpmWhole),
            species = species,
            moves = moves,
            maxMoveNames = miners.typeMaxMove.mapNotNull { (type, vn) ->
                miners.maxMoves[vn]?.let { type to prettyMaxName(it.second) }
            }.toMap(),
            pvp = pvp,
        )
    }

    // ---------------------------------------------------------------- PokeMiners

    private val moveTemplate = Regex("^V(\\d{4})_MOVE_(.+)$")
    private val maxMoveTemplate = Regex("^VN_BM_\\d+$")

    private fun parseMiners(input: InputStream): MinersData {
        val out = MinersData()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                val obj = JsonParser.parseReader(reader).asJsonObject
                val tid = obj["templateId"]?.asString ?: continue
                val data = obj["data"]?.asJsonObject ?: continue
                when {
                    moveTemplate.matches(tid) -> data.obj("moveSettings")?.let { ms ->
                        val match = moveTemplate.find(tid)!!
                        val name = match.groupValues[2]
                        out.moveNumbers[name] = match.groupValues[1].toInt()
                        out.pveMoves[name] = PveMove(
                            power = ms.double("power") ?: 0.0,
                            durationMs = ms.int("durationMs") ?: 1000,
                            energyDelta = ms.int("energyDelta") ?: 0,
                        )
                    }
                    maxMoveTemplate.matches(tid) -> data.obj("moveSettings")?.let { ms ->
                        val type = ms.str("pokemonType")?.removePrefix("POKEMON_TYPE_")?.lowercase() ?: return@let
                        out.maxMoves[tid] = type to (ms.str("vfxName") ?: tid)
                    }
                    tid == "PLAYER_LEVEL_SETTINGS" ->
                        out.cpmWhole = data.obj("playerLevel")!!["cpMultiplier"].asJsonArray.map { it.asDouble }
                    tid == "BREAD_MOVE_MAPPING_SETTINGS" ->
                        data.obj("breadMoveMappings")?.get("mappings")?.asJsonArray?.forEach {
                            val m = it.asJsonObject
                            out.typeMaxMove[m.str("type")!!.removePrefix("POKEMON_TYPE_").lowercase()] = m.str("move")!!
                        }
                    tid == "SOURDOUGH_MOVE_MAPPING_SETTINGS" ->
                        data.obj("sourdoughMoveMappingSettings")?.get("mappings")?.asJsonArray?.forEach {
                            val m = it.asJsonObject
                            out.gmax[m.str("pokemonId")!! to m.str("form")] = m.str("move")!!
                        }
                    tid.startsWith("BREAD_") -> collectBreadModes(data, out)
                }
            }
        }
        return out
    }

    /** Recorre cualquier plantilla BREAD_* y junta las especies/formas con modo Dynamax. */
    private fun collectBreadModes(el: JsonElement, out: MinersData) {
        when {
            el.isJsonArray -> el.asJsonArray.forEach { collectBreadModes(it, out) }
            el.isJsonObject -> {
                val o = el.asJsonObject
                val pid = o.str("pokemonId")
                if (pid != null) {
                    o["pokemonFormData"]?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { fd ->
                        val f = fd.asJsonObject
                        val modes = f["visualData"]?.asJsonArray?.mapNotNull { it.asJsonObject.str("breadMode") } ?: emptyList()
                        if ("BREAD_MODE" in modes) out.dynamax += pid to f.str("pokemonForm")?.ifBlank { null }
                    }
                    val mode = o.str("breadMode")
                    val target = when {
                        mode == "BREAD_MODE" -> out.dynamax
                        // allowedSourdoughPokemon: G-Max habilitados de verdad (hay variantes BREAD_DOUGH_MODE_2, ej. Urshifu RS)
                        mode?.startsWith("BREAD_DOUGH_MODE") == true -> out.allowedGmax
                        else -> null
                    }
                    if (target != null) {
                        val forms = o["form"]
                        when {
                            forms == null -> target += pid to null
                            forms.isJsonArray -> forms.asJsonArray.forEach { target += pid to it.asString }
                            else -> target += pid to forms.asString
                        }
                    }
                }
                o.entrySet().forEach { (_, v) -> if (v.isJsonObject || v.isJsonArray) collectBreadModes(v, out) }
            }
        }
    }

    // ---------------------------------------------------------------- PvPoke

    private fun parseMoves(arr: JsonArray, miners: MinersData, spanish: Map<String, String>): List<Move> = arr.mapNotNull { el ->
        val m = el.asJsonObject
        val id = m.str("moveId") ?: return@mapNotNull null
        val pvpEnergyCost = m.int("energy") ?: 0
        val fast = pvpEnergyCost == 0
        val pve = pveFor(id, fast, miners)
        val pvpPower = m.double("power") ?: 0.0
        val turns = m.int("turns") ?: ((m.int("cooldown") ?: 500) / 500)
        if (pve == null && m["isMegaMove"]?.asBoolean != true) missingPve += id
        Move(
            id = id,
            name = m.str("name") ?: id,
            type = m.str("type") ?: "normal",
            fast = fast,
            power = pve?.power ?: pvpPower,
            energy = pve?.energyDelta?.let { kotlin.math.abs(it) } ?: if (fast) (m.int("energyGain") ?: 0) else pvpEnergyCost,
            // Sin dato PvE: aproximar con los turnos PvP (500 ms c/u) en vez de un valor fijo.
            durationMs = pve?.durationMs ?: if (fast) turns * 500 else 3000,
            pvpPower = pvpPower,
            pvpEnergy = if (fast) (m.int("energyGain") ?: 0) else pvpEnergyCost,
            pvpTurns = turns,
            // Las variantes "_PLUS" de PvPoke son el mismo ataque con otra potencia: mismo nombre en el juego.
            nameEs = (moveNumber(id, fast, miners) ?: moveNumber(id.removeSuffix("_PLUS"), fast, miners))
                ?.let { spanish["move_name_%04d".format(it)] },
        )
    }

    private fun parseSpecies(arr: JsonArray, miners: MinersData, spanish: Map<String, String>): List<Species> {
        val raw = arr.map { it.asJsonObject }
        val ids = raw.mapNotNull { it.str("speciesId") }.toHashSet()

        // Resolver los ids de PokeMiners (VENUSAUR / VENUSAUR_NORMAL / MAROWAK_ALOLA) a ids de PvPoke.
        fun resolve(pokemonId: String, form: String?): String? {
            val base = pokemonId.lowercase()
            if (form == null || form == "FORM_UNSET" || form.endsWith("_NORMAL")) return base.takeIf { it in ids }
            var f = form.lowercase()
            listOf("_alola" to "_alolan", "_galar" to "_galarian", "_hisui" to "_hisuian", "_paldea" to "_paldean")
                .forEach { (a, b) -> if (f.endsWith(a)) f = f.removeSuffix(a) + b }
            return when {
                f in ids -> f
                base in ids -> base
                else -> null
            }
        }

        val dynamaxIds = miners.dynamax.mapNotNull { (p, f) -> resolve(p, f) }.toHashSet()
        val gmaxIds = miners.gmax.mapNotNull { (key, vn) -> resolve(key.first, key.second)?.let { it to vn } }.toMap()
        val allowedGmaxIds = miners.allowedGmax.mapNotNull { (p, f) -> resolve(p, f) }.toHashSet()

        return raw.mapNotNull { p ->
            val id = p.str("speciesId") ?: return@mapNotNull null
            val tags = p["tags"]?.asJsonArray?.map { it.asString }.orEmpty()
            val stats = p.obj("baseStats") ?: return@mapNotNull null
            val shadow = "shadow" in tags || id.endsWith("_shadow")
            val mega = "mega" in tags || id.contains("_mega") || id.contains("_primal")
            val baseId = when {
                shadow -> id.removeSuffix("_shadow")
                mega -> id.replace(Regex("_(mega|primal).*$"), "")
                else -> id
            }
            val family = p.obj("family")
            val battleForm = !shadow && !mega && !id.endsWith("_eternamax")
            // SOURDOUGH también mapea movimientos Max exclusivos (Behemoth Blade, Dynamax Cannon...): solo "gmax_*" es Gigamax.
            val mappedVn = if (battleForm) gmaxIds[id] else null
            val isGmaxMove = mappedVn?.let { miners.maxMoves[it]?.second?.startsWith("gmax") } == true
            // G-Max solo si además está habilitado (allowedSourdoughPokemon); el resto son Max Attacks exclusivos.
            val gmaxVn = mappedVn?.takeIf { isGmaxMove && id in allowedGmaxIds }
            val specialVn = mappedVn?.takeIf { !isGmaxMove }
            val gmaxInfo = gmaxVn?.let { miners.maxMoves[it] }
            val specialInfo = specialVn?.let { miners.maxMoves[it] }
            Species(
                id = id,
                dex = p.int("dex") ?: 0,
                name = p.str("speciesName") ?: id,
                baseId = baseId,
                atk = stats.int("atk") ?: 0,
                def = stats.int("def") ?: 0,
                sta = stats.int("hp") ?: 0,
                types = p["types"].asJsonArray.map { it.asString }.filter { it != "none" },
                fast = p.strList("fastMoves"),
                charged = p.strList("chargedMoves"),
                elite = p.strList("eliteMoves"),
                legacy = p.strList("legacyMoves"),
                shadow = shadow,
                mega = mega,
                shadowEligible = "shadoweligible" in tags,
                legendary = "legendary" in tags,
                mythical = "mythical" in tags,
                ultraBeast = "ultrabeast" in tags,
                // dynamax = existe la variante Dynamax "común" (la Gigamax es otra captura distinta)
                dynamax = battleForm && (id in dynamaxIds || specialVn != null),
                gigantamax = gmaxVn != null,
                gmaxMove = gmaxInfo?.let { prettyMaxName(it.second) },
                gmaxType = gmaxInfo?.first,
                specialMaxMove = specialInfo?.let { prettyMaxName(it.second) },
                specialMaxType = specialInfo?.first,
                family = family?.str("id"),
                parent = family?.str("parent"),
                evolutions = family?.strList("evolutions").orEmpty(),
                released = p["released"]?.asBoolean ?: true,
                buddyKm = p.int("buddyDistance") ?: 3,
                // Solo si difiere (Paradox, Código Cero…): casi todos se llaman igual en español.
                nameEs = p.int("dex")?.let { spanish["pokemon_name_%04d".format(it)] }
                    ?.takeIf { it != (p.str("speciesName") ?: id).substringBefore(" (") },
            )
        }
    }

    private fun parseRankings(input: InputStream): List<PvpRank> =
        input.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonArray }.map { el ->
            val r = el.asJsonObject
            PvpRank(
                id = r.str("speciesId")!!,
                score = r.double("score") ?: 0.0,
                moveset = r.strList("moveset"),
                wins = r["matchups"]?.asJsonArray?.mapNotNull { it.asJsonObject.str("opponent") }.orEmpty(),
                losses = r["counters"]?.asJsonArray?.mapNotNull { it.asJsonObject.str("opponent") }.orEmpty(),
                fastByUse = usage(r.obj("moves"), "fastMoves"),
                chargedByUse = usage(r.obj("moves"), "chargedMoves"),
            )
        }

    /** Ids de movimientos ordenados por "uses" descendente. */
    private fun usage(moves: JsonObject?, key: String): List<String> =
        moves?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.asJsonObject.let { o -> o.str("moveId")?.let { it to (o.double("uses") ?: 0.0) } } }
            ?.sortedByDescending { it.second }?.map { it.first }.orEmpty()

    // ---------------------------------------------------------------- helpers

    companion object {
        const val MAX_LEVEL = 51.0

        /** CPM para niveles 1.0..51.0 cada 0.5, a partir de la tabla de niveles enteros. */
        fun halfLevelCpm(whole: List<Double>): List<Double> {
            val out = ArrayList<Double>()
            val maxWhole = MAX_LEVEL.toInt()
            for (lvl in 1..maxWhole) {
                val c = whole[lvl - 1]
                out += c
                if (lvl < maxWhole) {
                    val n = whole[lvl]
                    out += sqrt((c * c + n * n) / 2)
                }
            }
            return out
        }

        /** "max_flare" -> "Max Flare", "gmax_wildfire" -> "G-Max Wildfire". */
        fun prettyMaxName(vfx: String): String {
            val words = vfx.lowercase().split('_').filter { it.isNotBlank() }
            if (words.isEmpty()) return vfx
            val head = when (words[0]) {
                "gmax" -> "G-Max"
                "max" -> "Max"
                else -> words[0].replaceFirstChar { it.uppercase() }
            }
            return (listOf(head) + words.drop(1).map { it.replaceFirstChar { c -> c.uppercase() } }).joinToString(" ")
        }

        private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
        private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive }?.asDouble?.toInt()
        private fun JsonObject.double(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive }?.asDouble
        private fun JsonObject.strList(k: String): List<String> =
            get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
    }
}
