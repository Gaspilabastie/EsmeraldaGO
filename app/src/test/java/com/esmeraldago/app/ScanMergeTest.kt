package com.esmeraldago.app

import com.esmeraldago.app.scan.ScanSession.Companion.mergeInto
import com.esmeraldago.core.model.OwnedPokemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanMergeTest {
    private val box = OwnedPokemon(uid = "a", speciesId = "eevee", cp = 1180, hp = 120, level = 30.0,
        ivAtk = 13, ivDef = 14, ivSta = 12, ivExact = true, nickname = "Evi", shiny = true, addedAt = 5)

    @Test
    fun evolutionTakesNewSpeciesAndKeepsIdentity() {
        val scan = box.copy(uid = "", speciesId = "vaporeon", cp = 2510, hp = 231, nickname = null, shiny = false, addedAt = 0)
        val m = mergeInto(box, scan)
        assertEquals("vaporeon", m.speciesId)
        assertEquals(2510, m.cp)
        assertEquals("a", m.uid)
        assertEquals(5L, m.addedAt)
        assertEquals("Evi", m.nickname)
        assertTrue(m.shiny)
    }

    @Test
    fun olderScreenshotDoesNotLowerLevel() {
        val old = box.copy(uid = "", cp = 900, hp = 100, level = 25.0)
        val m = mergeInto(box, old)
        assertEquals(30.0, m.level, 0.0)
        assertEquals(1180, m.cp)
    }

    @Test
    fun estimatedIvsNeverReplaceExactOnes() {
        val guess = box.copy(uid = "", ivAtk = 10, ivDef = 10, ivSta = 10, ivExact = false, level = 31.0, cp = 1200)
        val m = mergeInto(box, guess)
        assertEquals(13, m.ivAtk)
        assertTrue(m.ivExact)
        // El nivel de una lectura con IV estimados tampoco se toma: no cuadra con los IV exactos.
        assertEquals(30.0, m.level, 0.0)
    }

    @Test
    fun purifyingClearsShadow() {
        val shadow = box.copy(shadow = true)
        val m = mergeInto(shadow, box.copy(uid = "", purified = true))
        assertFalse(m.shadow)
        assertTrue(m.purified)
    }
}
