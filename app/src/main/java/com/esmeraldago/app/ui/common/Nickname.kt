package com.esmeraldago.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.AppContainer
import com.esmeraldago.app.ui.theme.GbaText
import com.esmeraldago.app.ui.theme.Pal
import com.esmeraldago.app.ui.theme.PixelButton
import com.esmeraldago.core.GameIndex
import com.esmeraldago.core.model.OwnedPokemon
import com.esmeraldago.core.reco.Nicknames

/** El apodo para el juego según el estilo elegido en Opciones. */
fun nicknameFor(container: AppContainer, p: OwnedPokemon, idx: GameIndex): String {
    val s = container.settings.state.value
    return Nicknames.make(p, s.nick, idx, s.pvpMaxLevel)
}

/** Copia el apodo para pegarlo al renombrar el Pokémon en el juego. */
fun copyNickname(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Apodo", text))
}

/** "Apodo: 93 ⑮⑭⑫  COPIAR". */
@Composable
fun NicknameRow(container: AppContainer, p: OwnedPokemon, idx: GameIndex, modifier: Modifier = Modifier, onCopied: () -> Unit = {}) {
    val settings by container.settings.state.collectAsState()
    val context = LocalContext.current
    val nick = remember(p, settings.nickStyle, settings.pvpMaxLevel, idx) { Nicknames.make(p, settings.nick, idx, settings.pvpMaxLevel) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GbaText("Apodo: ", size = 14.sp, color = Pal.Muted)
        GbaText(nick, size = 14.sp, modifier = Modifier.weight(1f))
        PixelButton("COPIAR", { copyNickname(context, nick); onCopied() }, Modifier.padding(start = 6.dp), color = Pal.Muted)
    }
}
