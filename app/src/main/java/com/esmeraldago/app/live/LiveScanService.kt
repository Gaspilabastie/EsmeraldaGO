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
import android.view.View
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
    private var menu: ComposeView? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuClosedAt = 0L
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
        overlays().forEach { (v, p) -> if (visible) moveBack(v, p) else moveAway(v, p) }
    }

    /*
     * Ocultar = correr la ventana fuera de la pantalla, nunca quitarla: una ComposeView quitada y vuelta a poner
     * dejaba de recibir toques (la burbuja no respondía después del primer escaneo). Fuera de la pantalla tampoco
     * aparece en la captura (las ventanas seguras salen como un rectángulo negro donde estén).
     */
    private val homePosition = HashMap<View, Pair<Int, Int>>()

    private fun overlays() = listOfNotNull(
        bubble?.let { v -> bubbleParams?.let { v to it } },
        panel?.let { v -> panelParams?.let { v to it } },
        menu?.let { v -> menuParams?.let { v to it } },
    )

    private fun moveAway(v: View, p: WindowManager.LayoutParams) {
        if (v in homePosition || !v.isAttachedToWindow) return
        homePosition[v] = p.x to p.y
        p.x = -20_000; p.y = -20_000
        runCatching { wm.updateViewLayout(v, p) }.onFailure { log("no se pudo ocultar una ventana: ${it.message}") }
    }

    private fun moveBack(v: View, p: WindowManager.LayoutParams) {
        val (x, y) = homePosition.remove(v) ?: return
        p.x = x; p.y = y
        runCatching { wm.updateViewLayout(v, p) }.onFailure { log("no se pudo volver a mostrar una ventana: ${it.message}") }
    }

    // ------------------------------------------------------------------ acciones

    /**
     * Registro de pruebas: cada paso va al log de Android (etiqueta "EsmLive") y a un archivo en la carpeta privada
     * de la app (Android/data/com.esmeraldago.app/files/live/), junto con las imágenes que leyó el OCR. Así una prueba
     * hecha sin la compu conectada se revisa después con adb. No se muestra en la app; se guardan las últimas
     * [MAX_FRAMES] imágenes.
     */
    private val testDir get() = LiveTestLog.dir(this)
    private val stamp get() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    private fun log(msg: String) {
        android.util.Log.i("EsmLive", msg)
        runCatching { java.io.File(testDir, "log.txt").appendText("$stamp $msg\n") }
    }

    /** Guarda la imagen y devuelve su nombre (para guardar al lado el texto que leyó el OCR). */
    private fun saveFrame(bmp: android.graphics.Bitmap): String? = runCatching {
        val frames = java.io.File(testDir, "frames").apply { mkdirs() }
        val name = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date()) + ".jpg"
        java.io.File(frames, name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        log("imagen $name")
        // Se conservan las últimas imágenes, cada una con su texto.
        frames.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }?.dropLast(LiveTestLog.MAX_FRAMES)?.forEach {
            it.delete(); java.io.File(frames, it.nameWithoutExtension + ".json").delete()
        }
        name
    }.getOrNull()

    private fun summary(a: OcrScanner.Analysis?): String = a?.result?.let { r ->
        "${r.screen} ${r.species?.id ?: "?"} PC ${r.cp ?: "?"}${if (r.cpInferred) "*" else ""} PS ${r.hp ?: "?"} " +
            "rápido ${r.fast ?: "-"} cargados ${r.charged.size} IV ${if (r.ivExact) "exactos" else "${r.candidates.size} opciones"}" +
            (r.appraisal?.let { " valoración ${it}" } ?: "") + (if (a.bars != null) " barras" else "") +
            (r.problem?.let { " problema: $it" } ?: "")
    } ?: "sin imagen"

    /**
     * Lee lo que hay en pantalla ahora (un toque en la burbuja). Antes de leer se cierra el cuadro anterior: ninguna
     * ventana propia queda en pantalla durante la captura ni mientras se toca el juego. Lo completo se guarda solo
     * (si está activado); el resultado aparece en el cuadro y en el centro de la burbuja.
     */
    fun scanNow() {
        log("toque en la burbuja (lejos de Pokémon GO: $away, cuadro: $panelWanted)")
        if (scanJob?.isActive == true) {
            log("escaneo anterior todavía en curso: se ignora el toque")
            vibrate(15)
            return
        }
        closeMenu()
        showPanel(false)
        _mark.value = null
        scanJob = lifecycleScope.launch {
            vibrate(25)
            _status.value = null
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
            var key: Int? = null
            when {
                a == null && timedOut -> _status.value = "La lectura tardó demasiado. Volver a tocar la burbuja."
                a == null -> _status.value = "Sin imagen de la pantalla todavía."
                a.result.screen == ScreenType.UNKNOWN -> {
                    // No era la ficha de un Pokémon: se avisa, sin cargarlo como falla ni mostrar el anterior.
                    container.scan.clearLatest()
                    _status.value = a.result.problem ?: "No es la ficha de un Pokémon."
                }
                else -> {
                    key = container.scan.submit(a, idx, parser)
                    // La valoración leída sola se empareja con la ficha (mismo PC y PS).
                    pendingAppraisal?.let { key = container.scan.submit(it, idx, parser) ?: key }
                }
            }
            val d = key?.let { k ->
                if (container.settings.state.value.autoSave) container.scan.settle(k, idx)
                else container.scan.drafts.value.firstOrNull { it.key == k }
            }
            log("estado: ${d?.status ?: "sin borrador"}" + (d?.missing?.let { " ($it)" } ?: ""))
            _mark.value = if (d?.saved == true) "✓" else "!"
            vibrate(if (d?.saved == true) 25 else 70)
            showPanel(true)
        }
    }

    /** GUARDAR desde el cuadro (con el guardado automático apagado). */
    fun save(key: Int) {
        lifecycleScope.launch {
            val d = container.scan.settle(key, container.scan.idx())
            _mark.value = if (d?.saved == true) "✓" else "!"
        }
    }

    private fun vibrate(ms: Long) {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
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
        val frame = withContext(Dispatchers.IO) { saveFrame(bmp) }
        return try {
            container.ocr.analyze(bmp, parser).also { a ->
                frame?.let { name -> withContext(Dispatchers.IO) { LiveTestLog.saveOcr(this@LiveScanService, name, a.lines, a.bars) } }
            }
        } catch (t: Throwable) {
            log("error al leer el fotograma: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            withContext(Dispatchers.Default) { bmp.recycle() }
        }
    }

    /**
     * Las ventanas seguras (FLAG_SECURE) no se leen, pero en la captura aparecen como un rectángulo NEGRO:
     * con el panel abierto arriba se tapaba el PC y el nombre. Se corren fuera de la pantalla, se espera un
     * fotograma limpio, se captura y se vuelven a su lugar.
     */
    private suspend fun <T> withOverlaysHidden(block: suspend () -> T): T {
        val views = overlays()
        views.forEach { (v, p) -> moveAway(v, p) }
        delay(300) // que la pantalla se redibuje sin la burbuja ni el panel
        return try {
            block()
        } finally {
            if (!away) views.forEach { (v, p) -> moveBack(v, p) }
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
                // Fuera del juego, o mientras un escaneo con la burbuja toca la pantalla, no se lee.
                if (away || scanJob?.isActive == true) continue
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

    private fun overlayParams(width: Int, height: Int, watchOutside: Boolean = false) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@LiveScanService)
        setViewTreeSavedStateRegistryOwner(this@LiveScanService)
        // Se quita y se vuelve a poner en cada captura: que no pierda su estado.
        setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@LiveScanService))
        setContent(content)
    }

    private fun showBubble() {
        if (bubble != null) return
        val dm = resources.displayMetrics
        val saved = container.settings.state.value
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            // La posición se recuerda entre sesiones (dentro de la pantalla).
            x = if (saved.bubbleX >= 0) saved.bubbleX.coerceAtMost(dm.widthPixels - dp(62)) else dm.widthPixels - dp(80)
            y = if (saved.bubbleY >= 0) saved.bubbleY.coerceAtMost(dm.heightPixels - dp(62)) else (dm.heightPixels * 0.35f).toInt()
        }
        bubbleParams = params
        bubble = composeView {
            LiveBubble(
                service = this,
                onDrag = { dx, dy ->
                    closeMenu()
                    params.x += dx.toInt(); params.y += dy.toInt()
                    bubble?.let { runCatching { wm.updateViewLayout(it, params) } }
                },
                onDragEnd = {
                    val (x, y) = params.x to params.y
                    container.settings.update { it.copy(bubbleX = x.coerceAtLeast(0), bubbleY = y.coerceAtLeast(0)) }
                },
            )
        }
        wm.addView(bubble, params)
    }

    /** Cuadro del resultado: a lo ancho con un margen, a la altura del modelo 3D (entre el PC y el nombre). */
    fun showPanel(show: Boolean) {
        log(if (show) "cuadro abierto" else "cuadro cerrado")
        panelWanted = show
        if (show && panel == null) {
            val dm = resources.displayMetrics
            val params = overlayParams(dm.widthPixels - dp(32), WindowManager.LayoutParams.WRAP_CONTENT).apply {
                x = dp(16)
                y = (dm.heightPixels * 0.17f).toInt()
            }
            panelParams = params
            val view = composeView { LiveResult(container = container, service = this) }
            panel = view
            wm.addView(view, params)
            // Lejos de Pokémon GO se crea igual, pero fuera de la pantalla (vuelve con la burbuja).
            if (away) view.post { moveAway(view, params) }
        } else if (!show && panel != null) {
            panel?.let { v -> homePosition.remove(v); runCatching { wm.removeView(v) } }
            panel = null
        }
        _panelVisible.value = show
    }

    /** Menú de la burbuja (mantener apretado), al costado de la burbuja que da al centro de la pantalla. */
    fun toggleMenu() {
        if (menu != null) { closeMenu(); return }
        // El toque que cerró el menú (fuera de él) puede ser el mismo que lo vuelve a pedir.
        if (System.currentTimeMillis() - menuClosedAt < 600) return
        val bp = bubbleParams ?: return
        val dm = resources.displayMetrics
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, watchOutside = true).apply {
            x = if (bp.x > dm.widthPixels / 2) (bp.x - dp(204)).coerceAtLeast(0) else bp.x + dp(70)
            y = bp.y
        }
        menuParams = params
        val view = composeView { LiveMenu(container = container, service = this) }
        view.setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false
        }
        menu = view
        wm.addView(view, params)
        vibrate(20)
        // Si no se elige nada, se cierra solo.
        lifecycleScope.launch { delay(6000); if (menu === view) closeMenu() }
    }

    private fun closeMenu() {
        val v = menu ?: return
        homePosition.remove(v)
        runCatching { wm.removeView(v) }
        menu = null
        menuClosedAt = System.currentTimeMillis()
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
        listOfNotNull(bubble, panel, menu).forEach { runCatching { wm.removeView(it) } }
        bubble = null; panel = null; menu = null
        container.scan.endLive()
        _mark.value = null
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
        /** Cómo terminó el último escaneo, en el centro de la burbuja: "✓" guardado, "!" falta algo. */
        private val _mark = MutableStateFlow<String?>(null)
        val mark: StateFlow<String?> = _mark.asStateFlow()

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
