package com.esmeraldago.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.TypeBadge
import com.esmeraldago.core.model.Types

/** "×2.56", "×1.6", "×0.39"… como los multiplicadores de Pokémon GO. */
fun fmtMult(m: Double): String = "×" + when {
    m >= 2.5 -> "2.56"; m >= 1.5 -> "1.6"; m == 1.0 -> "1"
    m >= 0.6 -> "0.625"; m >= 0.38 -> "0.39"; m >= 0.24 -> "0.24"
    else -> "%.3f".format(m)
}

/**
 * Grupos de tipos con su multiplicador (de mayor a menor), un renglón de insignias por grupo. Sirve tanto
 * para defender (quién le pega a esta combinación) como para atacar (a quién le pega este tipo).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypeGroups(groups: List<Pair<String, List<Pair<String, Double>>>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.filter { it.second.isNotEmpty() }.forEach { (title, items) ->
            GbaText(title, size = 13.sp, color = Pal.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { (t, m) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TypeBadge(t, small = true)
                        if (m != 1.0) GbaText(fmtMult(m), size = 11.sp)
                    }
                }
            }
        }
    }
}

/** Defensa de una combinación de tipos: débil a / neutral / resiste, con multiplicadores. */
@Composable
fun DefenseMatchups(types: List<String>) {
    val all = Types.ALL.map { it to Types.effectiveness(it, types) }.sortedByDescending { it.second }
    TypeGroups(listOf(
        "Débil a" to all.filter { it.second > 1.0 },
        "Neutral (×1)" to all.filter { it.second == 1.0 },
        "Resiste" to all.filter { it.second < 1.0 },
    ))
}

/** Ataque con un tipo: súper eficaz / neutral / poco eficaz contra cada tipo. */
@Composable
fun AttackMatchups(type: String) {
    val all = Types.attackChart(type).toList().sortedByDescending { it.second }
    TypeGroups(listOf(
        "Súper eficaz contra" to all.filter { it.second > 1.0 },
        "Neutral contra (×1)" to all.filter { it.second == 1.0 },
        "Poco eficaz contra" to all.filter { it.second < 1.0 },
    ))
}
