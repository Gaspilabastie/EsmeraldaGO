package com.esmeraldago.core

import com.esmeraldago.core.model.GameData
import com.esmeraldago.core.model.League
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.PvpRank
import com.esmeraldago.core.model.Species
import kotlin.math.roundToInt

/** Índices de búsqueda sobre [GameData]. Se construye una vez al cargar los datos. */
class GameIndex(
    val data: GameData,
    /** Incluir las especies que todavía no salieron en Pokémon GO (opción "Mostrar no lanzados"). */
    val includeUnreleased: Boolean = false,
) {
    val species: Map<String, Species> = data.species.associateBy { it.id }
    val moves: Map<String, Move> = data.moves.associateBy { it.id }

    private fun visible(s: Species) = includeUnreleased || s.released

    /** Especies "elegibles" para rankings: liberadas (o todas, con la opción) y sin duplicados raros. */
    val released: List<Species> = data.species.filter { visible(it) && it.atk > 0 }

    private val pvpIndex: Map<String, Map<String, Pair<Int, PvpRank>>> =
        data.pvp.mapValues { (_, list) -> list.mapIndexed { i, r -> r.id to ((i + 1) to r) }.toMap() }

    private val byFamily: Map<String, List<Species>> =
        data.species.filter { it.family != null }.groupBy { it.family!! }

    fun cpm(level: Double): Double =
        data.cpm[((level - 1) * 2).roundToInt().coerceIn(0, data.cpm.lastIndex)]

    val maxLevel: Double get() = 1.0 + (data.cpm.size - 1) / 2.0

    operator fun get(id: String): Species? = species[id]
    fun move(id: String?): Move? = id?.let { moves[it] }

    fun pvpRanking(league: League): List<PvpRank> = data.pvp[league.key].orEmpty()

    /** (puesto 1-based, entrada) en el ranking PvP de la liga, o null si no figura. */
    fun pvpRank(league: League, id: String): Pair<Int, PvpRank>? = pvpIndex[league.key]?.get(id)

    fun shadowOf(s: Species): Species? = species["${s.baseId}_shadow"]?.takeIf { !s.mega }
    fun megasOf(s: Species): List<Species> = data.species.filter { it.mega && it.baseId == s.baseId }

    fun family(s: Species): List<Species> = s.family?.let { byFamily[it] }.orEmpty()

    /** Todas las evoluciones posteriores (directas e indirectas), sin shadow/mega. */
    fun descendants(s: Species): List<Species> {
        val out = mutableListOf<Species>()
        val queue = ArrayDeque(s.evolutions)
        while (queue.isNotEmpty()) {
            val next = species[queue.removeFirst()] ?: continue
            if (out.any { it.id == next.id }) continue
            out += next
            queue.addAll(next.evolutions)
        }
        return out
    }

    /** Variante con el mismo estado (shadow o normal) de otra especie, ej. evolución de un shadow. */
    fun sameVariant(target: Species, shadow: Boolean): Species =
        if (shadow) species["${target.baseId}_shadow"] ?: target else target

    /** Busca una especie por nombre tolerando el orden de palabras ("Mega Charizard X" == "Charizard (Mega X)"). */
    fun findByName(name: String): Species? {
        val key = nameKey(name)
        return nameIndex[key] ?: nameIndex[key.replace(" forme", "").replace(" form", "")] ?: aliasIndex[key]?.firstOrNull()
    }

    /** Formas "jugables" (sin shadow/mega) agrupadas por número de Pokédex. */
    val byDex: Map<Int, List<Species>> = data.species.filter { !it.shadow && !it.mega && visible(it) }.groupBy { it.dex }

    /**
     * Variantes de aspecto -> su forma principal: mismos stats y tipos que la forma principal de su Nº (disfraces
     * de Pikachu, capas de Burmy, unidades de Genesect, Tatsugiri…). En la Pokédex van dentro de la entrada
     * principal, no como entradas aparte. Kyurem Blanco/Negro comparten stats entre ellos pero no con Kyurem:
     * no cuentan. La principal es la que ya salió y tiene el id más corto ("pikachu", "burmy_plant").
     */
    val cosmeticOf: Map<String, String> by lazy {
        fun key(s: Species) = "${s.atk}|${s.def}|${s.sta}|${s.types}"
        byDex.values.map { forms -> forms.filter { it.id !in BATTLE_FORMS && it.id !in ALIAS_FORMS } }.flatMap { forms ->
            val main = forms.minWithOrNull(compareBy<Species>({ !it.released }, { it.id.length }, { it.id })) ?: return@flatMap emptyList()
            forms.filter { it.id != main.id && key(it) == key(main) }.map { it.id to main.id }
        }.toMap()
    }

    /** Las variantes de aspecto de [s] (vacío si no tiene o si ella misma es una variante). */
    fun cosmeticsOf(s: Species): List<Species> = byDex[s.dex].orEmpty().filter { cosmeticOf[it.id] == s.id }

    /**
     * Todas las especies que el juego podría mostrar con este nombre: el juego muestra "Giratina", "Marowak"
     * o "Nidoran" sin la forma, así que se devuelven todas las formas de ese Nº.
     */
    fun findAllByName(name: String): List<Species> {
        val key = nameKey(name)
        val direct = listOfNotNull(nameIndex[key]?.takeIf { !it.shadow && !it.mega }) + aliasIndex[key].orEmpty()
        return direct.flatMap { s -> listOf(s) + byDex[s.dex].orEmpty() }.distinctBy { it.id }
    }

    private val nameIndex: Map<String, Species> by lazy {
        val m = HashMap<String, Species>()
        // Las formas normales tienen prioridad sobre shadow/mega cuando el nombre coincide.
        data.species.sortedBy { if (it.shadow || it.mega) 1 else 0 }.forEach { s ->
            m.putIfAbsent(nameKey(s.name), s)
            m.putIfAbsent(nameKey(s.id.replace('_', ' ')), s)
        }
        m
    }

    /** Nombre visible en el juego (sin la forma entre paréntesis ni símbolos de género) -> especies. */
    private val aliasIndex: Map<String, List<Species>> by lazy {
        data.species.filter { !it.shadow && !it.mega }.flatMap { s ->
            val shown = s.name.substringBefore(" (")
            // "Nidoran Female" / "Nidoran♀" -> el OCR suele leer solo "Nidoran".
            val noGender = shown.replace("♀", "").replace("♂", "").replace(Regex("\\s+(Female|Male)$"), "")
            // + el nombre en el juego en español, cuando difiere ("Colmilargo" = Great Tusk).
            listOfNotNull(nameKey(shown), nameKey(noGender), s.nameEs?.let { nameKey(it.replace("♀", "").replace("♂", "")) })
                .distinct().map { it to s }
        }.groupBy({ it.first }, { it.second })
    }

    // ---------------------------------------------------------------- caché PvP (acotada, por índice)

    private val statProductCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 1200
    }

    /**
     * Stat products ordenados (desc) de los 4096 IV para especie/liga como Float (~16 KB c/u, máx. 1200 ≈ 20 MB).
     * Alcanza para el rank: los stat products vecinos difieren mucho más que la precisión de un Float.
     */
    fun cachedStatProducts(key: String, compute: () -> FloatArray): FloatArray {
        synchronized(statProductCache) { statProductCache[key] }?.let { return it }
        val v = compute()
        synchronized(statProductCache) { statProductCache[key] = v }
        return v
    }

    /** Diagnóstico: cantidad de tablas PvP en caché y vaciado manual. */
    fun statProductCacheSize(): Int = synchronized(statProductCache) { statProductCache.size }
    fun clearStatProductCache() = synchronized(statProductCache) { statProductCache.clear() }

    /**
     * Formas que el Pokémon toma solo durante el combate (no se atrapan así): ver [BATTLE_FORMS]. PvPoke las marca
     * "no lanzadas" (no se pueden elegir), pero si el Pokémon salió, la forma aparece en sus combates: se muestran.
     */
    fun battleFormsOf(s: Species): List<Species> =
        if (!visible(s)) emptyList() else data.species.filter { it.dex == s.dex && it.id in BATTLE_FORMS }

    companion object {
        /**
         * Formas que aparecen solo en combate (PvPoke las lista aparte porque cambian stats o tipo a mitad de la
         * pelea): Aegislash Filo, Morpeko Voraz, Mimikyu destapado, Cramorant tragón, Eiscue sin hielo,
         * Wishiwashi banco, Palafin heroico, Darmanitan Daruma, Meloetta Danza, Minior Meteoro, Cherrim Soleado,
         * Necrozma Ultra y Eternatus Dinamax Infinito (solo jefe).
         */
        val BATTLE_FORMS = setOf(
            "aegislash_blade", "morpeko_hangry", "mimikyu_busted", "cramorant_gulping", "cramorant_gorging",
            "eiscue_noice", "wishiwashi_school", "palafin_hero", "darmanitan_zen", "darmanitan_galarian_zen",
            "meloetta_pirouette", "minior_meteor", "cherrim_sunny", "necrozma_ultra", "eternatus_eternamax",
        )

        /**
         * Entradas duplicadas en PvPoke: la misma forma con otro nombre ("Hoopa" y "Hoopa (Confined)", "Toxtricity"
         * y "Toxtricity (Amped)"). Se ocultan en la Pokédex; los rankings no cambian.
         */
        val ALIAS_FORMS = setOf("hoopa_confined", "eiscue_ice", "wishiwashi_solo", "palafin_zero", "toxtricity_amped", "maushold_family_of_four")

        fun nameKey(name: String): String = name.lowercase()
            .replace("♀", " female").replace("♂", " male")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ').filter { it.isNotBlank() }
            .map { if (it == "alola") "alolan" else if (it == "galar") "galarian" else if (it == "hisui") "hisuian" else it }
            .sorted().joinToString(" ")
    }
}
