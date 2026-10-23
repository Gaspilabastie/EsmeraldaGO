package com.esmeraldago.app.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Captura de pantalla vía MediaProjection: un VirtualDisplay espeja la pantalla en un ImageReader.
 * El display solo produce fotogramas cuando la pantalla cambia, así que se guarda una copia del
 * último para poder escanear una pantalla quieta.
 */
class ScreenCapturer(context: Context, private val projection: MediaProjection) {
    private val thread = HandlerThread("esmeraldago-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val width: Int
    private val height: Int
    private val reader: ImageReader
    private val display: VirtualDisplay
    private var lastFrame: Bitmap? = null
    private val lock = Any()

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dpi: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            width = b.width(); height = b.height()
            dpi = context.resources.displayMetrics.densityDpi
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            width = m.widthPixels; height = m.heightPixels; dpi = m.densityDpi
        }
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> copyLatest(r) }, handler)
        display = projection.createVirtualDisplay(
            "esmeraldago-scan", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    /** Copia el fotograma más nuevo (descartando los intermedios). */
    private fun copyLatest(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        image.use { img ->
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val exact = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            synchronized(lock) {
                lastFrame?.recycle()
                lastFrame = exact
            }
        }
    }

    /** Copia del fotograma actual de la pantalla (el que la llama la recicla). */
    fun grab(): Bitmap? = synchronized(lock) { lastFrame?.copy(Bitmap.Config.ARGB_8888, false) }

    fun release() {
        runCatching { display.release() }
        runCatching { reader.close() }
        synchronized(lock) { lastFrame?.recycle(); lastFrame = null }
        thread.quitSafely()
    }
}
