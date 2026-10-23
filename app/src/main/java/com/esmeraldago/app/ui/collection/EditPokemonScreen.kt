package com.esmeraldago.app.ui.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.common.MoveLabel
import com.esmeraldago.app.ui.common.SpeciesPickerDialog
import com.esmeraldago.app.ui.common.Sprite
import com.esmeraldago.app.ui.common.WithGame
import com.esmeraldago.app.ui.common.dexLabel
import com.esmeraldago.app.ui.common.fmtLevel
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.HpBar
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.app.ui.theme.SectionTitle
import com.esmeraldago.app.ui.theme.SmallPixelShape
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.ToggleChip
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.app.ui.theme.YesNoDialog
import com.esmeraldago.app.ui.theme.gbaStyle
import com.esmeraldago.core.calc.Ivs
import com.esmeraldago.core.calc.PokeMath
import com.esmeraldago.core.data.GameDataSources
import com.esmeraldago.core.model.Move
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.model.Species

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditPokemonScreen(uid: String?, onBack: () -> Unit) {
    val container = LocalContainer.current
    val original = remember(uid) { uid?.let { container.collection.get(it) } }
    val blank = OwnedPokemon(uid = "", speciesId = "", cp = 0, level = 0.0, ivAtk = 0, ivDef = 0, ivSta = 0, ivExact = false)
    // Se guarda como JSON para sobrevivir a la recreación de la Activity / muerte del proceso.
    val draftState = rememberSaveable(uid) {
        mutableStateOf(GameDataSources.json.encodeToString(OwnedPokemon.serializer(), original ?: blank))
    }
    var draft by JsonDraft(draftState)
    val d = draft
    var cpText by rememberSaveable(uid) { mutableStateOf(original?.cp?.toString() ?: "") }
    var hpText by rememberSaveable(uid) { mutableStateOf(original?.hp?.toString() ?: "") }
    var levelText by rememberSaveable(uid) { mutableStateOf(original?.level?.let { fmtLevel(it) } ?: "") }
    var picking by remember { mutableStateOf(false) }
    var pickMove by remember { mutableStateOf<Int?>(null) } // 0 = rápido, 1/2 = cargados
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val cp = cpText.toIntOrNull()
    val level = levelText.replace(',', '.').toDoubleOrNull()
    val cpError = if (cpText.isNotEmpty() && (cp == null || cp < 10)) "El PC es un número de 10 en adelante." else null
    val levelError = if (levelText.isNotEmpty() && (level == null || level !in 1.0..51.0 || (level * 2) % 1.0 != 0.0))
        "Nivel entre 1 y 51, en pasos de 0.5." else null
    val current = d.copy(cp = cp ?: 0, hp = hpText.toIntOrNull(), level = level ?: 0.0)
    val changed = current != (original ?: blank)

    BackHandler(enabled = changed) { confirmDiscard = true }

    Column(Modifier.fillMaxSize()) {
        TitleBar(if (original == null) "Añadir Pokémon" else "Editar", onBack = { if (changed) confirmDiscard = true else onBack() })
        WithGame { idx, _ ->
            val s = idx[d.speciesId]
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WindowBox(Modifier.fillMaxWidth().clickable { picking = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                        if (s != null) Sprite(d, s, 64.dp)
                        Spacer(Modifier.width(8.dp))
                        GbaText(s?.let { "${dexLabel(it.dex)} ${it.name}  ▼" } ?: "▶ Tocá para elegir la especie", size = 16.sp)
                    }
                }

                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Datos")
                    Field("PC", cpText, "ej. 1520", KeyboardType.Number, cpError) { cpText = it.filter(Char::isDigit) }
                    Field("PS (opcional)", hpText, "ej. 120", KeyboardType.Number, null) { hpText = it.filter(Char::isDigit) }
                    Field("Nivel", levelText, "ej. 27.5", KeyboardType.Decimal, levelError) { levelText = it }
                    Field("Apodo", d.nickname ?: "", "(opcional)", KeyboardType.Text, null) { draft = d.copy(nickname = it.ifBlank { null }) }
                }

                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("IV")
                    if (original == null && !d.ivExact) GbaText("Si no los sabés, cargá PC (y PS/nivel) y tocá CALCULAR IV.", size = 12.sp, color = Pal.Muted)
                    IvStepper("Ataque", d.ivAtk) { draft = d.copy(ivAtk = it) }
                    IvStepper("Defensa", d.ivDef) { draft = d.copy(ivDef = it) }
                    IvStepper("PS", d.ivSta) { draft = d.copy(ivSta = it) }
                    ToggleChip("IV confirmados", d.ivExact, { draft = d.copy(ivExact = it) })
                    Spacer(Modifier.width(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelButton("CALCULAR NIVEL", {
                            message = when {
                                s == null -> "Elegí la especie primero."
                                cp == null -> "Cargá el PC primero."
                                else -> PokeMath.solveIvs(s, cp, idx, hp = hpText.toIntOrNull(), appraisal = Ivs(d.ivAtk, d.ivDef, d.ivSta))
                                    .firstOrNull()?.let { levelText = fmtLevel(it.level); "Nivel ${fmtLevel(it.level)}" }
                                    ?: "No hay nivel con ese PC e IV."
                            }
                        }, color = Pal.Blue)
                        PixelButton("CALCULAR IV", {
                            message = when {
                                s == null -> "Elegí la especie primero."
                                cp == null -> "Cargá el PC primero."
                                else -> {
                                    val c = PokeMath.solveIvs(s, cp, idx, hp = hpText.toIntOrNull(), levels = level?.let { listOf(it) },
                                        minIv = maxOf(if (d.lucky) 12 else 0, if (d.purified) 2 else 0))
                                    val pick = c.sortedBy { it.ivs.total }.getOrNull(c.size / 2)
                                    if (pick == null) "Ninguna combinación da ese PC${if (level != null) " en ese nivel" else ""}."
                                    else {
                                        draft = d.copy(ivAtk = pick.ivs.atk, ivDef = pick.ivs.def, ivSta = pick.ivs.sta, ivExact = c.size == 1)
                                        if (level == null) levelText = fmtLevel(pick.level)
                                        if (c.size == 1) "IV exactos: ${pick.ivs}" else "${c.size} combinaciones posibles; se eligió ${pick.ivs} (cargá la valoración para precisar)."
                                    }
                                }
                            }
                        }, color = Pal.Blue)
                    }
                    message?.let { GbaText(it, size = 12.sp, color = Pal.Muted) }
                }

                if (s != null) WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Movimientos")
                    MoveSlot("Rápido", idx.move(d.fast), s) { pickMove = 0 }
                    MoveSlot("Cargado 1", idx.move(d.charged1), s) { pickMove = 1 }
                    MoveSlot("Cargado 2", idx.move(d.charged2), s) { pickMove = 2 }
                }

                WindowBox(Modifier.fillMaxWidth()) {
                    SectionTitle("Estado")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToggleChip("Oscuro", d.shadow, { draft = d.copy(shadow = it, purified = if (it) false else d.purified) })
                        ToggleChip("Purificado", d.purified, { draft = d.copy(purified = it, shadow = if (it) false else d.shadow) })
                        ToggleChip("Con suerte", d.lucky, { draft = d.copy(lucky = it) })
                        ToggleChip("Shiny", d.shiny, { draft = d.copy(shiny = it) })
                        ToggleChip("Dinamax", d.dynamax, { draft = d.copy(dynamax = it, gigantamax = if (it) false else d.gigantamax) })
                        ToggleChip("Gigamax", d.gigantamax, { draft = d.copy(gigantamax = it, dynamax = if (it) false else d.dynamax) })
                    }
                }
            }

            // Barra fija con GUARDAR (no queda fuera de la pantalla en teléfonos chicos).
            val problems = listOfNotNull(
                "falta la especie".takeIf { s == null },
                "falta el PC".takeIf { cp == null },
                "falta el nivel".takeIf { level == null },
                cpError, levelError,
            )
            WindowBox(Modifier.fillMaxWidth().padding(8.dp), padding = 6.dp) {
                if (problems.isNotEmpty()) GbaText("Para guardar: " + problems.joinToString(", ") + ".", size = 12.sp, color = Pal.Red)
                PixelButton("GUARDAR", {
                    container.collection.upsert(current)
                    container.notify("¡${current.nickname ?: s?.name ?: "Pokémon"} guardado en la CAJA!")
                    onBack()
                }, Modifier.fillMaxWidth(), enabled = problems.isEmpty())
            }

            if (picking) SpeciesPickerDialog(idx, onDismiss = { picking = false }) {
                draft = d.copy(speciesId = it.id, fast = null, charged1 = null, charged2 = null)
                picking = false
            }
            pickMove?.let { slot ->
                val sp = s ?: return@let
                val options = (if (slot == 0) sp.fast else sp.charged).mapNotNull { idx.move(it) }
                MovePickerDialog(options, sp, onDismiss = { pickMove = null }) { m ->
                    draft = when (slot) {
                        0 -> d.copy(fast = m?.id)
                        1 -> d.copy(charged1 = m?.id)
                        else -> d.copy(charged2 = m?.id)
                    }
                    pickMove = null
                }
            }
        }
    }

    if (confirmDiscard) YesNoDialog(
        "¿Descartar los cambios?",
        onYes = { confirmDiscard = false; onBack() },
        onNo = { confirmDiscard = false },
    )
}

/** Expone un OwnedPokemon guardado como JSON en un estado "saveable". */
private class JsonDraft(private val state: androidx.compose.runtime.MutableState<String>) {
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): OwnedPokemon =
        GameDataSources.json.decodeFromString(OwnedPokemon.serializer(), state.value)
    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: OwnedPokemon) {
        state.value = GameDataSources.json.encodeToString(OwnedPokemon.serializer(), value)
    }
}

@Composable
private fun MoveSlot(label: String, move: Move?, s: Species, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GbaText(label, size = 13.sp, color = Pal.Muted, modifier = Modifier.width(84.dp))
        if (move != null) MoveLabel(move, s, 14) else GbaText("—  (tocá para elegir)", size = 13.sp)
    }
}

@Composable
private fun MovePickerDialog(options: List<Move>, s: Species, onDismiss: () -> Unit, onPick: (Move?) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        WindowBox(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
            GbaText("Elegí el movimiento", size = 16.sp)
            LazyColumn {
                items(options, key = { it.id }) { m ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onPick(m) }.padding(vertical = 6.dp)) { MoveLabel(m, s, 15) }
                }
                item {
                    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onPick(null) }.padding(vertical = 6.dp)) { GbaText("(ninguno)", size = 15.sp) }
                }
            }
        }
    }
}

@Composable
private fun IvStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        GbaText(label, size = 14.sp, modifier = Modifier.width(80.dp))
        StepButton("–", "Bajar $label") { onChange((value - 1).coerceAtLeast(0)) }
        GbaText(value.toString().padStart(2), size = 18.sp, modifier = Modifier.width(40.dp))
        StepButton("+", "Subir $label") { onChange((value + 1).coerceAtMost(15)) }
        Spacer(Modifier.width(8.dp))
        HpBar(value / 15f, Modifier.weight(1f), color = if (value == 15) Pal.HpRed else Pal.Gold)
    }
}

@Composable
private fun StepButton(text: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .background(Pal.FrameOuter, SmallPixelShape).padding(2.dp).background(Pal.Select, SmallPixelShape)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GbaText(text, size = 18.sp) }
}

@Composable
private fun Field(label: String, value: String, hint: String, type: KeyboardType, error: String?, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GbaText(label, size = 14.sp, modifier = Modifier.width(120.dp))
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .background(if (error != null) Pal.Red else Pal.FrameOuter, SmallPixelShape).padding(2.dp)
                    .background(Color.White, SmallPixelShape).padding(8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) GbaText(hint, size = 15.sp, color = Pal.Muted)
                BasicTextField(
                    value, onChange,
                    textStyle = gbaStyle(16.sp), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        error?.let { GbaText(it, size = 12.sp, color = Pal.Red) }
    }
}
