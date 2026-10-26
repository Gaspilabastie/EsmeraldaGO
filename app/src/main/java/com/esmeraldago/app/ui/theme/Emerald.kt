package com.esmeraldago.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.R
import com.esmeraldago.core.model.Types
import kotlinx.coroutines.delay

/** Paleta inspirada en los menús de Pokémon Esmeralda (GBA). */
object Pal {
    val Bg = Color(0xFFB8E0C8)
    val BgStripe = Color(0xFFA8D4B8)
    val Header = Color(0xFF289860)
    val HeaderDark = Color(0xFF186840)
    val HeaderLight = Color(0xFF58C890)
    val Window = Color(0xFFF8F8F8)
    val FrameOuter = Color(0xFF404850)
    val FrameInner = Color(0xFF98B0C8)
    val Text = Color(0xFF404040)
    val TextShadow = Color(0xFFD0D0C8)
    val TextLight = Color(0xFFF8F8F8)
    val TextLightShadow = Color(0xFF585858)
    val Muted = Color(0xFF5C5C5C)
    val Select = Color(0xFFF8F0B0)
    val Cursor = Color(0xFF383838)
    val HpGreen = Color(0xFF70F8A8)
    val HpGreenDark = Color(0xFF58D080)
    val HpYellow = Color(0xFFF8E038)
    val HpRed = Color(0xFFF85838)
    val BarBg = Color(0xFF506858)
    /** Solo para errores y acciones destructivas (LIBERAR, DETENER). */
    val Red = Color(0xFFE04038)
    /** Avisos que no son errores (IV estimado, falta una MT, se pasa de PC): ámbar oscuro, legible y discreto. */
    val Warn = Color(0xFF9A6418)
    /** Algo bueno que destacar (jefe que rinde en rankings, IV casi perfecto). */
    val Good = Color(0xFF1E7A4A)
    val Blue = Color(0xFF3868C8)
    val Shadow = Color(0xFF7848A8)
    val Gold = Color(0xFFE8B030)
    val Orange = Color(0xFFE07828)
    val Dynamax = Color(0xFFE02868)

    val TYPE = mapOf(
        "normal" to Color(0xFFA8A878), "fire" to Color(0xFFF08030), "water" to Color(0xFF6890F0),
        "electric" to Color(0xFFF8D030), "grass" to Color(0xFF78C850), "ice" to Color(0xFF98D8D8),
        "fighting" to Color(0xFFC03028), "poison" to Color(0xFFA040A0), "ground" to Color(0xFFE0C068),
        "flying" to Color(0xFFA890F0), "psychic" to Color(0xFFF85888), "bug" to Color(0xFFA8B820),
        "rock" to Color(0xFFB8A038), "ghost" to Color(0xFF705898), "dragon" to Color(0xFF7038F8),
        "dark" to Color(0xFF705848), "steel" to Color(0xFFB8B8D0), "fairy" to Color(0xFFEE99AC),
    )

    fun type(t: String) = TYPE[t] ?: Color.Gray
}

/** Galmuri11 (OFL), pixel font inspirada en las de Nintendo; su grilla es de 12 px por em. */
val PixelFont = FontFamily(Font(R.font.galmuri11, FontWeight.Normal))
private const val FONT_GRID = 12f

/** Bordes "cortados" como los marcos pixelados de GBA. */
val PixelShape = CutCornerShape(4.dp)
val SmallPixelShape = CutCornerShape(2.dp)

@Composable
fun EmeraldTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Pal.Header, onPrimary = Pal.TextLight, secondary = Pal.Blue,
        background = Pal.Bg, surface = Pal.Window, onSurface = Pal.Text, onBackground = Pal.Text,
    )
    MaterialTheme(colorScheme = colors) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides gbaStyle(16.sp),
            content = content,
        )
    }
}

/**
 * Estilo de texto GBA: la sombra se desplaza exactamente 1 "píxel de fuente" (entero en px reales)
 * hacia abajo-derecha, como en Esmeralda, para que los bordes queden nítidos en cualquier densidad.
 */
@Composable
fun gbaStyle(size: TextUnit, color: Color = Pal.Text, shadow: Color = Pal.TextShadow): TextStyle {
    val px = with(LocalDensity.current) { size.toPx() }
    val off = (px / FONT_GRID).roundToInt().coerceAtLeast(1).toFloat()
    return TextStyle(
        fontFamily = PixelFont,
        fontSize = size,
        color = color,
        shadow = Shadow(shadow, Offset(off, off), 0f),
        lineHeight = size * 1.25f,
    )
}

/** Texto con la sombra dura típica de GBA. */
@Composable
fun GbaText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = Pal.Text,
    shadow: Color = if (color == Pal.TextLight) Pal.TextLightShadow else Pal.TextShadow,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    Text(
        text, modifier = modifier,
        style = gbaStyle(size, color, shadow),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align,
    )
}

/** Fondo verde a rayas diagonales. */
@Composable
fun EmeraldBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Pal.Bg)
            .drawBehind {
                val step = 24.dp.toPx()
                val w = 8.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(Pal.BgStripe, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = w)
                    x += step
                }
            },
        content = content,
    )
}

/** Ventana blanca con doble marco, como los menús de Esmeralda. */
@Composable
fun WindowBox(
    modifier: Modifier = Modifier,
    padding: Dp = 10.dp,
    background: Color = Pal.Window,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(Pal.FrameOuter, PixelShape)
            .padding(2.dp)
            .background(Pal.FrameInner, PixelShape)
            .padding(3.dp)
            .background(background, SmallPixelShape)
            .padding(padding),
        content = content,
    )
}

/** Barra de título verde con botón "volver". */
@Composable
fun TitleBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Pal.Header)
            .drawBehind {
                drawRect(Pal.HeaderLight, size = Size(size.width, 3.dp.toPx()))
                drawRect(Pal.HeaderDark, Offset(0f, size.height - 4.dp.toPx()), Size(size.width, 4.dp.toPx()))
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 40.dp)
                    .background(Pal.HeaderDark, SmallPixelShape)
                    .clickable(onClickLabel = "Volver", role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Volver" }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { GbaText("◀ B", size = 14.sp, color = Pal.TextLight) }
            Spacer(Modifier.width(10.dp))
        }
        GbaText(title.uppercase(), size = 20.sp, color = Pal.TextLight, modifier = Modifier.weight(1f), maxLines = 1)
        actions()
    }
}

/** false desactiva las animaciones infinitas (cursor, texto letra por letra); lo usan los tests de captura. */
val LocalAnimations = staticCompositionLocalOf { true }

/** Cursor ▶ fijo de los menús de Esmeralda (en el juego no parpadea). */
@Composable
fun BlinkingCursor(visible: Boolean = true, char: String = "▶") {
    GbaText(char, color = Pal.Cursor, modifier = Modifier.alpha(if (visible) 1f else 0f))
}

/** Flecha ▼ que rebota, como la que indica "seguir" en los diálogos de Esmeralda. */
@Composable
fun BouncingArrow() {
    var up by remember { mutableStateOf(false) }
    val animate = LocalAnimations.current
    LaunchedEffect(animate) { while (animate) { delay(300); up = !up } }
    GbaText("▼", color = Pal.Cursor, modifier = Modifier.offset(y = if (up) (-2).dp else 0.dp))
}

/** Opción de menú con cursor ▶ (estilo menú START). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MenuItem(
    text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: TextUnit = 18.sp,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (selected) Pal.Select else Color.Transparent, SmallPixelShape)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp)) { BlinkingCursor(selected) }
        GbaText(text, size = size)
    }
}

/** Cuadro de diálogo con texto letra por letra y flecha ▼. Tocarlo muestra todo el texto (como apretar A). */
@Composable
fun DialogBox(text: String, modifier: Modifier = Modifier, speedMs: Long = 18) {
    val animate = LocalAnimations.current
    var shown by remember(text) { mutableIntStateOf(if (animate) 0 else text.length) }
    LaunchedEffect(text) {
        if (!animate) return@LaunchedEffect
        while (shown < text.length) { delay(speedMs); shown++ }
    }
    WindowBox(
        modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { shown = text.length },
        padding = 12.dp,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            GbaText(text.take(shown), size = 17.sp)
            if (shown >= text.length) Box(Modifier.align(Alignment.BottomEnd)) { BouncingArrow() }
        }
    }
}

/** Color de texto legible sobre un fondo dado (texto oscuro en colores claros). */
fun readableOn(bg: Color): Pair<Color, Color> =
    if (bg.luminance() > 0.45f) Pal.Text to Color(0x66FFFFFF) else Pal.TextLight to Pal.TextLightShadow

@Composable
fun TypeBadge(type: String, modifier: Modifier = Modifier, small: Boolean = false, textSize: TextUnit? = null) {
    val c = Pal.type(type)
    val (fg, sh) = readableOn(c)
    Box(
        modifier
            .background(Color(0xFF303030), SmallPixelShape)
            .padding(1.dp)
            .background(c, SmallPixelShape)
            .padding(horizontal = if (textSize != null) 2.dp else if (small) 4.dp else 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        GbaText(Types.es(type).uppercase(), size = textSize ?: if (small) 11.sp else 12.sp, color = fg, shadow = sh, maxLines = 1)
    }
}

/** Etiqueta chica de color (OSCURO, MEGA, LEGADO…). */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    val (fg, sh) = readableOn(color)
    Box(
        modifier
            .background(color, SmallPixelShape)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) { GbaText(text, size = 11.sp, color = fg, shadow = sh, maxLines = 1) }
}

/** Cuadro de elección SÍ / NO al estilo Esmeralda. */
@Composable
fun YesNoDialog(question: String, onYes: () -> Unit, onNo: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onNo) {
        Column(horizontalAlignment = Alignment.End) {
            WindowBox(Modifier.widthIn(max = 160.dp), padding = 4.dp) {
                MenuItem("SÍ", selected = true, onClick = onYes)
                MenuItem("NO", selected = false, onClick = onNo)
            }
            Spacer(Modifier.height(6.dp))
            WindowBox(Modifier.fillMaxWidth(), padding = 12.dp) { GbaText(question, size = 17.sp) }
        }
    }
}

/** Barra estilo "PS" de GBA. */
@Composable
fun HpBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, color: Color? = null) {
    val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val c = color ?: when {
        f > 0.5f -> Pal.HpGreen
        f > 0.2f -> Pal.HpYellow
        else -> Pal.HpRed
    }
    Box(
        modifier
            .height(height + 4.dp)
            .background(Pal.Cursor, SmallPixelShape)
            .padding(2.dp)
            .background(Pal.BarBg)
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(c))
        Box(Modifier.fillMaxWidth(f).height(height / 3).background(Color.White.copy(alpha = 0.35f)))
    }
}

/** Barra de un IV (0..15), verde si es 15. */
@Composable
fun IvBar(label: String, value: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GbaText(label, size = 13.sp, modifier = Modifier.width(56.dp))
        HpBar(value / 15f, Modifier.weight(1f), color = if (value == 15) Pal.HpRed else Pal.Gold)
        GbaText(value.toString().padStart(2), size = 14.sp, modifier = Modifier.width(28.dp), align = TextAlign.End)
    }
}

@Composable
fun PixelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Pal.Header, enabled: Boolean = true) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .background(Pal.FrameOuter, PixelShape)
            .padding(2.dp)
            .background(color, PixelShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { GbaText(text, color = Pal.TextLight, size = 15.sp, maxLines = 1) }
}

/** Interruptor tipo casilla de opciones de GBA. */
@Composable
fun ToggleChip(label: String, checked: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 40.dp)
            .background(if (checked) Pal.Select else Pal.Window, SmallPixelShape)
            .border(2.dp, Pal.FrameOuter, SmallPixelShape)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GbaText(if (checked) "■" else "□", size = 14.sp)
        Spacer(Modifier.width(4.dp))
        GbaText(label, size = 13.sp, maxLines = 1)
    }
}

/** Pestañas: se reparten en filas para que la seleccionada siempre esté a la vista. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> PixelTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier,
                  /** Pestañas de igual ancho en una sola fila (no se parten en dos renglones con letra grande). */
                  fill: Boolean = false) {
    if (fill) {
        Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> PixelTab(label(o), o == selected, { onSelect(o) }, Modifier.weight(1f)) }
        }
        return
    }
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { o -> PixelTab(label(o), o == selected, { onSelect(o) }) }
    }
}

@Composable
private fun PixelTab(text: String, sel: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(Pal.FrameOuter, SmallPixelShape)
            .padding(2.dp)
            .background(if (sel) Pal.Select else Pal.Window, SmallPixelShape)
            .selectable(selected = sel, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sel) GbaText("▶", size = 12.sp)
            GbaText(text, size = 14.sp, maxLines = 1)
        }
    }
}

/**
 * Selector de tipos: grilla de 6×3 (los 18 tipos siempre visibles). Si no entra el nombre completo
 * (pantalla angosta o letra grande) se usan abreviaturas de 3 letras.
 * Elige un tipo ([selected]) o varios ([selectedSet], p. ej. los dos tipos de un defensor).
 */
@Composable
fun TypePicker(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, types: List<String> = Types.ALL,
               selectedSet: Set<String>? = null) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        // Grilla de 6×3 con los nombres completos: la letra se achica lo justo para que entre el más largo
        // (ELÉCTRICO); solo con letra muy grande del sistema pasa a 3 columnas.
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val longest = with(density) {
            types.maxOf { measurer.measure(Types.es(it).uppercase(), gbaStyle(11.sp)).size.width }.toDp()
        }
        // Lo que queda para el texto: separación 2 + caja de selección 3 por lado + insignia (borde 1 + relleno 2 por lado).
        fun room(cols: Int) = maxWidth / cols - 14.dp
        val fit = (room(6) / longest).coerceAtMost(1f)
        val cols = if (fit >= 0.72f) 6 else 3
        val textSize = 11.sp * if (cols == 6) fit else (room(3) / longest).coerceAtMost(1f)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            types.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { t ->
                        val sel = selectedSet?.contains(t) ?: (t == selected)
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .border(3.dp, if (sel) Pal.Cursor else Color.Transparent, SmallPixelShape)
                                .selectable(selected = sel, role = Role.RadioButton) { onSelect(t) }
                                .padding(horizontal = 3.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) { TypeBadge(t, Modifier.fillMaxWidth(), textSize = textSize) }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun LoadingBox(text: String = "Calculando…", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        WindowBox(Modifier.widthIn(max = 280.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GbaText(text)
                Spacer(Modifier.width(6.dp))
                BlinkingCursor(true, "…")
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .background(Pal.Header, SmallPixelShape)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { GbaText(text.uppercase(), size = 14.sp, color = Pal.TextLight) }
}

@Composable
fun Labeled(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        GbaText(label, size = 14.sp, color = Pal.Muted, modifier = Modifier.weight(1f))
        GbaText(value, size = 14.sp)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).background(color, SmallPixelShape))
