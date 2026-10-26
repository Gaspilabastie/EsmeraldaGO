package com.esmeraldago.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.esmeraldago.app.AppContainer
import com.esmeraldago.app.data.GameState
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.LoadingBox
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.SmallPixelShape
import com.esmeraldago.app.ui.theme.Tag
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.app.ui.theme.gbaStyle
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.Species
import com.esmeraldago.core.reco.Recommender

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("Sin AppContainer") }

@Composable
fun ProvideContainer(c: AppContainer, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContainer provides c, content = content)

/** Muestra [content] cuando los datos del juego están listos. */
@Composable
fun WithGame(content: @Composable (GameIndex, Recommender) -> Unit) {
    val state by LocalContainer.current.game.state.collectAsState()
    when (val s = state) {
        is GameState.Ready -> content(s.idx, s.reco)
        is GameState.Loading -> LoadingBox("Cargando Pokédex")
        is GameState.Error -> Box(Modifier.padding(16.dp)) { WindowBox { GbaText("Error: ${s.message}") } }
    }
}

private const val SPRITES = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon"

/**
 * Fuentes de sprite en orden de preferencia (si una no existe se prueba la siguiente):
 * Esmeralda (Gen I–III), Negro/Blanco (Gen IV–V), estilo Showdown (pixel, cubre Gen VI–IX) y, como
 * último recurso, el artwork oficial (los pocos Paradox / legendarios recientes sin pixel art).
 */
/** Formas que en Showdown son la forma base (su sprite no lleva sufijo). */
private val BASE_FORMS = setOf("altered", "incarnate", "land", "ordinary", "aria", "standard", "normal", "hero", "confined",
    "average", "plant", "shield", "midday", "baile", "male", "overcast", "core", "fullbelly", "amped", "curly", "solo", "ice")

/**
 * Sprite de la FORMA (Showdown, pixel estilo Negro/Blanco): sin esto Exeggutor de Alola, las megas o Zacian
 * Espada Suprema mostraban el sprite de la forma base. "Exeggutor (Alolan)" → exeggutor-alola.
 */
private fun formSpriteUrl(s: Species, shiny: Boolean): String? {
    val name = s.name.replace(" (Shadow)", "")
    val form = Regex("""\((.+)\)""").find(name)?.groupValues?.get(1) ?: return null
    val base = name.substringBefore(" (").lowercase().replace(Regex("[^a-z0-9]"), "")
    // "Galarian Zen" → "galarzen" (Showdown abrevia la región también cuando va con otra forma).
    var f = form.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        .replace("galarian ", "galar ").replace("alolan ", "alola ").replace("hisuian ", "hisui ").replace("paldean ", "paldea ")
    f = when {
        f == "alolan" -> "alola"; f == "galarian" -> "galar"; f == "hisuian" -> "hisui"; f == "paldean" -> "paldea"
        f.startsWith("crowned") -> "crowned"
        f.endsWith(" forme") -> f.removeSuffix(" forme")
        f == "female" -> "f"
        else -> f
    }.replace(" ", "")
    if (f in BASE_FORMS) return null
    return "https://play.pokemonshowdown.com/sprites/gen5${if (shiny) "-shiny" else ""}/$base-$f.png"
}

/** Cadena de sprites de una especie: primero el de su forma (si es una forma), después el del número. */
fun spriteUrls(s: Species, shiny: Boolean = false, gmax: Boolean = false): List<String> =
    listOfNotNull(gmaxSpriteUrl(s, shiny).takeIf { gmax }, formSpriteUrl(s, shiny)) + spriteUrls(s.dex, shiny)

/** Sprite Gigamax (Showdown): "charizard-gmax"; Urshifu Fluido es "urshifu-rapidstrikegmax". */
private fun gmaxSpriteUrl(s: Species, shiny: Boolean): String {
    val base = s.name.substringBefore(" (").lowercase().replace(Regex("[^a-z0-9]"), "")
    val slug = if (s.id == "urshifu_rapid_strike") "urshifu-rapidstrikegmax" else "$base-gmax"
    return "https://play.pokemonshowdown.com/sprites/gen5${if (shiny) "-shiny" else ""}/$slug.png"
}

fun spriteUrls(dex: Int, shiny: Boolean = false): List<String> = buildList {
    val sh = if (shiny) "shiny/" else ""
    when {
        dex in 1..386 -> add("$SPRITES/versions/generation-iii/emerald/$sh$dex.png")
        dex in 387..649 -> add("$SPRITES/versions/generation-v/black-white/$sh$dex.png")
    }
    add("$SPRITES/other/showdown/$sh$dex.gif")
    add("$SPRITES/other/official-artwork/$sh$dex.png")
}

/**
 * Cada fuente usa un lienzo distinto (Esmeralda 64×64, Negro/Blanco 96×96, Showdown variable) y el Pokémon
 * ocupa una parte distinta de él: por eso Lucario (lienzo de 96) se veía chico al lado de Latios (lienzo de 64).
 * Se recorta el margen transparente para que todos llenen el cuadro por igual.
 */
private object TrimTransparent : coil.transform.Transformation {
    override val cacheKey = "trim-transparent-v1"
    override suspend fun transform(input: android.graphics.Bitmap, size: coil.size.Size): android.graphics.Bitmap {
        val w = input.width
        val h = input.height
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 16) {
                if (x < l) l = x; if (x > r) r = x
                if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < l || b < t) return input
        return android.graphics.Bitmap.createBitmap(input, l, t, r - l + 1, b - t + 1)
    }
}

/** Sprite de un Pokémon de tu caja: muestra su versión shiny y los íconos de estado. */
@Composable
fun Sprite(p: com.esmeraldago.core.model.OwnedPokemon, species: Species, size: Dp = 56.dp) =
    Sprite(species, size, shadow = p.shadow, shiny = p.shiny, purified = p.purified, lucky = p.lucky,
        dynamaxIcon = p.dynamax, gigamaxIcon = p.gigantamax)

/**
 * Sprite con íconos de estado en las esquinas: llamita violeta (oscuro), destello (shiny), trébol (con suerte),
 * destello celeste (purificado) y nube roja (Dinamax; con núcleo dorado, Gigamax). Van en el sprite y no como
 * etiquetas al lado de los tipos.
 */
@Composable
fun Sprite(
    species: Species, size: Dp = 56.dp, shadow: Boolean = species.shadow,
    shiny: Boolean = false, purified: Boolean = false, lucky: Boolean = false,
    /** false cuando el destello ya lo muestra otro control (el botón shiny de la Pokédex). */
    shinyIcon: Boolean = true,
    gmax: Boolean = false,
    dynamaxIcon: Boolean = false,
    gigamaxIcon: Boolean = false,
) {
    val urls = remember(species.id, shiny, gmax) { spriteUrls(species, shiny, gmax) }
    // Atados a las URL, no a la especie: el oscuro usa la misma imagen que el normal y, al cambiar entre ellos,
    // la imagen no se vuelve a cargar (no hay aviso de "cargó") y el número quedaba visible detrás.
    var attempt by remember(urls) { mutableStateOf(0) }
    var loaded by remember(urls) { mutableStateOf(false) }
    val icon = (size * 0.3f).coerceIn(12.dp, 24.dp)
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        // Sin conexión (o mientras carga) se ve el número, no un hueco en blanco.
        if (!loaded) GbaText(dexLabel(species.dex), size = (size.value / 5).coerceIn(9f, 16f).sp, color = Pal.Muted)
        if (attempt < urls.size) AsyncImage(
            model = remember(urls[attempt]) {
                coil.request.ImageRequest.Builder(context)
                    .data(urls[attempt])
                    .transformations(TrimTransparent)
                    .allowHardware(false)
                    .build()
            },
            contentDescription = species.name,
            // Un pequeño margen para que no toque los bordes de la ventana.
            modifier = Modifier.fillMaxSize().padding(size * 0.06f),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            onSuccess = { loaded = true },
            onError = { attempt++ },
        )
        if (shadow) ShadowIcon(icon, Modifier.align(Alignment.BottomStart))
        if (purified) PurifiedIcon(icon, Modifier.align(Alignment.BottomStart))
        if (shiny && shinyIcon) ShinyIcon(icon, Modifier.align(Alignment.TopStart))
        if (lucky) LuckyIcon(icon, Modifier.align(Alignment.TopEnd))
        // El Gigamax ya implica Dinamax: se muestra uno solo.
        if (gigamaxIcon) GigamaxIcon(icon, Modifier.align(Alignment.BottomEnd))
        else if (dynamaxIcon) DynamaxIcon(icon, Modifier.align(Alignment.BottomEnd))
    }
}

/** Sprite suelto por URL (variantes de aspecto que no son entradas: letras de Unown, diseños de Vivillon…). */
@Composable
fun SpriteUrl(url: String, size: Dp, description: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AsyncImage(
        model = remember(url) {
            coil.request.ImageRequest.Builder(context).data(url).transformations(TrimTransparent).allowHardware(false).build()
        },
        contentDescription = description,
        modifier = Modifier.size(size).padding(size * 0.06f),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

/** Insignia de tier (S, A+, A…), con colores que bajan de "caliente" a "frío". */
@Composable
fun TierBadge(tier: com.esmeraldago.core.calc.Tier) {
    val color = when (tier) {
        com.esmeraldago.core.calc.Tier.S -> Color(0xFFE03848)
        com.esmeraldago.core.calc.Tier.A_PLUS -> Color(0xFFF07830)
        com.esmeraldago.core.calc.Tier.A -> Color(0xFFE8B030)
        com.esmeraldago.core.calc.Tier.B_PLUS -> Color(0xFF58B850)
        com.esmeraldago.core.calc.Tier.B -> Color(0xFF38A8A0)
        com.esmeraldago.core.calc.Tier.C -> Color(0xFF5878C8)
        com.esmeraldago.core.calc.Tier.D -> Color(0xFF888888)
    }
    Tag(tier.label, color)
}

/**
 * Columna derecha de un ranking: el dato por el que se ordena, su % respecto del #1 y el tier de ese %.
 * [higherIsBetter] = false para datos donde menos es mejor (el % es entonces mejor / valor).
 */
@Composable
fun RankValue(value: Double, best: Double, text: String, width: Dp = 68.dp, tier: com.esmeraldago.core.calc.Tier? = null,
              higherIsBetter: Boolean = true) {
    val ratio = if (best <= 0 || value <= 0) 0.0 else if (higherIsBetter) value / best else best / value
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(width)) {
        TierBadge(tier ?: com.esmeraldago.core.calc.Tier.of(ratio, 1.0))
        GbaText(text, size = 16.sp)
        GbaText("${(ratio * 100).toInt()} %", size = 11.sp, color = Pal.Muted)
        com.esmeraldago.app.ui.theme.HpBar(ratio.toFloat(), Modifier.fillMaxWidth(), height = 5.dp)
    }
}

/** "49.2 (93%)": valor con su porcentaje respecto del #1. */
fun pctOfTop(value: Double, top: Double) = if (top > 0) " (${(value / top * 100).toInt()}% del #1)" else ""

/** Nombre para mostrar: lo de "Shadow" ya lo indica la llamita del sprite. */
val Species.displayName: String get() = name.replace(" (Shadow)", "")

/** Nombre de un movimiento; LEGADO = solo se enseña con MT Élite (legado / día de la comunidad / evento). */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MoveLabel(move: Move, species: Species, size: Int = 13) {
    // Si no entra en un renglón, la etiqueta LEGADO (y el nombre largo) bajan: nunca se cortan con "…".
    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.align(Alignment.CenterVertically).padding(end = 3.dp).size(8.dp).background(Pal.type(move.type), SmallPixelShape))
        GbaText(move.name, size = size.sp, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 3.dp))
        if (species.isElite(move.id)) Box(Modifier.align(Alignment.CenterVertically)) { Tag("LEGADO", Pal.Red) }
    }
}

/** Fila de ranking: puesto, sprite, nombre y tipos + contenido extra. */
@Composable
fun RankRow(
    rank: Int,
    species: Species,
    onClick: (() -> Unit)? = null,
    title: String = species.displayName,
    gmax: Boolean = false,
    dynamax: Boolean = false,
    shadowTint: Boolean = species.shadow,
    /** El Pokémon que se vino a buscar (desde su Pokédex): marco del color del cursor. */
    highlight: Boolean = false,
    trailing: @Composable () -> Unit = {},
    body: @Composable () -> Unit = {},
) {
    WindowBox(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .let { if (highlight) it.border(3.dp, Pal.Cursor, com.esmeraldago.app.ui.theme.PixelShape) else it }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        padding = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GbaText(if (rank > 0) "#$rank" else "", size = 14.sp, modifier = Modifier.width(38.dp), color = Pal.Muted)
            Sprite(species, 52.dp, shadowTint, dynamaxIcon = dynamax, gigamaxIcon = gmax)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                GbaText(title, size = 15.sp, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    species.types.forEach { TypeBadge(it, small = true) }
                }
                body()
            }
            trailing()
        }
    }
}

/** Diálogo para elegir una especie con búsqueda por nombre. */
@Composable
fun SpeciesPickerDialog(
    idx: GameIndex,
    title: String = "Elegir Pokémon",
    filter: (Species) -> Boolean = { !it.shadow && !it.mega },
    preferred: List<Species> = emptyList(),
    onDismiss: () -> Unit,
    onPick: (Species) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val all = remember(idx) { idx.released.filter(filter).sortedBy { it.dex } }
    val matches = remember(query, all) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) preferred + all.filter { s -> preferred.none { it.id == s.id } }
        else all.filter { it.name.lowercase().contains(q) || it.dex.toString() == q }
    }
    val shown = matches.take(150)
    Dialog(onDismissRequest = onDismiss) {
        WindowBox(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            GbaText(title, size = 17.sp)
            Spacer(Modifier.height(6.dp))
            SearchField(query, { query = it }, "Buscar (nombre o número)")
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(s) }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sprite(s, 40.dp)
                        Spacer(Modifier.width(6.dp))
                        GbaText("${dexLabel(s.dex)} ${s.name}", size = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        s.types.forEach { TypeBadge(it, small = true) }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Pal.FrameOuter, SmallPixelShape)
            .padding(2.dp)
            .background(Color.White, SmallPixelShape)
            .padding(8.dp)
    ) {
        if (value.isEmpty()) GbaText(hint, color = Pal.Muted, size = 15.sp)
        BasicTextField(value, onChange, textStyle = gbaStyle(15.sp), singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

/** Hasta 2 cálculos pesados a la vez, para no saturar la CPU si se cambia de pestaña rápido. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val CalcDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2)

/**
 * Corre un cálculo del core fuera del hilo principal. Si la pantalla se va (se cancela la corrutina),
 * el hilo se interrumpe y el core corta en el próximo Interrupt.check().
 */
suspend fun <T> compute(block: () -> T): T =
    kotlinx.coroutines.withContext(CalcDispatcher) { kotlinx.coroutines.runInterruptible { block() } }

/**
 * Resultado de un cálculo pesado: conserva el valor anterior mientras recalcula (sin parpadeo ni salto
 * de scroll) y devuelve también si está ocupado.
 */
@Composable
fun <T> rememberComputed(vararg keys: Any?, block: () -> T): Pair<T?, Boolean> {
    var value by remember { mutableStateOf<T?>(null) }
    var busy by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(*keys) {
        busy = true
        value = compute(block)
        busy = false
    }
    return value to busy
}

/** Número de Pokédex como en Esmeralda: "No.068". */
fun dexLabel(dex: Int) = "No.%03d".format(dex)

fun fmtLevel(l: Double) = Recommender.fmt(l)
fun fmtNum(n: Int) = "%,d".format(n).replace(',', '.')
fun fmt1(d: Double) = "%.1f".format(d)
