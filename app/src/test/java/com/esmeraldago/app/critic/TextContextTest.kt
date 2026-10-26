package com.esmeraldago.app.critic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.esmeraldago.app.EsmeraldaApp
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.ui.common.ProvideContainer
import com.esmeraldago.app.ui.theme.EmeraldBackground
import com.esmeraldago.app.ui.theme.EmeraldTheme
import com.esmeraldago.app.ui.theme.LocalAnimations
import com.esmeraldago.core.model.OwnedPokemon
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Ubica cada texto del inventario (build/review/inventory.json) en las pantallas de la app y guarda un recorte de
 * la captura con el texto marcado: build/review/img/<id>.jpg y build/review/context.json (id → pantalla). Sirve
 * para revisar los textos en su contexto. Solo con CONTEXT=1.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp-xhdpi")
class TextContextTest {
    @Before fun onlyOnDemand() = Assume.assumeTrue(System.getenv("CONTEXT") != null)

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Serializable private data class Item(val id: String, val file: String, val text: String)
    /** [full]: el texto completo como se ve en pantalla (una oración partida en el código queda entera). */
    @Serializable private data class Found(val id: String, val screen: String, val img: String, val full: String)

    private val app get() = ApplicationProvider.getApplicationContext<EsmeraldaApp>()
    private val dir = File("build/review").apply { mkdirs() }
    private val imgDir = File(dir, "img").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private lateinit var pending: MutableList<Pair<Item, Regex>>
    private val found = mutableListOf<Found>()

    private var current by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var started = false

    private fun waitForData() {
        val deadline = System.currentTimeMillis() + 30_000
        while (app.container.game.state.value !is GameState.Ready && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    private fun settle(extraMs: Long = 0) {
        repeat(4) { Thread.sleep(250); rule.waitForIdle() }
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline &&
            rule.onAllNodesWithText("Calculando", substring = true).fetchSemanticsNodes().isNotEmpty()
        ) { Thread.sleep(300); rule.waitForIdle() }
        if (extraMs > 0) { Thread.sleep(extraMs); rule.waitForIdle() }
        repeat(2) { Thread.sleep(250); rule.waitForIdle() }
    }

    private fun show(content: @Composable () -> Unit) {
        waitForData()
        rule.runOnIdle { current = null }
        if (!started) {
            started = true
            rule.setContent {
                CompositionLocalProvider(LocalAnimations provides false) {
                    EmeraldTheme { ProvideContainer(app.container) { EmeraldBackground(Modifier.fillMaxSize()) { current?.invoke() } } }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { current = content }
    }

    private fun click(text: String) = rule.onAllNodesWithText(text)[0].performClick()

    /** "…" del inventario = parte variable del texto (un número, un nombre). */
    private fun pattern(text: String): Regex? {
        val parts = text.split("…").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.sumOf { it.length } < 12) return null
        return Regex(parts.joinToString(".*?") { Regex.escape(it) }, setOf(RegexOption.DOT_MATCHES_ALL))
    }

    /** Ubica los textos pendientes en la pantalla actual y guarda un recorte por cada uno. */
    private fun record(screen: String, extraMs: Long = 0) {
        settle(extraMs)
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.let { it to n.boundsInRoot } }
        val hits = pending.mapNotNull { (item, rx) -> nodes.firstOrNull { rx.containsMatchIn(it.first) }?.let { Triple(item, it.second, it.first) } }
        if (hits.isEmpty()) return
        var full: Bitmap? = null
        rule.runOnUiThread {
            val view = rule.activity.window.decorView.rootView
            full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val bmp = full!!
        val density = rule.activity.resources.displayMetrics.density
        for ((item, b, full) in hits) {
            val margin = 150 * density
            val top = (b.top - margin).toInt().coerceIn(0, bmp.height - 1)
            val bottom = (b.bottom + margin).toInt().coerceIn(top + 1, bmp.height)
            val crop = Bitmap.createBitmap(bmp.width, bottom - top, Bitmap.Config.ARGB_8888)
            Canvas(crop).apply {
                drawBitmap(bmp, Rect(0, top, bmp.width, bottom), Rect(0, 0, bmp.width, bottom - top), null)
                val pad = 4 * density
                drawRoundRect(RectF(b.left - pad, b.top - top - pad, b.right + pad, b.bottom - top + pad), 6 * density, 6 * density,
                    Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3 * density; color = 0xFFE0302A.toInt(); isAntiAlias = true })
            }
            val scaled = Bitmap.createScaledBitmap(crop, 480, (crop.height * 480f / crop.width).toInt(), true)
            val name = "${item.id}.jpg"
            File(imgDir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 72, it) }
            found += Found(item.id, screen, "img/$name", full)
        }
        val done = hits.map { it.first.id }.toSet()
        pending.removeAll { it.first.id in done }
    }

    private fun seed() {
        if (app.container.collection.items.value.isNotEmpty()) return
        app.container.collection.addAll(listOf(
            OwnedPokemon("", "machamp", 3056, 181, 40.0, 15, 15, 15, shadow = true, fast = "COUNTER", charged1 = "DYNAMIC_PUNCH"),
            OwnedPokemon("", "medicham", 1499, 140, 49.5, 0, 15, 15, fast = "COUNTER", charged1 = "ICE_PUNCH", charged2 = "PSYCHIC"),
            OwnedPokemon("", "azumarill", 1500, 160, 40.0, 0, 15, 15, fast = "BUBBLE", charged1 = "ICE_BEAM", charged2 = "PLAY_ROUGH"),
            OwnedPokemon("", "charizard", 2300, 130, 30.0, 14, 13, 12, ivExact = false, gigantamax = true, fast = "FIRE_SPIN", charged1 = "BLAST_BURN"),
            OwnedPokemon("", "gengar", 2600, 120, 35.0, 12, 10, 11, purified = true),
        ))
    }

    @Test
    fun locate() {
        val items = json.decodeFromString(ListSerializer(Item.serializer()), File(dir, "inventory.json").readText())
        pending = items.mapNotNull { i -> pattern(i.text)?.let { i to it } }.toMutableList()

        // Con la caja vacía primero: así aparecen los textos de "todavía no cargaste nada".
        show { com.esmeraldago.app.ui.collection.CollectionScreen(onBack = {}, onOpen = {}, onAdd = {}, onScan = {}) }; record("Mi caja (vacía)")
        show { com.esmeraldago.app.ui.advice.AdviceScreen(onBack = {}, onOwned = {}, onSpecies = {}) }; record("Qué atrapar (caja vacía)")
        show { com.esmeraldago.app.ui.teams.TeamsScreen(onBack = {}, onOwned = {}) }; record("Equipos (caja vacía)")
        show { com.esmeraldago.app.ui.home.HomeScreen(onOpen = {}) }; record("Inicio", 8000)
        click("MÁS"); record("Inicio › Más")
        show { com.esmeraldago.app.ui.scan.ScanScreen(onBack = {}) }; record("Escanear")
        show { com.esmeraldago.app.ui.collection.EditPokemonScreen(null, onBack = {}) }; record("Agregar Pokémon a mano")
        show { com.esmeraldago.app.ui.settings.SettingsScreen(onBack = {}) }; record("Opciones")

        seed()
        show { com.esmeraldago.app.ui.collection.CollectionScreen(onBack = {}, onOpen = {}, onAdd = {}, onScan = {}) }; record("Mi caja")
        app.container.collection.items.value.forEach { p ->
            show { com.esmeraldago.app.ui.collection.OwnedDetailScreen(p.uid, onBack = {}, onEdit = {}) }; record("Mi caja › ${p.speciesId}")
            show { com.esmeraldago.app.ui.collection.EditPokemonScreen(p.uid, onBack = {}) }; record("Mi caja › ${p.speciesId} › Editar")
        }
        show { com.esmeraldago.app.ui.advice.AdviceScreen(onBack = {}, onOwned = {}, onSpecies = {}) }; record("Qué atrapar")
        show { com.esmeraldago.app.ui.teams.TeamsScreen(onBack = {}, onOwned = {}) }; record("Equipos")
        show { com.esmeraldago.app.ui.pokedex.PokedexScreen(onBack = {}, onSpecies = {}) }; record("Pokédex (lista)")
        show { com.esmeraldago.app.ui.rankings.RankingsScreen(onBack = {}, onSpecies = {}) }
        record("Rankings › PvE")
        click("PvP"); record("Rankings › PvP")
        click("Legado"); record("Rankings › PvP › sin legado"); click("Legado")
        click("Dinamax"); record("Rankings › Dinamax")
        click("Tanques"); record("Rankings › Dinamax › Tanques")
        show { com.esmeraldago.app.ui.types.TypesScreen(onBack = {}) }; record("Tipos › Atacar")
        click("DEFENDER"); record("Tipos › Defender")
        show { com.esmeraldago.app.ui.raids.RaidsScreen(onBack = {}, onBoss = { _, _ -> }) }; record("Incursiones", 6000)
        show { com.esmeraldago.app.ui.raids.CountersScreen("kyogre", "T5", onBack = {}, onSpecies = {}) }; record("Incursiones › vs Kyogre")
        click("MIS POKéMON"); record("Incursiones › vs Kyogre › Mis Pokémon")
        show { com.esmeraldago.app.ui.max.MaxBattleScreen(onBack = {}, onSpecies = {}) }; record("Combate Dinamax")
        listOf("charizard", "charizard:gmax", "charizard_shadow", "charizard_mega_x", "pikachu", "mewtwo", "ditto", "magikarp",
            "unown", "aegislash_shield", "eevee", "kyurem", "genesect", "shellos", "vivillon", "darmanitan_galarian_standard", "spinda", "gimmighoul", "meowstic", "deerling",
        ).forEach { id ->
            show { com.esmeraldago.app.ui.rankings.SpeciesDetailScreen(id, onBack = {}) }
            record("Pokédex › $id › INFO")
            click("COMBATE"); record("Pokédex › $id › COMBATE")
            click("FORMAS"); record("Pokédex › $id › FORMAS")
        }

        File(dir, "context.json").writeText(json.encodeToString(ListSerializer(Found.serializer()), found))
        File(dir, "not-found.txt").writeText(pending.joinToString("\n") { "${it.first.id}\t${it.first.text}" } +
            "\n" + items.filter { pattern(it.text) == null }.joinToString("\n") { "${it.id}\t(muy variable) ${it.text}" })
    }
}
