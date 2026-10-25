package com.esmeraldago.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.esmeraldago.app.ui.theme.LocalAnimations
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.ui.collection.OwnedDetailScreen
import com.esmeraldago.app.ui.common.ProvideContainer
import com.esmeraldago.app.ui.home.HomeScreen
import com.esmeraldago.app.ui.rankings.RankingsScreen
import com.esmeraldago.app.ui.teams.TeamsScreen
import com.esmeraldago.app.ui.theme.EmeraldBackground
import com.esmeraldago.app.ui.theme.EmeraldTheme
import com.esmeraldago.core.model.OwnedPokemon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renderiza pantallas a PNG (app/build/screens) para revisar el diseño sin dispositivo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<EsmeraldaApp>()

    private fun waitForData() {
        val deadline = System.currentTimeMillis() + 30_000
        while (app.container.game.state.value !is GameState.Ready && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    private fun shoot(name: String, act: () -> Unit = {}, content: @Composable () -> Unit) {
        waitForData()
        rule.setContent {
            // Sin animaciones infinitas (cursor parpadeante) para que la UI llegue a "idle".
            CompositionLocalProvider(LocalAnimations provides false) {
                EmeraldTheme {
                    ProvideContainer(app.container) {
                        EmeraldBackground(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
        repeat(8) {
            Thread.sleep(400) // cálculos en Dispatchers.Default
            rule.waitForIdle()
        }
        act(); rule.waitForIdle()
        var bmp: Bitmap? = null
        rule.runOnUiThread {
            val view = rule.activity.window.decorView.rootView
            bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seed() {
        if (app.container.collection.items.value.isNotEmpty()) return
        app.container.collection.addAll(
            listOf(
                OwnedPokemon("", "machamp", 3056, 181, 40.0, 15, 15, 15, shadow = true, fast = "COUNTER", charged1 = "DYNAMIC_PUNCH"),
                OwnedPokemon("", "medicham", 1499, 140, 49.5, 0, 15, 15, fast = "COUNTER", charged1 = "ICE_PUNCH", charged2 = "PSYCHIC"),
                OwnedPokemon("", "azumarill", 1500, 160, 40.0, 0, 15, 15, fast = "BUBBLE", charged1 = "ICE_BEAM", charged2 = "PLAY_ROUGH"),
                OwnedPokemon("", "registeel", 1490, 130, 25.0, 0, 14, 15),
                OwnedPokemon("", "charizard", 2300, 130, 30.0, 14, 13, 12, gigantamax = true, fast = "FIRE_SPIN", charged1 = "BLAST_BURN"),
            )
        )
    }

    @Test
    fun home() = shoot("01_home", act = { repeat(20) { Thread.sleep(500); rule.waitForIdle() } }) { HomeScreen(onOpen = {}) }

    @Test
    fun rankings() = shoot("02_rankings_pve") { RankingsScreen(onBack = {}, onSpecies = {}) }

    @Test
    fun ownedDetail() {
        seed()
        val uid = app.container.collection.items.value.first { it.speciesId == "machamp" }.uid
        shoot("03_owned_detail") { OwnedDetailScreen(uid, onBack = {}, onEdit = {}) }
    }

    @Test
    fun teams() {
        seed()
        shoot("04_teams") { TeamsScreen(onBack = {}, onOwned = {}) }
    }

    @Test
    fun counters() = shoot("05_counters") {
        com.esmeraldago.app.ui.raids.CountersScreen("kyogre", "T5", onBack = {}, onSpecies = {})
    }

    @Test
    fun advice() {
        seed()
        shoot("06_advice") { com.esmeraldago.app.ui.advice.AdviceScreen(onBack = {}, onOwned = {}, onSpecies = {}) }
    }

    @Test
    fun types() = shoot("08_types") { com.esmeraldago.app.ui.types.TypesScreen(onBack = {}) }

    @Test
    fun typesDefense() = shoot("09_types_defense", act = {
        rule.onNodeWithText("DEFENDER", useUnmergedTree = true).performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("VOLADOR")[0].performClick()
    }) { com.esmeraldago.app.ui.types.TypesScreen(onBack = {}) }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun typesNarrow() = shoot("10_types_360dp") { com.esmeraldago.app.ui.types.TypesScreen(onBack = {}) }

    @Test
    fun pokedex() = shoot("11_pokedex") { com.esmeraldago.app.ui.pokedex.PokedexScreen(onBack = {}, onSpecies = {}) }

    @Test
    fun speciesDetail() = shoot("07_species") {
        com.esmeraldago.app.ui.rankings.SpeciesDetailScreen("charizard", onBack = {})
    }
}
