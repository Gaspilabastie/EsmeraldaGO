package com.esmeraldago.app.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.esmeraldago.app.EsmeraldaApp
import com.esmeraldago.app.MainActivity
import com.esmeraldago.app.R
import com.esmeraldago.core.scan.ScreenType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Escaneo en vivo (como Poke Genie): un servicio en primer plano captura la pantalla con MediaProjection
 * y muestra una burbuja flotante sobre Pokémon GO. Tocar la burbuja lee el Pokémon en pantalla;
 * el modo continuo lee solo mientras deslizás por tu colección.
 * Las ventanas flotantes usan FLAG_SECURE: Android las excluye de la captura, así el OCR no se lee a sí mismo.
 */
class LiveScanService : LifecycleService(), SavedStateRegistryOwner {
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val container get() = (application as EsmeraldaApp).container
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var bubble: ComposeView? = null
    private var panel: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var continuousJob: Job? = null

    override fun onCreate() {
        savedState.performAttach()
        savedState.performRestore(null)
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (projection != null) return
        // Android 14+: el servicio debe estar en primer plano ANTES de pedir la proyección.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)) ?: run { stopSelf(); return }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return }
        // Requerido en Android 14 antes de crear el VirtualDisplay.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = mp
        capturer = ScreenCapturer(this, mp)
        _running.value = true
        showBubble()
        if (container.settings.state.value.liveContinuous) setContinuous(true)
    }

    // ------------------------------------------------------------------ acciones

    /** Lee lo que hay en pantalla ahora (un toque en la burbuja). */
    fun scanNow() {
        lifecycleScope.launch {
            _status.value = "Leyendo…"
            val bmp = grabWithoutOverlays() ?: run { _status.value = "Todavía no hay imagen de la pantalla."; return@launch }
            container.scan.enqueue(bmp, "En vivo")
            showPanel(true)
            _status.value = null
        }
    }

    /**
     * Las ventanas seguras (FLAG_SECURE) no se leen, pero en la captura aparecen como un rectángulo NEGRO:
     * con el panel abierto arriba se tapaba el PC y el nombre. Se quitan de la pantalla, se espera un
     * fotograma limpio, se captura y se vuelven a poner.
     */
    private suspend fun grabWithoutOverlays(): android.graphics.Bitmap? {
        val views = listOfNotNull(bubble?.let { it to bubbleParams }, panel?.let { it to panelParams })
        views.forEach { (v, _) -> runCatching { wm.removeView(v) } }
        delay(300) // que la pantalla se redibuje sin la burbuja ni el panel
        val bmp = capturer?.grab()
        views.forEach { (v, p) -> runCatching { wm.addView(v, p) } }
        return bmp
    }

    /** Modo continuo: lee cada ~1,2 s y agrega cada Pokémon distinto que aparezca. */
    fun setContinuous(on: Boolean) {
        container.settings.update { it.copy(liveContinuous = on) }
        continuousJob?.cancel()
        _continuous.value = on
        if (!on) return
        // En continuo el panel taparía (en negro) la parte de arriba de cada captura: se oculta.
        showPanel(false)
        continuousJob = lifecycleScope.launch {
            var lastKey: Triple<ScreenType, Int?, Int?>? = null
            val session = container.scan
            while (isActive) {
                delay(1200)
                val bmp = capturer?.grab() ?: continue
                val idx = session.idx()
                val parser = session.parserFor(idx)
                val a = try {
                    container.ocr.analyze(bmp, parser)
                } catch (t: Throwable) {
                    null
                } finally {
                    withContext(Dispatchers.Default) { bmp.recycle() }
                }
                a ?: continue
                val r = a.result
                if (r.screen == ScreenType.UNKNOWN || r.cp == null) continue
                val key = Triple(r.screen, r.cp, r.hp)
                if (key == lastKey) continue // mismo Pokémon todavía en pantalla
                lastKey = key
                session.submit(a, idx, parser)
                _detected.value++
            }
        }
    }

    fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_ROUTE, "scan"))
    }

    // ------------------------------------------------------------------ ventanas flotantes

    private fun overlayParams(width: Int, height: Int, focusable: Boolean = false) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@LiveScanService)
        setViewTreeSavedStateRegistryOwner(this@LiveScanService)
        // Se quita y se vuelve a poner en cada captura: que no pierda su estado.
        setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@LiveScanService))
        setContent(content)
    }

    private fun showBubble() {
        if (bubble != null) return
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = resources.displayMetrics.widthPixels - (resources.displayMetrics.density * 80).toInt()
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }
        bubbleParams = params
        bubble = composeView {
            LiveBubble(
                container = container,
                service = this,
                onDrag = { dx, dy ->
                    params.x += dx.toInt(); params.y += dy.toInt()
                    bubble?.let { runCatching { wm.updateViewLayout(it, params) } }
                },
            )
        }
        wm.addView(bubble, params)
    }

    fun showPanel(show: Boolean) {
        if (show && panel == null) {
            val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                y = (resources.displayMetrics.density * 24).toInt()
            }
            panelParams = params
            panel = composeView { LivePanel(container = container, service = this) }
            wm.addView(panel, params)
        } else if (!show && panel != null) {
            runCatching { wm.removeView(panel) }
            panel = null
        }
        _panelVisible.value = show
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Escaneo en vivo", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "scan"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("EsmeraldaGO está leyendo la pantalla")
            .setContentText("Tocá la burbuja sobre Pokémon GO para escanear.")
            .setContentIntent(open)
            .addAction(0, "DETENER", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        continuousJob?.cancel()
        listOfNotNull(bubble, panel).forEach { runCatching { wm.removeView(it) } }
        bubble = null; panel = null
        capturer?.release(); capturer = null
        projection?.stop(); projection = null
        _running.value = false
        _panelVisible.value = false
        _continuous.value = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "live_scan"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.esmeraldago.live.START"
        const val ACTION_STOP = "com.esmeraldago.live.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _continuous = MutableStateFlow(false)
        val continuous: StateFlow<Boolean> = _continuous.asStateFlow()
        private val _panelVisible = MutableStateFlow(false)
        val panelVisible: StateFlow<Boolean> = _panelVisible.asStateFlow()
        private val _status = MutableStateFlow<String?>(null)
        val status: StateFlow<String?> = _status.asStateFlow()
        private val _detected = MutableStateFlow(0)
        val detected: StateFlow<Int> = _detected.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, LiveScanService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveScanService::class.java).setAction(ACTION_STOP))
        }
    }
}
