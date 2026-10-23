package com.esmeraldago.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.theme.gbaStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Compara la legibilidad de fuentes pixel candidatas (dígitos 5/S, 2/Z/8, B/8). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FontCompareTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compareFonts() {
        // FONT_DIR permite comparar candidatas fuera del repo.
        val dir = System.getenv("FONT_DIR")?.let(::File) ?: File("src/test/fontcandidates")
        val fonts = dir.listFiles { f -> f.extension == "ttf" }!!.sortedBy { it.name }
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFFF8F8F8)).padding(8.dp)) {
                fonts.forEach { f ->
                    val fam = FontFamily(Font(f))
                    Text(f.nameWithoutExtension, color = Color(0xFF3868C8), fontSize = 9.sp)
                    Text("PC 3455 · IV 15/12/8 · 5S 8B 0O 1lI 2Z", style = gbaStyle(16.sp).copy(fontFamily = fam))
                    Text("¿Qué atrapar? ¡Subí a Machamp! Nº150", style = gbaStyle(16.sp).copy(fontFamily = fam))
                    Text("Débil a: Eléctrico, Planta · DPS 49.2 TDO 1909 · VU UV", style = gbaStyle(12.sp).copy(fontFamily = fam))
                }
            }
        }
        rule.waitForIdle()
        var bmp: Bitmap? = null
        rule.runOnUiThread {
            val v = rule.activity.window.decorView.rootView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        }
        File("build/screens").mkdirs()
        File("build/screens/fonts_compare.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

