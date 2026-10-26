package com.esmeraldago.app.live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.esmeraldago.app.ui.common.LocalContainer
import com.esmeraldago.app.ui.theme.YesNoDialog

/**
 * Devuelve una acción que arranca el escaneo en vivo pidiendo, en orden, los permisos necesarios:
 * 1) mostrar sobre otras apps (para EsmeraldaGO: es la que dibuja la burbuja), 2) notificaciones
 * (Android 13+), 3) captura de pantalla. Al volver de la configuración sigue sola con el paso siguiente.
 * Al terminar abre Pokémon GO.
 */
@Composable
fun rememberLiveScanStarter(): () -> Unit {
    val context = LocalContext.current
    val container = LocalContainer.current
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    var explainOverlay by remember { mutableStateOf(false) }
    // Se fue a la configuración a dar el permiso: al volver, continuar.
    var waitingOverlay by rememberSaveable { mutableStateOf(false) }

    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            LiveScanService.start(context, res.resultCode, data)
            openPokemonGo(context)
        } else {
            container.notify("Sin permiso de captura no se puede leer la pantalla.")
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Con o sin notificaciones se puede seguir (solo no se vería el aviso de "DETENER").
        projection.launch(mpm.createScreenCaptureIntent())
    }

    fun next() {
        when {
            !Settings.canDrawOverlays(context) -> explainOverlay = true
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> projection.launch(mpm.createScreenCaptureIntent())
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && waitingOverlay) {
                waitingOverlay = false
                if (Settings.canDrawOverlays(context)) next()
                else container.notify("Falta el permiso para mostrar EsmeraldaGO sobre otras apps.")
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    if (explainOverlay) YesNoDialog(
        "Paso 1 de 2: en la lista, activar \"Mostrar sobre otras apps\" para EsmeraldaGO (no Pokémon GO) " +
            "y volver con ◀. ¿Continuar?",
        onYes = {
            explainOverlay = false
            waitingOverlay = true
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        onNo = { explainOverlay = false },
    )

    return { next() }
}

fun openPokemonGo(context: Context) {
    context.packageManager.getLaunchIntentForPackage("com.nianticlabs.pokemongo")
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ?.let { runCatching { context.startActivity(it) } }
}
