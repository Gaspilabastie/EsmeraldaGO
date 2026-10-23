package com.esmeraldago.app.critic

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.esmeraldago.app.EsmeraldaApp
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.ui.collection.CollectionScreen
import com.esmeraldago.app.ui.collection.EditPokemonScreen
import com.esmeraldago.app.ui.collection.OwnedDetailScreen
import com.esmeraldago.app.ui.common.ProvideContainer
import com.esmeraldago.app.ui.home.HomeScreen
import com.esmeraldago.app.ui.max.MaxBattleScreen
import com.esmeraldago.app.ui.raids.RaidsScreen
import com.esmeraldago.app.ui.rankings.RankingsScreen
import com.esmeraldago.app.ui.rankings.SpeciesDetailScreen
import com.esmeraldago.app.ui.scan.ScanScreen
import com.esmeraldago.app.ui.settings.SettingsScreen
import com.esmeraldago.app.ui.teams.TeamsScreen
import com.esmeraldago.app.ui.theme.EmeraldBackground
import com.esmeraldago.app.ui.theme.EmeraldTheme
import com.esmeraldago.app.ui.theme.LocalAnimations
import com.esmeraldago.core.model.OwnedPokemon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Capturas extra para la auditoría de UX (pantalla chica, letra grande, estados). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiCriticScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<EsmeraldaApp>()

    private fun waitForData() {
        val deadline = System.currentTimeMillis() + 30_000
        while (app.container.game.state.value !is GameState.Ready && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    private fun shoot(name: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
        waitForData()
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalAnimations provides false,
                LocalDensity provides Density(d.density, fontScale),
            ) {
                EmeraldTheme {
                    ProvideContainer(app.container) {
                        EmeraldBackground(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
        repeat(8) {
            Thread.sleep(400)
            rule.waitForIdle()
        }
        var bmp: Bitmap? = null
        rule.runOnUiThread {
            val view = rule.activity.window.decorView.rootView
            bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "critic_$name.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seed5() {
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

    private fun seed30() {
        waitForData()
        val idx = (app.container.game.state.value as GameState.Ready).idx
        val species = idx.released.filter { !it.shadow && !it.mega }.sortedBy { it.dex }.filterIndexed { i, _ -> i % 13 == 0 }.take(30)
        app.container.collection.addAll(species.mapIndexed { i, s ->
            OwnedPokemon("", s.id, 500 + i * 97, null, 20.0 + i, i % 16, (i * 7) % 16, (i * 5) % 16,
                ivExact = i % 4 != 0, lucky = i % 9 == 0, shadow = i % 7 == 0, dynamax = i % 11 == 0,
                nickname = if (i == 3) "Mi bicho favorito largo" else null)
        })
    }

    // --- Pantalla chica (360x640 mdpi) ---
    @Test @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun homeSmall() = shoot("home_small") { HomeScreen(onOpen = {}) }

    @Test @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun homeSmallFont() = shoot("home_small_font130", 1.3f) { HomeScreen(onOpen = {}) }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun rankingsSmallFont() = shoot("rankings_small_font130", 1.3f) { RankingsScreen(onBack = {}, onSpecies = {}) }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun ownedSmallFont() {
        seed5()
        val uid = app.container.collection.items.value.first { it.speciesId == "medicham" }.uid
        shoot("owned_small_font130", 1.3f) { OwnedDetailScreen(uid, onBack = {}, onEdit = {}) }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun speciesSmall() = shoot("species_small") { SpeciesDetailScreen("mewtwo", onBack = {}) }

    // --- Colección ---
    @Test
    fun collectionEmpty() = shoot("collection_empty") { CollectionScreen({}, {}, {}, {}) }

    @Test
    fun collection30() {
        seed30()
        shoot("collection_30") { CollectionScreen({}, {}, {}, {}) }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun collection30SmallFont() {
        seed30()
        shoot("collection_30_small_font130", 1.3f) { CollectionScreen({}, {}, {}, {}) }
    }

    // --- Editar ---
    @Test
    fun editNew() = shoot("edit_new") { EditPokemonScreen(null, onBack = {}) }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun editExistingSmall() {
        seed5()
        val uid = app.container.collection.items.value.first { it.speciesId == "medicham" }.uid
        shoot("edit_existing_small") { EditPokemonScreen(uid, onBack = {}) }
    }

    // --- Escaneo / Dinamax / Opciones / Incursiones / Equipos ---
    @Test
    fun scanEmpty() = shoot("scan_empty") { ScanScreen(onBack = {}) }

    @Test
    fun maxEmpty() = shoot("max_empty") { MaxBattleScreen(onBack = {}, onSpecies = {}) }

    @Test
    fun settings() = shoot("settings") { SettingsScreen(onBack = {}) }

    @Test
    fun raids() = shoot("raids") { RaidsScreen(onBack = {}, onBoss = { _, _ -> }) }

    @Test
    fun teamsEmpty() = shoot("teams_empty") { TeamsScreen(onBack = {}, onOwned = {}) }

    @Test
    fun homeWithCollection() {
        seed5()
        shoot("home_seeded") { HomeScreen(onOpen = {}) }
    }
}
