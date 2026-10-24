package com.esmeraldago.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Insignias de tipo que se acomodan en varias líneas (nunca se cortan). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypeBadges(types: List<String>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        types.forEach { TypeBadge(it, small = true) }
    }
}

/**
 * Tipos agrupados por multiplicador, de mayor a menor: el multiplicador va una sola vez en el título del grupo.
 * [label] nombra cada grupo según su multiplicador ("Débil", "Resistente"…).
 */
@Composable
private fun ByMultiplier(chart: List<Pair<String, Double>>, label: (Double) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chart.groupBy { fmtMult(it.second) }.values
            .sortedByDescending { it.first().second }
            .forEach { group ->
                val m = group.first().second
                GbaText("${label(m)} ${fmtMult(m)}", size = 13.sp, color = if (m >= 2.5 || m < 0.3) Pal.Text else Pal.Muted)
                TypeBadges(group.map { it.first })
            }
    }
}

/** Qué tan eficaces son los ataques de cada tipo contra una combinación de tipos (uno o dos). */
@Composable
fun DefenseMatchups(types: List<String>) {
    ByMultiplier(Types.ALL.map { it to Types.effectiveness(it, types) }) { m ->
        when { m > 1.0 -> "Débil"; m < 1.0 -> "Resistente"; else -> "Neutral" }
    }
}

/** Qué tan eficaz es un ataque de un tipo contra cada tipo. */
@Composable
fun AttackMatchups(type: String) {
    ByMultiplier(Types.attackChart(type).toList()) { m ->
        when { m > 1.0 -> "Supereficaz contra"; m < 1.0 -> "Poco eficaz contra"; else -> "Neutral contra" }
    }
}

/**
 * "Débil a" con insignias, agrupado por multiplicador cuando hay debilidades dobles (×2.56 primero).
 * Para jefes de incursión, batallas Max y equipos.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeaknessBadges(defenderTypes: List<String>, modifier: Modifier = Modifier) {
    val weak = Types.weaknesses(defenderTypes).map { it to Types.effectiveness(it, defenderTypes) }
    if (weak.isEmpty()) return
    val groups = weak.groupBy { fmtMult(it.second) }.values.sortedByDescending { it.first().second }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GbaText("Débil a", size = 12.sp, color = Pal.Muted, modifier = Modifier.align(Alignment.CenterVertically))
        groups.forEach { g ->
            if (groups.size > 1) GbaText(fmtMult(g.first().second), size = 12.sp, color = Pal.Muted,
                modifier = Modifier.align(Alignment.CenterVertically).padding(start = 2.dp))
            g.forEach { TypeBadge(it.first, small = true) }
        }
    }
}
