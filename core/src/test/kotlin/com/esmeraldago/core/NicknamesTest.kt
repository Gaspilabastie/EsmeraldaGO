package com.esmeraldago.core

import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.reco.NickStyle
import com.esmeraldago.core.reco.Nicknames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class NicknamesTest {
    private val idx by lazy {
        val f = listOf("../app/src/main/assets/gamedata.json", "app/src/main/assets/gamedata.json").map(::File).first { it.exists() }
        GameIndex(GameDataSources.decode(f.readText()))
    }
    private val marill = OwnedPokemon(uid = "m", speciesId = "marill", cp = 400, level = 10.0, ivAtk = 15, ivDef = 14, ivSta = 12)

    @Test
    fun formats() {
        assertEquals("91 ⑮⑭⑫", Nicknames.make(marill, NickStyle.CIRCLED, idx))
        assertEquals("91 15/14/12", Nicknames.make(marill, NickStyle.SLASH, idx))
        val pvp = Nicknames.make(marill, NickStyle.PVP, idx)
        assertTrue(Regex("""^S\d+( U\d+)?$""").matches(pvp), pvp)
        val perfect = marill.copy(ivAtk = 15, ivDef = 15, ivSta = 15)
        listOf(NickStyle.entries).flatten().forEach { assertTrue(Nicknames.make(perfect, it, idx).length <= Nicknames.MAX) }
        assertEquals("100 15/15/15", Nicknames.make(perfect, NickStyle.SLASH, idx))
    }

    @Test
    fun tooStrongForTheLeagueHasNoRank() {
        val big = marill.copy(speciesId = "azumarill", cp = 2600, level = 50.0)
        assertEquals(null, Nicknames.pvpIvRank(big, com.esmeraldago.core.model.League.GREAT, idx, 50.0))
    }
}
