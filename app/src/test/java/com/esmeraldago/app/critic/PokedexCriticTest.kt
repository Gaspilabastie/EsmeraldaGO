package com.esmeraldago.app.critic

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.esmeraldago.app.EsmeraldaApp
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.ui.common.ProvideContainer
import com.esmeraldago.app.ui.rankings.SpeciesDetailScreen
import com.esmeraldago.app.ui.theme.EmeraldBackground
import com.esmeraldago.app.ui.theme.EmeraldTheme
import com.esmeraldago.app.ui.theme.LocalAnimations
import com.esmeraldago.core.calc.MaxCalc
import com.esmeraldago.core.calc.PveCalc
import com.esmeraldago.core.model.League
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
 * Capturas de la entrada de Pokédex (SpeciesDetailScreen) para la crítica de UX: ventana alta para ver todo
 * el contenido de cada pestaña, recortada al final del contenido y partida en tramos de ~850dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h3200dp-xhdpi")
class PokedexCriticTest {
    /** Tarda ~4 min: solo corre con CRITIC_SCREENS=1 (no en cada publicación). */
    @Before fun onlyOnDemand() = Assume.assumeTrue(System.getenv("CRITIC_SCREENS") != null)

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<EsmeraldaApp>()
    private val dir = File("build/screens/pokedex").apply { mkdirs() }

    private fun waitForData() {
        val deadline = System.currentTimeMillis() + 30_000
        while (app.container.game.state.value !is GameState.Ready && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    private fun log(line: String) = File(dir, "log.txt").appendText(line + "\n")

    private fun settle() {
        repeat(4) { Thread.sleep(300); rule.waitForIdle() }
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline &&
            rule.onAllNodesWithText("Calculando", substring = true).fetchSemanticsNodes().isNotEmpty()
        ) { Thread.sleep(300); rule.waitForIdle() }
        repeat(3) { Thread.sleep(300); rule.waitForIdle() }
    }

    private fun maxBottom(n: SemanticsNode): Float =
        if (n.children.isEmpty()) n.boundsInRoot.bottom else n.children.maxOf { maxBottom(it) }

    private fun capture(name: String) {
        val t0 = System.currentTimeMillis()
        settle()
        val bottom = maxBottom(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        var bmp: Bitmap? = null
        rule.runOnUiThread {
            val view = rule.activity.window.decorView.rootView
            bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val full = bmp!!
        val density = rule.activity.resources.displayMetrics.density
        val h = (bottom + 24 * density).toInt().coerceIn(1, full.height)
        val slice = (850 * density).toInt()
        var y = 0
        var part = 1
        while (y < h) {
            val sh = minOf(slice, h - y)
            val b = Bitmap.createBitmap(full, 0, y, full.width, sh)
            File(dir, "${name}_p$part.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            y += slice - (40 * density).toInt() // un poco de solapamiento entre tramos
            part++
            if (sh < slice) break
        }
        log("$name: contenido ${(bottom / density).toInt()}dp de ${(full.height / density).toInt()}dp, ${part - 1} tramos, espera ${System.currentTimeMillis() - t0}ms")
    }

    private fun shootAll(id: String, tag: String = id, fontScale: Float = 1f, shinyToo: Boolean = false) {
        waitForData()
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalAnimations provides false, LocalDensity provides Density(d.density, fontScale)) {
                EmeraldTheme {
                    ProvideContainer(app.container) {
                        EmeraldBackground(Modifier.fillMaxSize()) {
                            SpeciesDetailScreen(id, onBack = {}, onSpecies = { log("$tag -> onSpecies($it)") })
                        }
                    }
                }
            }
        }
        capture("${tag}_1info")
        rule.onNodeWithText("COMBATE").performClick()
        capture("${tag}_2combate")
        rule.onNodeWithText("FORMAS").performClick()
        capture("${tag}_3formas")
        if (shinyToo) {
            // El shiny se ve tocando el sprite del encabezado completo (INFO).
            rule.onNodeWithText("INFO").performClick()
            rule.onNodeWithContentDescription("Shiny: no").performClick()
            capture("${tag}_4shiny_resumen")
        }
    }

    @Test fun mewtwo() = shootAll("mewtwo", shinyToo = true)
    @Test fun mewtwoShadow() = shootAll("mewtwo_shadow")
    @Test fun mewtwoMegaY() = shootAll("mewtwo_mega_y")
    @Test fun eevee() = shootAll("eevee")
    @Test fun charizard() = shootAll("charizard")
    @Test fun exeggutorAlolan() = shootAll("exeggutor_alolan")
    @Test fun gengar() = shootAll("gengar")
    @Test fun zacian() = shootAll("zacian_crowned_sword")
    @Test fun unown() = shootAll("unown")
    @Test fun ditto() = shootAll("ditto")
    @Test fun magikarp() = shootAll("magikarp")
    @Test fun kingambit() = shootAll("kingambit")
    @Test fun pichu() = shootAll("pichu")
    @Test fun pikachu() = shootAll("pikachu")
    @Test fun meowth() = shootAll("meowth")
    @Test fun aegislash() = shootAll("aegislash_shield")
    @Test fun vivillon() = shootAll("vivillon")
    @Test fun darmanitan() = shootAll("darmanitan_galarian_standard")
    @Test fun kyurem() = shootAll("kyurem")
    @Test fun slowpoke() = shootAll("slowpoke")

    @Test @Config(qualifiers = "w360dp-h3200dp-xhdpi")
    fun mewtwoSmallFont() = shootAll("mewtwo", "small130_mewtwo", 1.3f)

    @Test @Config(qualifiers = "w360dp-h3200dp-xhdpi")
    fun eeveeSmallFont() = shootAll("eevee", "small130_eevee", 1.3f)

    @Test @Config(qualifiers = "w360dp-h3200dp-xhdpi")
    fun charizardSmallFont() = shootAll("charizard", "small130_charizard", 1.3f)

    /** Datos crudos para verificar lo que muestra la pantalla y cuánto tardan los cálculos de RESUMEN. */
    @Test fun dump() {
        waitForData()
        val idx = (app.container.game.state.value as GameState.Ready).idx
        val out = StringBuilder()
        val ids = listOf("mewtwo", "mewtwo_shadow", "mewtwo_mega_y", "eevee", "charizard", "exeggutor_alolan", "exeggutor", "gengar",
            "zacian_crowned_sword", "zacian_hero", "unown", "ditto", "magikarp", "kingambit", "pichu", "pikachu", "raichu", "meowth", "slowpoke")
        for (id in ids) {
            val s = idx[id]
            if (s == null) { out.appendLine("$id: NO EXISTE"); continue }
            out.appendLine("== $id '${s.name}' dex=${s.dex} types=${s.types} atk/def/sta=${s.atk}/${s.def}/${s.sta} buddyKm=${s.buddyKm} " +
                "dmax=${s.dynamax} gmax=${s.gigantamax} gmaxMove=${s.gmaxMove} specialMax=${s.specialMaxMove} leg=${s.legendary} myth=${s.mythical} " +
                "shadowElig=${s.shadowEligible} released=${s.released} parent=${s.parent} evos=${s.evolutions} family=${s.family} nameEs=${s.nameEs}")
            out.appendLine("   fast=${s.fast} charged=${s.charged} elite=${s.elite} legacy=${s.legacy}")
            out.appendLine("   byDex=${idx.byDex[s.dex].orEmpty().map { it.id }}")
            out.appendLine("   shadowOf=${idx[s.baseId]?.let { idx.shadowOf(it)?.id }} megas=${idx[s.baseId]?.let { b -> idx.megasOf(b).map { it.id } }}")
            out.appendLine("   pvp=" + League.entries.joinToString { l -> "${l.key}:${idx.pvpRank(l, s.id)?.let { "#${it.first} ${it.second.score} ${it.second.moveset}" }}" })
            out.appendLine("   chargedTypes=${PveCalc.usableCharged(s, idx).map { it.type }.distinct()} fastTypes=${PveCalc.usableFast(s, idx).map { it.type }.distinct()}")
        }
        // Tiempo de los cálculos de RESUMEN (en JVM de escritorio; en un teléfono es varias veces más).
        for (id in listOf("mewtwo", "mewtwo_mega_y", "charizard", "magikarp")) {
            val s = idx[id]!!
            val t0 = System.nanoTime()
            val types = PveCalc.usableCharged(s, idx).map { it.type }.distinct()
            val lines = types.map { type ->
                val filtered = PveCalc.topAttackers(type, idx, 40.0, includeShadow = s.shadow || s.mega, includeMega = s.mega, limit = 200).distinctBy { it.species.id }
                val all = PveCalc.topAttackers(type, idx, 40.0, limit = 200).distinctBy { it.species.id }
                val i = filtered.indexOfFirst { it.species.id == s.id }
                val j = all.indexOfFirst { it.species.id == s.id }
                val ratioF = if (i >= 0) filtered[i].score / filtered.first().score else -1.0
                val ratioA = if (j >= 0) all[j].score / all.first().score else -1.0
                "$type: clase #${i + 1} (${"%.2f".format(ratioF)} del #1 ${filtered.first().species.id}) | todos #${j + 1} (${"%.2f".format(ratioA)} del #1 ${all.first().species.id})" +
                    " | mov=${if (i >= 0) filtered[i].fast.id + "/" + filtered[i].charged.id else "-"}"
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            out.appendLine("PVE $id (${types.size} tipos, x2 por la comparación): ${ms}ms")
            lines.forEach { out.appendLine("   $it") }
            if (s.dynamax || s.gigantamax) {
                val t1 = System.nanoTime()
                val mt = PveCalc.usableFast(s, idx).flatMap { f -> listOfNotNull(MaxCalc.maxMoveType(s, false, f), MaxCalc.maxMoveType(s, true, f).takeIf { s.gigantamax }) }.distinct()
                mt.forEach { type ->
                    val list = MaxCalc.topAttackers(type, idx, 40.0, 3, limit = 100)
                    out.appendLine("   MAX $type: #${list.indexOfFirst { it.species.id == s.id } + 1} top=${list.first().species.id}/${list.first().gigantamax}")
                }
                out.appendLine("   MAX ${(System.nanoTime() - t1) / 1_000_000}ms")
            }
        }
        val dmax = idx.released.filter { it.dynamax || it.gigantamax }
        out.appendLine("DYNAMAX (${dmax.size}): " + dmax.joinToString { it.id + (if (it.gigantamax) "[G]" else "") })
        out.appendLine("MÁS FORMAS POR Nº: " + idx.byDex.entries.sortedByDescending { it.value.size }.take(20)
            .joinToString { "${it.key}:${it.value.size}" })
        idx.byDex.entries.sortedByDescending { it.value.size }.take(8).forEach { (dex, l) -> out.appendLine("   $dex: ${l.map { it.id }}") }
        File(dir, "dump.txt").writeText(out.toString())
    }
}
