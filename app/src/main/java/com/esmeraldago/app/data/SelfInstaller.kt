package com.esmeraldago.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.esmeraldago.app.BuildConfig
import com.esmeraldago.app.EsmeraldaApp
import com.esmeraldago.app.MainActivity
import com.esmeraldago.app.R
import java.io.File

/**
 * Instala una versión nueva con la API de sesiones de Android (como las tiendas), no con la pantalla del
 * instalador del sistema:
 * - Desde Android 12, si EsmeraldaGO es quien instaló la versión actual, la actualización no pide confirmación.
 *   La primera vez (la instalaste desde el navegador) Android todavía pregunta una vez.
 * - Se declara como fuente "tienda" (Android 13+): así Android no marca la app como "descargada de un archivo",
 *   que bloquea activar el servicio de accesibilidad del escaneo en vivo ("configuración restringida").
 * Al terminar, Android cierra la app para reemplazarla; [UpdatedReceiver] avisa con una notificación para volver.
 */
object SelfInstaller {
    private const val ACTION_STATUS = "com.esmeraldago.app.INSTALL_STATUS"

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 256 * 1024)
                    session.fsync(out)
                }
            }
            val status = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }
}

/** Resultado de la sesión: si Android pide confirmación (la primera vez), abre su diálogo; si falla, lo cuenta. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as? EsmeraldaApp)?.container?.updates
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // La app ya se está reemplazando.
            else -> updates?.installFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android no la instaló")
        }
    }
}

/**
 * Android avisa a la versión nueva que reemplazó a la anterior: una notificación lleva de vuelta a la app
 * (Android no deja abrirla sola desde segundo plano).
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("EsmeraldaGO se actualizó a ${BuildConfig.VERSION_NAME}")
                .setContentText("Tocá para abrirla.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 7001
    }
}
