package com.esmeraldago.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.esmeraldago.app.ui.home.SplashScreen
import com.esmeraldago.app.ui.theme.EmeraldTheme
import com.esmeraldago.app.ui.theme.LocalAnimations
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Pantalla de carga (en los tests no hay red: se ve sin el sprite de Rayquaza). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SplashScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun splash() {
        rule.setContent {
            CompositionLocalProvider(LocalAnimations provides false) {
                EmeraldTheme { SplashScreen(ready = false, status = "Cargando datos del juego…", onDone = {}) }
            }
        }
        rule.waitForIdle()
        var bmp: Bitmap? = null
        rule.runOnUiThread {
            val v = rule.activity.window.decorView.rootView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        }
        File("build/screens").mkdirs()
        File("build/screens/splash.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
