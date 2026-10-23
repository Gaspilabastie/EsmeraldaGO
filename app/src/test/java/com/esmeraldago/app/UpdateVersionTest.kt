package com.esmeraldago.app

import com.esmeraldago.app.data.UpdateRepository.Companion.isNewer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun comparesNumericallyNotAlphabetically() {
        assertTrue(isNewer("0.10.0", "0.9.9"))
        assertTrue(isNewer("0.2.1", "0.2.0"))
        assertTrue(isNewer("1.0", "0.9.9"))
        assertFalse(isNewer("0.2.0", "0.2.0"))
        assertFalse(isNewer("0.2", "0.2.0"))
        assertFalse(isNewer("0.1.9", "0.2.0"))
    }
}
