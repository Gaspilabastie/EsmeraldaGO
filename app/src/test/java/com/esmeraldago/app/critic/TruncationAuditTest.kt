package com.esmeraldago.app.critic

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.esmeraldago.app.EsmeraldaApp
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.ui.common.ProvideContainer
import com.esmeraldago.app.ui.theme.EmeraldBackground
import com.esmeraldago.app.ui.theme.EmeraldTheme
import com.esmeraldago.app.ui.theme.LocalAnimations
import com.esmeraldago.core.model.OwnedPokemon
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
 * Busca textos cortados con "…", en el teléfono angosto común (360dp) y con una ventana
 * alta para que las listas compongan muchas filas. Escribe build/audit/cortados.txt: pantalla → texto.
 * Solo con AUDIT=1 (tarda varios minutos).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp-xhdpi")
class TruncationAuditTest {
    @Before fun onlyOnDemand() = Assume.assumeTrue(System.getenv("AUDIT") != null)

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<EsmeraldaApp>()
    private val out = File("build/audit").apply { mkdirs() }.let { File(it, "cortados.txt") }

    private fun waitForData() {
        val deadline = System.currentTimeMillis() + 30_000
        while (app.container.game.state.value !is GameState.Ready && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    private fun settle() {
        repeat(4) { Thread.sleep(250); rule.waitForIdle() }
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline &&
            rule.onAllNodesWithText("Calculando", substring = true).fetchSemanticsNodes().isNotEmpty()
        ) { Thread.sleep(300); rule.waitForIdle() }
        repeat(2) { Thread.sleep(250); rule.waitForIdle() }
    }

    /** Textos de la pantalla actual con alguna línea cortada con "…" o que se salen de su caja. */
    private fun record(screen: String) {
        settle()
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val cut = nodes.mapNotNull { n ->
            val results = mutableListOf<TextLayoutResult>()
            n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.firstOrNull() ?: return@mapNotNull null
            val ellipsized = (0 until r.lineCount).any { r.isLineEllipsized(it) }
            // hasVisualOverflow no sirve: la sombra dura del texto pixelado "desborda" siempre.
            if (ellipsized) r.layoutInput.text.text.replace("\n", " ⏎ ") else null
        }.distinct()
        synchronized(TruncationAuditTest::class.java) {
            cut.forEach { out.appendText("$screen\t$it\n") }
            if (cut.isEmpty()) out.appendText("$screen\t(ok)\n")
        }
    }

    // setContent va una sola vez por test: después se cambia la pantalla con este estado.
    private var current by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var started = false

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

    private fun click(text: String) {
        rule.onAllNodesWithText(text)[0].performClick()
    }

    private fun seed() {
        if (app.container.collection.items.value.isNotEmpty()) return
        app.container.collection.addAll(listOf(
            OwnedPokemon("", "machamp", 3056, 181, 40.0, 15, 15, 15, shadow = true, fast = "COUNTER", charged1 = "DYNAMIC_PUNCH"),
            OwnedPokemon("", "medicham", 1499, 140, 49.5, 0, 15, 15, fast = "COUNTER", charged1 = "ICE_PUNCH", charged2 = "PSYCHIC"),
            OwnedPokemon("", "azumarill", 1500, 160, 40.0, 0, 15, 15, fast = "BUBBLE", charged1 = "ICE_BEAM", charged2 = "PLAY_ROUGH"),
            OwnedPokemon("", "crabominable", 2100, 150, 30.0, 10, 12, 14, nickname = "Cangrejonazo"),
            OwnedPokemon("", "charizard", 2300, 130, 30.0, 14, 13, 12, gigantamax = true, fast = "FIRE_SPIN", charged1 = "BLAST_BURN"),
            OwnedPokemon("", "zacian_crowned_sword", 4300, 180, 40.0, 15, 14, 13),
        ))
    }

    private val species = listOf(
        "vivillon", "furfrou", "florges", "unown", "pikachu", "charizard", "mewtwo", "eevee", "darmanitan_galarian_standard",
        "zacian_crowned_sword", "necrozma_dawn_wings", "urshifu_rapid_strike", "meowth", "slowpoke", "rotom", "oricorio_pom_pom",
        "lycanroc_midnight", "toxtricity", "kyurem", "giratina_origin", "tapu_koko", "crabominable", "corviknight", "squawkabilly",
        "dudunsparce", "gimmighoul", "basculin", "sinistea", "minior_core", "deerling", "shellos", "genesect", "hoopa_unbound",
        "wormadam_trash", "pumpkaboo_super", "tauros_blaze", "mr_mime_galarian", "farfetchd_galarian", "aegislash_shield",
        "nidoran_female", "porygon_z", "kingambit", "ho_oh", "rayquaza", "deoxys_attack", "calyrex_ice_rider", "flabebe",
    )

    @Test
    fun pokedexEntries() {
        species.forEach { id ->
            if (app.container.game.state.value.let { it is GameState.Ready && it.idx[id] == null }) return@forEach
            show { com.esmeraldago.app.ui.rankings.SpeciesDetailScreen(id, onBack = {}) }
            record("pokédex/$id/INFO")
            click("COMBATE"); record("pokédex/$id/COMBATE")
            click("FORMAS"); record("pokédex/$id/FORMAS")
        }
    }

    @Test
    fun lists() {
        seed()
        show { com.esmeraldago.app.ui.home.HomeScreen(onOpen = {}) }; record("inicio")
        show { com.esmeraldago.app.ui.pokedex.PokedexScreen(onBack = {}, onSpecies = {}) }; record("pokédex/lista")
        show { com.esmeraldago.app.ui.rankings.RankingsScreen(onBack = {}, onSpecies = {}) }
        record("rankings/PvE")
        click("PvP"); record("rankings/PvP")
        click("Dinamax"); record("rankings/Dinamax")
        show { com.esmeraldago.app.ui.types.TypesScreen(onBack = {}) }; record("tipos/atacar")
        click("DEFENDER"); record("tipos/defender")
        show { com.esmeraldago.app.ui.raids.CountersScreen("kyogre", "T5", onBack = {}, onSpecies = {}) }; record("counters/kyogre")
        show { com.esmeraldago.app.ui.max.MaxBattleScreen(onBack = {}, onSpecies = {}) }; record("max")
        show { com.esmeraldago.app.ui.teams.TeamsScreen(onBack = {}, onOwned = {}) }; record("equipos")
        show { com.esmeraldago.app.ui.advice.AdviceScreen(onBack = {}, onOwned = {}, onSpecies = {}) }; record("consejos")
        show { com.esmeraldago.app.ui.collection.CollectionScreen(onBack = {}, onOpen = {}, onAdd = {}, onScan = {}) }; record("caja")
        app.container.collection.items.value.forEach { p ->
            show { com.esmeraldago.app.ui.collection.OwnedDetailScreen(p.uid, onBack = {}, onEdit = {}) }; record("caja/${p.speciesId}")
        }
        show { com.esmeraldago.app.ui.settings.SettingsScreen(onBack = {}) }; record("opciones")
    }
}
