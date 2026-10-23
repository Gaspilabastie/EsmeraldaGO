package com.esmeraldago.app.ui.types

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.AttackMatchups
import com.esmeraldago.app.ui.common.DefenseMatchups
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelTabs
import com.esmeraldago.app.ui.theme.TitleBar
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.app.ui.theme.TypePicker
import com.esmeraldago.app.ui.theme.WindowBox
import com.esmeraldago.core.model.Types

private enum class Mode(val label: String) { ATTACK("ATACAR"), DEFENSE("DEFENDER") }

/** Tabla de tipos: al atacar se elige un tipo; al defender, uno o dos (como un Pokémon de doble tipo). */
@Composable
fun TypesScreen(onBack: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(Mode.ATTACK) }
    var attack by rememberSaveable { mutableStateOf("fighting") }
    var defense by rememberSaveable { mutableStateOf(listOf("steel")) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Tipos", onBack)
        PixelTabs(Mode.entries, mode, { it.label }, { mode = it }, Modifier.padding(vertical = 6.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (mode) {
                Mode.ATTACK -> TypePicker(attack, { attack = it })
                // Tocar un tipo elegido lo quita; con dos elegidos, el nuevo reemplaza al más viejo.
                Mode.DEFENSE -> TypePicker("", { t ->
                    defense = when {
                        t in defense -> (defense - t).ifEmpty { defense }
                        defense.size < 2 -> defense + t
                        else -> listOf(defense.last(), t)
                    }
                }, selectedSet = defense.toSet())
            }
            Spacer(Modifier.height(8.dp))
            WindowBox(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                when (mode) {
                    Mode.ATTACK -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GbaText("Un ataque de tipo", size = 14.sp); TypeBadge(attack, small = true)
                        }
                        Spacer(Modifier.height(6.dp))
                        AttackMatchups(attack)
                    }
                    Mode.DEFENSE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GbaText("Un Pokémon", size = 14.sp); defense.forEach { TypeBadge(it, small = true) }
                        }
                        Spacer(Modifier.height(6.dp))
                        DefenseMatchups(defense)
                    }
                }
            }
            GbaText(
                "En Pokémon GO: súper eficaz ×1.6, poco eficaz ×0.625 y \"inmune\" ×0.39 (no hay inmunidad total). " +
                    "Con dos tipos se multiplican: ×2.56 si ambos son débiles.",
                size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(10.dp),
            )
            if (mode == Mode.DEFENSE && defense.size == 1) GbaText(
                "Tocá otro tipo para ver una combinación (${Types.es(defense.first())} + …).",
                size = 12.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}
