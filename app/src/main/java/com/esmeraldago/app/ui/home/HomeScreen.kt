package com.esmeraldago.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.theme.DialogBox
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.MenuItem
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.WindowBox

private data class Entry(val label: String, val route: String, val help: String)

private val ENTRIES = listOf(
    Entry("POKéDEX", "pokedex", "Todas las especies con sus stats, tipos, movimientos y rankings."),
    Entry("MIS POKéMON", "collection", "Tu caja: IV, ranks PvP y costos de cada uno de tus Pokémon."),
    Entry("ESCANEAR", "scan", "Cargá tus Pokémon leyendo la pantalla del juego, en vivo o con capturas."),
    Entry("RANKINGS", "rankings", "Los mejores en PvE por tipo, en cada liga PvP y en Dinamax."),
    Entry("INCURSIONES", "raids", "Las incursiones de hoy y los mejores counters para cada jefe."),
    Entry("DINAMAX", "max", "Los mejores atacantes y tanques contra un jefe Dinamax."),
    Entry("EQUIPOS", "teams", "Equipos para cada liga PvP armados con tus Pokémon."),
    Entry("CONSEJOS", "advice", "Qué subir, qué evolucionar y qué conviene atrapar."),
    Entry("OPCIONES", "settings", "Actualizar los datos del juego y ajustes de cálculo."),
)

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val game by container.game.state.collectAsState()
    val owned by container.collection.items.collectAsState()
    val collectionError by container.collection.error.collectAsState()
    // El cursor ▶ siempre está en alguna opción y recuerda la última elegida (como en Esmeralda).
    // Tocar abre la opción; mantener apretado mueve el cursor y explica la opción sin abrirla.
    var selected by rememberSaveable { mutableStateOf(0) }
    var explaining by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("EsmeraldaGO")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // "Ficha de entrenador"
                WindowBox(Modifier.weight(1f)) {
                    GbaText("FICHA", size = 14.sp, color = Pal.Muted)
                    val ready = game as? GameState.Ready
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ready?.idx?.get("rayquaza")?.let { Sprite(it, 112.dp) }
                    }
                    GbaText("POKéMON: ${owned.size}", size = 15.sp)
                    GbaText("Dinamax: ${owned.count { it.dynamax || it.gigantamax }}", size = 15.sp)
                    GbaText("Oscuros: ${owned.count { it.shadow }}", size = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    GbaText(
                        when (val g = game) {
                            is GameState.Ready -> "Datos: ${g.idx.data.species.size} especies"
                            is GameState.Loading -> "Cargando datos…"
                            is GameState.Error -> "Error de datos"
                        },
                        size = 12.sp, color = Pal.Muted,
                    )
                }
                // Menú START
                WindowBox(Modifier.weight(1.15f), padding = 4.dp) {
                    ENTRIES.forEachIndexed { i, e ->
                        MenuItem(
                            e.label, selected == i, size = 16.sp,
                            onClick = { selected = i; explaining = null; onOpen(e.route) },
                            onLongClick = { selected = i; explaining = i },
                        )
                    }
                }
            }
            TodayRaids(game, onOpen)
        }
        DialogBox(
            collectionError
                ?: explaining?.let { ENTRIES.getOrNull(it)?.let { e -> "${e.label}: ${e.help}" } }
                ?: if (owned.isEmpty()) "¡Hola! Soy el PROF. ABEDUL. Empezá cargando tus Pokémon con ESCANEAR. " +
                    "Mantené apretada una opción para saber qué hace."
                else "¡Hola de nuevo! Tenés ${owned.size} Pokémon en la caja. ¿Qué hacemos hoy? " +
                    "(Mantené apretada una opción para saber qué hace.)",
            Modifier.padding(10.dp),
        )
    }
}

private fun shortTier(t: com.esmeraldago.core.calc.RaidTier) = when (t) {
    com.esmeraldago.core.calc.RaidTier.T1 -> "1★"
    com.esmeraldago.core.calc.RaidTier.T3 -> "3★"
    com.esmeraldago.core.calc.RaidTier.T5 -> "5★"
    com.esmeraldago.core.calc.RaidTier.MEGA, com.esmeraldago.core.calc.RaidTier.MEGA_LEGENDARY -> "MEGA"
    com.esmeraldago.core.calc.RaidTier.SHADOW_T1 -> "OSC 1★"
    com.esmeraldago.core.calc.RaidTier.SHADOW_T3 -> "OSC 3★"
    com.esmeraldago.core.calc.RaidTier.SHADOW_T5 -> "OSC 5★"
}

/** Jefes de incursión de nivel alto disponibles hoy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayRaids(game: GameState, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val raids by container.raids.raids.collectAsState()
    val idx = (game as? GameState.Ready)?.idx ?: return
    LaunchedEffect(idx) { if (raids == null) container.raids.refresh(idx) }
    val top = raids.orEmpty().filter {
        it.species != null && it.tier != com.esmeraldago.core.calc.RaidTier.T1 && it.tier != com.esmeraldago.core.calc.RaidTier.SHADOW_T1
    }.take(8)
    if (top.isEmpty()) return
    WindowBox(Modifier.fillMaxWidth().clickable { onOpen("raids") }) {
        GbaText("HOY EN INCURSIONES ▶", size = 14.sp, color = Pal.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            top.forEach { r ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp)) {
                    Sprite(r.species!!, 64.dp)
                    GbaText(shortTier(r.tier), size = 11.sp, color = Pal.Muted, maxLines = 1)
                }
            }
        }
    }
}
