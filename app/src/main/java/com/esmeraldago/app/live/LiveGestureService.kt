package com.esmeraldago.app.live

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Servicio de accesibilidad (opcional) del escaneo en vivo. Hace esto y nada más:
 * - desliza la ficha de Pokémon GO hacia abajo para leer los ataques y la vuelve a subir;
 * - abre la valoración del Pokémon (toques en el menú y en el diálogo) para leer los IV exactos;
 * - avisa qué app está en primer plano, para ocultar la burbuja y pausar el modo continuo fuera del juego.
 * No lee el contenido de las ventanas (canRetrieveWindowContent = false en su configuración).
 */
class LiveGestureService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // La barra de notificaciones, el teclado o nuestras propias ventanas no cuentan como "salir del juego".
        if (pkg == packageName || pkg in IGNORED || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        _foreground.value = pkg
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        _foreground.value = null
        super.onDestroy()
    }

    /** Desliza en vertical por el centro de la pantalla; [from]/[to] en fracción de la altura. */
    suspend fun swipe(from: Float, to: Float, durationMs: Long = 450): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels * 0.5f
        val path = Path().apply { moveTo(x, m.heightPixels * from); lineTo(x, m.heightPixels * to) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    /** Un toque en [x]/[y] (fracción del ancho y del alto). */
    suspend fun tap(x: Float, y: Float): Boolean {
        val m = resources.displayMetrics
        val path = Path().apply { moveTo(m.widthPixels * x, m.heightPixels * y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    /** El botón "atrás" del sistema. */
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    companion object {
        const val POKEMON_GO = "com.nianticlabs.pokemongo"
        private val IGNORED = setOf("com.android.systemui", "android")

        @Volatile
        var instance: LiveGestureService? = null
            private set

        private val _foreground = MutableStateFlow<String?>(null)
        /** App en primer plano (null si el servicio no está activo y no se sabe). */
        val foreground: StateFlow<String?> = _foreground.asStateFlow()

        /** ¿El usuario activó el servicio en Ajustes → Accesibilidad? */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, LiveGestureService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
