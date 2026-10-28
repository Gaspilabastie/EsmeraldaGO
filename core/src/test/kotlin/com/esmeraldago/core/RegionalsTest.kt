package com.esmeraldago.core

import com.esmeraldago.core.data.Regionals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionalsTest {
    // Mismo formato que la tabla de Bulbapedia (texto de ejemplo, no copiado de la página).
    private val sample = """
        ===Region-exclusive Pokémon===
        Intro.
        {| class="roundy"
        |- style="background-color:#fff"
        ! Region
        ! Pokémon
        ! Notes
        |- style=background:#CCC
        | colspan="3" | '''Set 1'''
        |- style=background:#FFF
        | Mexico, Central and South America{{tt|*|South of 29° N}}
        ! {{MSP/GO|0214|Heracross}}
        |
        * A note.
        |- style=background:#fff
        | Western Hemisphere
        ! {{MSP/GO|0128PA|Tauros}}{{MSP/GO|0422|Shellos|West Sea}}
        | rowspan="2" |
        * Notes.
        ** More.
        |- style=background:#FFF
        | style="{{roundybl|5px}}" | United Kingdom
        ! {{MSP/GO|0874|Stonjourner}}
        | style="{{roundybr|5px}}" |
        |}

        ====Biome-dependent Pokémon====
        {| class="roundy"
        |- style=background:#FFF
        | Beach
        ! {{MSP/GO|0960|Wiglett}}{{MSP/GO|0961|Wugtrio}}
        |
        |}
    """.trimIndent()

    @Test
    fun parsesRowsFormsNotesAndBiomes() {
        val g = Regionals.parse(sample)
        assertEquals(listOf("Mexico, Central and South America", "Western Hemisphere", "United Kingdom", "Beach"), g.map { it.region })
        assertEquals("South of 29° N", g[0].note)
        assertEquals(214, g[0].members.single().dex)
        val west = g[1].members
        assertEquals("PA", west[0].code)
        assertEquals("West Sea", west[1].form)
        assertFalse(g[2].biome)
        assertTrue(g[3].biome)
        assertEquals(2, g[3].members.size)
    }

    /** Con la respuesta real de la API guardada localmente (REGIONALS_FILE); diagnóstico, no se sube al repo. */
    @Test
    fun parsesRealPage() {
        val f = System.getenv("REGIONALS_FILE")?.let { java.io.File(it) }
        org.junit.jupiter.api.Assumptions.assumeTrue(f != null && f.exists(), "REGIONALS_FILE no configurado")
        val g = Regionals.parse(Regionals.wikitextOf(f!!.readText()))
        g.forEach { println("${if (it.biome) "[bioma] " else ""}${it.region} (${it.note}) → ${it.members.joinToString { m -> "${m.dex}${m.code} ${m.name}${m.form?.let { x -> " ($x)" } ?: ""}" }}") }
        assertTrue(g.size > 20)
    }
}
