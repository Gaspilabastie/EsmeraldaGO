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
import com.esmeraldago.app.scan.OcrScanner
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
import kotlinx.coroutines.withTimeoutOrNull

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
    private var scanJob: Job? = null
    /** Pokémon GO no está adelante (lo sabe el servicio de accesibilidad, si está activo). */
    private var away = false

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
        // Fuera de Pokémon GO: la burbuja se oculta y el modo continuo se pausa (sin leer otras apps).
        lifecycleScope.launch {
            LiveGestureService.foreground.collect { pkg ->
                log("app adelante: $pkg")
                val nowAway = pkg != null && pkg != LiveGestureService.POKEMON_GO
                if (nowAway != away) { away = nowAway; setOverlaysVisible(!nowAway) }
            }
        }
    }

    private var panelWanted = false

    private fun setOverlaysVisible(visible: Boolean) {
        log(if (visible) "Pokémon GO adelante: se muestra la burbuja" else "otra app adelante: se oculta la burbuja")
        if (visible) {
            bubble?.let { v -> runCatching { wm.addView(v, bubbleParams) }.onFailure { log("no se pudo mostrar la burbuja: ${it.message}") } }
            if (panelWanted) panel?.let { v -> runCatching { wm.addView(v, panelParams) } }
        } else {
            listOfNotNull(bubble, panel).forEach { runCatching { wm.removeView(it) } }
        }
    }

    // ------------------------------------------------------------------ acciones

    /**
     * Registro de pruebas: cada paso va al log de Android (etiqueta "EsmLive") y a un archivo en la carpeta privada
     * de la app (Android/data/com.esmeraldago.app/files/live/), junto con las imágenes que leyó el OCR. Así una prueba
     * hecha sin la compu conectada se revisa después con adb. No se muestra en la app; se guardan las últimas
     * [MAX_FRAMES] imágenes.
     */
    private val testDir by lazy { java.io.File(getExternalFilesDir(null), "live").apply { mkdirs() } }
    private val stamp get() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    private fun log(msg: String) {
        android.util.Log.i("EsmLive", msg)
        runCatching { java.io.File(testDir, "log.txt").appendText("$stamp $msg\n") }
    }

    private fun saveFrame(bmp: android.graphics.Bitmap) = runCatching {
        val frames = java.io.File(testDir, "frames").apply { mkdirs() }
        val name = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date()) + ".jpg"
        java.io.File(frames, name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        log("imagen $name")
        frames.listFiles()?.sortedBy { it.name }?.dropLast(MAX_FRAMES)?.forEach { it.delete() }
    }

    private fun summary(a: OcrScanner.Analysis?): String = a?.result?.let { r ->
        "${r.screen} ${r.species?.id ?: "?"} PC ${r.cp ?: "?"}${if (r.cpInferred) "*" else ""} PS ${r.hp ?: "?"} " +
            "rápido ${r.fast ?: "-"} cargados ${r.charged.size} IV ${if (r.ivExact) "exactos" else "${r.candidates.size} opciones"}" +
            (r.appraisal?.let { " valoración ${it}" } ?: "") + (if (a.bars != null) " barras" else "") +
            (r.problem?.let { " problema: $it" } ?: "")
    } ?: "sin imagen"

    /** Lee lo que hay en pantalla ahora (un toque en la burbuja). */
    fun scanNow() {
        log("toque en la burbuja (lejos de Pokémon GO: $away, panel: $panelWanted)")
        if (scanJob?.isActive == true) {
            // Un escaneo sigue en curso: en vez de ignorar el toque, se muestra el panel con su estado.
            log("escaneo anterior todavía en curso: se muestra el panel")
            showPanel(true)
            return
        }
        scanJob = lifecycleScope.launch {
            _status.value = "Leyendo…"
            val idx = container.scan.idx()
            val parser = container.scan.parserFor(idx)
            pendingAppraisal = null
            log("— escaneo pedido")
            // Límite general: si algo se traba, se corta, la burbuja vuelve y se puede escanear de nuevo.
            var finished = false
            val a = withTimeoutOrNull(45_000) { withOverlaysHidden { captureBest(parser) }.also { finished = true } }
            val timedOut = !finished
            if (timedOut) log("el escaneo superó los 45 s y se cortó")
            log("resultado: ${summary(a)}" + (pendingAppraisal?.let { " + valoración: ${summary(it)}" } ?: ""))
            when {
                a == null && timedOut -> _status.value = "La lectura tardó demasiado. Volver a tocar la burbuja."
                a == null -> _status.value = "Sin imagen de la pantalla todavía."
                a.result.screen == ScreenType.UNKNOWN -> {
                    // No era la ficha de un Pokémon: se avisa, sin cargarlo como falla ni mostrar el anterior.
                    container.scan.clearLatest()
                    _status.value = a.result.problem ?: "No es la ficha de un Pokémon."
                }
                else -> {
                    container.scan.submit(a, idx, parser)
                    // La valoración leída sola se empareja con la ficha (mismo PC y PS).
                    pendingAppraisal?.let { container.scan.submit(it, idx, parser) }
                    _status.value = null
                }
            }
            showPanel(true)
        }
    }

    /**
     * La mejor lectura de lo que hay en pantalla:
     * - si el PC está tapado o no cuadra, unos fotogramas más (el modelo se mueve y lo destapa);
     * - si no se vieron los ataques y el servicio de accesibilidad está activo, baja la ficha, los lee y vuelve.
     */
    private suspend fun captureBest(parser: com.esmeraldago.core.scan.ScreenParser): OcrScanner.Analysis? {
        var best = analyzeFrame(parser) ?: return null.also { log("primer fotograma: sin imagen") }
        log("primer fotograma: ${summary(best)}")
        if (best.result.screen == ScreenType.UNKNOWN) return best
        // PC tapado o que no cuadra: el Pokémon se mueve y tapa dígitos distintos en cada fotograma. Se usan
        // TODOS (no "el mejor"): el parser ubica cada dígito en su casillero y vota (ScreenParser.parseFrames).
        if (best.result.cp == null || best.result.cpInferred) {
            val frames = mutableListOf(best)
            repeat(3) {
                delay(350)
                analyzeFrame(parser)?.takeIf { it.result.screen != ScreenType.UNKNOWN && it.result.hp == best.result.hp }?.let { frames += it }
            }
            if (frames.size > 1) best = best.copy(result = parser.parseFrames(frames.map { it.lines }, best.bars))
            log("PC tapado o dudoso: ${frames.size} fotogramas → ${summary(best)}")
        }
        val gestures = LiveGestureService.instance
        log("accesibilidad ${if (gestures != null) "activa" else "inactiva"}, escaneo asistido ${container.settings.state.value.autoScroll}")
        if (best.result.screen == ScreenType.DETAIL && best.result.fast == null && gestures != null &&
            container.settings.state.value.autoScroll && gestures.swipe(0.78f, 0.38f)
        ) {
            delay(900) // que termine el desplazamiento
            val lower = analyzeFrame(parser)
            log("bajó la ficha: ${summary(lower)}")
            gestures.swipe(0.38f, 0.78f)
            // La parte de abajo no muestra el PC (y casi nunca los PS); si muestra otros PS, es otro Pokémon.
            if (lower != null && (lower.result.hp == null || lower.result.hp == best.result.hp)) {
                best = best.copy(result = parser.combine(best.result, lower.result), lines = best.lines + lower.lines)
            }
            delay(300)
        }
        // IV sin definir: con el servicio de accesibilidad, se abre la valoración, se leen las barras y se cierra.
        if (best.result.screen == ScreenType.DETAIL && !best.result.ivExact && gestures != null &&
            container.settings.state.value.autoScroll
        ) {
            _status.value = "Leyendo la valoración…"
            pendingAppraisal = runCatching { readAppraisal(parser, gestures) }.getOrNull()
        }
        return best
    }

    /** Valoración leída en el último escaneo (se envía después de la ficha para que se emparejen). */
    private var pendingAppraisal: OcrScanner.Analysis? = null

    /**
     * Abre la valoración desde la ficha: toca el botón de menú (abajo a la derecha), busca con el OCR la opción
     * "Appraise"/"Valorar", avanza el diálogo hasta que se ven las barras, las lee y la cierra. Ante cualquier
     * pantalla inesperada vuelve atrás y abandona: nunca toca nada más.
     */
    private suspend fun readAppraisal(parser: com.esmeraldago.core.scan.ScreenParser, g: LiveGestureService): OcrScanner.Analysis? {
        val words = listOf("appraise", "valorar", "evaluar", "tasar")
        // Dos posiciones posibles del botón de menú (con y sin la barra de navegación de 3 botones).
        for ((x, y) in listOf(0.86f to 0.925f, 0.86f to 0.89f)) {
            if (!g.tap(x, y)) return null.also { log("valoración: no se pudo tocar el menú ($x, $y)") }
            delay(700)
            val menu = analyzeFrame(parser) ?: return null.also { log("valoración: sin imagen tras tocar el menú") }
            val item = menu.lines.firstOrNull { l -> words.any { l.text.lowercase().contains(it) } }
            log("valoración: toque en ($x, $y) → ${menu.result.screen}, opción ${item?.text ?: "no encontrada"}; " +
                "textos: ${menu.lines.take(12).joinToString(" | ") { it.text }}")
            if (item == null) {
                // El toque no abrió el menú: si cambió la pantalla, se vuelve; si no, se prueba la otra posición.
                if (menu.result.screen != ScreenType.DETAIL) { g.back(); delay(500); return null }
                continue
            }
            g.tap((item.left + item.right) / 2, (item.top + item.bottom) / 2)
            delay(1200)
            repeat(5) { step ->
                val a = analyzeFrame(parser)
                log("valoración: paso ${step + 1} → ${summary(a)}")
                if (a != null && a.result.screen == ScreenType.APPRAISAL && a.bars != null) {
                    g.tap(0.5f, 0.85f) // cierra la valoración y vuelve a la ficha
                    delay(800)
                    return a
                }
                g.tap(0.5f, 0.85f) // avanza el diálogo del líder de equipo
                delay(900)
            }
            log("valoración: no aparecieron las barras; se vuelve atrás")
            g.back(); delay(500)
            return null
        }
        log("valoración: el menú no se abrió en ninguna de las dos posiciones")
        return null
    }

    private suspend fun analyzeFrame(parser: com.esmeraldago.core.scan.ScreenParser): OcrScanner.Analysis? {
        val bmp = capturer?.grab() ?: return null
        withContext(Dispatchers.IO) { saveFrame(bmp) }
        return try {
            container.ocr.analyze(bmp, parser)
        } catch (t: Throwable) {
            log("error al leer el fotograma: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            withContext(Dispatchers.Default) { bmp.recycle() }
        }
    }

    /**
     * Las ventanas seguras (FLAG_SECURE) no se leen, pero en la captura aparecen como un rectángulo NEGRO:
     * con el panel abierto arriba se tapaba el PC y el nombre. Se quitan de la pantalla, se espera un
     * fotograma limpio, se captura y se vuelven a poner.
     */
    private suspend fun <T> withOverlaysHidden(block: suspend () -> T): T {
        val views = listOfNotNull(bubble?.let { it to bubbleParams }, panel?.let { it to panelParams })
        views.forEach { (v, _) -> runCatching { wm.removeView(v) } }
        delay(300) // que la pantalla se redibuje sin la burbuja ni el panel
        return try {
            block()
        } finally {
            if (!away) views.forEach { (v, p) -> runCatching { wm.addView(v, p) }.onFailure { log("no se pudo volver a mostrar una ventana: ${it.message}") } }
            else log("después de leer: Pokémon GO no está adelante, la burbuja queda oculta")
        }
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
            var misses = 0
            while (isActive) {
                // Fuera del juego no se lee nada; un buen rato sin ver un Pokémon, se lee más espaciado.
                delay(if (misses > 8) 4000 else 1200)
                if (away) continue
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
                if (r.screen == ScreenType.UNKNOWN || r.cp == null) { misses++; continue }
                misses = 0
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
        log(if (show) "panel abierto" else "panel cerrado")
        panelWanted = show
        if (show && panel == null) {
            val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                y = (resources.displayMetrics.density * 24).toInt()
            }
            panelParams = params
            panel = composeView { LivePanel(container = container, service = this) }
            if (!away) wm.addView(panel, params)
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
            .setContentText("Tocar la burbuja sobre Pokémon GO para escanear.")
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
        /** Imágenes de prueba que se conservan (~100 KB cada una). */
        private const val MAX_FRAMES = 300
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
