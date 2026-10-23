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

/** Un grupo de tipos con su multiplicador; [compact] = una línea de texto (para los neutrales, que son muchos). */
class TypeGroup(val title: String, val items: List<Pair<String, Double>>, val compact: Boolean = false)

/**
 * Grupos de tipos con su multiplicador, un renglón de insignias por grupo. Los extremos (×2.56, ×0.24) se
 * resaltan. Sirve para defender (quién le pega a esta combinación) y para atacar (a quién le pega este tipo).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypeGroups(groups: List<TypeGroup>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.filter { it.items.isNotEmpty() }.forEach { g ->
            if (g.compact) {
                GbaText("${g.title}: " + g.items.joinToString(", ") { Types.es(it.first) }, size = 12.sp, color = Pal.Muted)
                return@forEach
            }
            GbaText(g.title, size = 13.sp, color = Pal.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                g.items.forEach { (t, m) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TypeBadge(t, small = true)
                        if (m != 1.0) GbaText(fmtMult(m), size = 11.sp, color = if (m >= 2.5 || m < 0.3) Pal.Text else Pal.Muted)
                    }
                }
            }
        }
    }
}

/** Defensa de una combinación de tipos: le pega fuerte / aguanta (lo más extremo primero) / neutral. */
@Composable
fun DefenseMatchups(types: List<String>) {
    val all = Types.ALL.map { it to Types.effectiveness(it, types) }
    TypeGroups(listOf(
        TypeGroup("Le pega fuerte", all.filter { it.second > 1.0 }.sortedByDescending { it.second }),
        TypeGroup("Aguanta", all.filter { it.second < 1.0 }.sortedBy { it.second }),
        TypeGroup("Neutral (×1)", all.filter { it.second == 1.0 }, compact = true),
    ))
}

/** Ataque con un tipo: súper eficaz / poco eficaz (lo más extremo primero) / neutral. */
@Composable
fun AttackMatchups(type: String) {
    val all = Types.attackChart(type).toList()
    TypeGroups(listOf(
        TypeGroup("Súper eficaz contra", all.filter { it.second > 1.0 }),
        TypeGroup("Poco eficaz contra", all.filter { it.second < 1.0 }.sortedBy { it.second }),
        TypeGroup("Neutral contra (×1)", all.filter { it.second == 1.0 }, compact = true),
    ))
}
