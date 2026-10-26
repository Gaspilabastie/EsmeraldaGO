package com.esmeraldago.core

import com.esmeraldago.core.calc.RaidTier
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.data.Pokebattler
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Traducción de ids entre la app y Pokébattler. Con PB=1 consulta en vivo los jefes actuales (pocas consultas,
 * una por jefe) y verifica que cada uno exista allá y que sus counters se resuelvan.
 */
class PokebattlerTest {
    private val idx by lazy {
        val f = listOf("../app/src/main/assets/gamedata.json", "app/src/main/assets/gamedata.json").map(::File).first { it.exists() }
        GameIndex(GameDataSources.decode(f.readText()))
    }

    @Test
    fun bossIds() {
        assertEquals("MEWTWO", Pokebattler.bossId(idx["mewtwo"]!!))
        assertEquals("MEWTWO_SHADOW_FORM", Pokebattler.bossId(idx["mewtwo_shadow"]!!))
        assertEquals("GIRATINA_ORIGIN_FORM", Pokebattler.bossId(idx["giratina_origin"]!!))
        assertEquals("MAROWAK_ALOLA_FORM", Pokebattler.bossId(idx["marowak_alolan"]!!))
        assertEquals("CHARIZARD_MEGA_Y", Pokebattler.bossId(idx["charizard_mega_y"]!!))
        assertEquals("HO_OH", Pokebattler.bossId(idx["ho_oh"]!!))
    }

    @Test
    fun resolveIds() {
        assertEquals("necrozma_dawn_wings", Pokebattler.resolve("NECROZMA_DAWN_WINGS_FORM", idx)?.id)
        assertEquals("darkrai_shadow", Pokebattler.resolve("DARKRAI_SHADOW_FORM", idx)?.id)
        assertEquals("mewtwo_mega_y", Pokebattler.resolve("MEWTWO_MEGA_Y", idx)?.id)
        assertEquals("houndoom_mega", Pokebattler.resolve("HOUNDOOM_MEGA", idx)?.id)
        assertEquals("keldeo_ordinary", Pokebattler.resolve("KELDEO", idx)?.id)
        assertEquals("darmanitan_standard_shadow", Pokebattler.resolve("DARMANITAN_SHADOW_FORM", idx)?.id)
        assertEquals("SNARL", Pokebattler.move("SNARL_FAST", idx)?.id)
    }

    @Test
    fun liveCurrentBosses() {
        if (System.getenv("PB") != "1") return
        val raids = GameDataSources.open(GameDataSources.CURRENT_RAIDS).use { it.reader().readText() }
        val current = GameDataSources.json.parseToJsonElement(raids).jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["tier"]!!.jsonPrimitive.content
        }
        // Más formas difíciles de nombrar, aunque no estén en rotación.
        val extra = listOf("Keldeo" to "5-Star Raids", "Landorus (Incarnate)" to "5-Star Raids", "Giratina (Altered)" to "5-Star Raids",
            "Kyurem (Black)" to "5-Star Raids", "Necrozma (Dusk Mane)" to "5-Star Raids", "Darmanitan (Galarian Standard)" to "3-Star Raids",
            "Shadow Raikou" to "Shadow 5-Star Raids", "Mega Charizard Y" to "Mega Raids", "Ho-Oh" to "5-Star Raids")
        val problems = mutableListOf<String>()
        (current + extra).forEach { (name, tierLabel) ->
            val shadow = name.startsWith("Shadow ") || tierLabel.contains("Shadow", true)
            val found = idx.findByName(name.removePrefix("Shadow ").trim()) ?: run { problems += "$name: no está en los datos"; return@forEach }
            val s = if (shadow) idx.shadowOf(found) ?: found else found
            val stars = Regex("""(\d)""").find(tierLabel)?.value?.toIntOrNull() ?: 5
            val tier = when {
                shadow -> when (stars) { 1 -> RaidTier.SHADOW_T1; 3 -> RaidTier.SHADOW_T3; else -> RaidTier.SHADOW_T5 }
                tierLabel.contains("Mega", true) -> if (s.legendary || s.mythical) RaidTier.MEGA_LEGENDARY else RaidTier.MEGA
                stars == 1 -> RaidTier.T1; stars == 3 -> RaidTier.T3; else -> RaidTier.T5
            }
            val text = Pokebattler.urls(s, tier, 40).firstNotNullOfOrNull { url ->
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", Pokebattler.USER_AGENT)
                conn.readTimeout = 120_000
                if (conn.responseCode == 200) conn.inputStream.reader().readText() else null.also { println("  404 $url") }
            }
            if (text == null) { problems += "$name (${s.id}, ${Pokebattler.raidLevel(tier)}): no existe en Pokébattler"; return@forEach }
            val list = Pokebattler.parse(text)
            // Especies que la app no tiene (p. ej. Overqwil oscuro, que PvPoke no lista) se omiten: solo se reportan.
            val unresolved = list.filter { Pokebattler.resolve(it.speciesId, idx) == null }.map { it.speciesId }
                .filterNot { it.endsWith("_SHADOW_FORM") && idx[it.removeSuffix("_SHADOW_FORM").lowercase()]?.let { b -> idx.shadowOf(b) } == null }
            val badMoves = list.flatMap { listOf(it.fast, it.charged) }.filter { Pokebattler.move(it, idx) == null }.distinct()
            println("$name -> ${Pokebattler.bossId(s)}: ${list.size} counters; top ${list.take(3).joinToString { "${it.speciesId} ${"%.2f".format(it.estimator)}" }}")
            if (unresolved.isNotEmpty()) problems += "$name: sin resolver $unresolved"
            if (badMoves.isNotEmpty()) problems += "$name: ataques sin resolver $badMoves"
            Thread.sleep(1500)
        }
        problems.forEach { println("PROBLEMA: $it") }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
