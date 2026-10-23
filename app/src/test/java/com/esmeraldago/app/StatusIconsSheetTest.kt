package com.esmeraldago.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esmeraldago.app.ui.common.LuckyIcon
import com.esmeraldago.app.ui.common.PurifiedIcon
import com.esmeraldago.app.ui.common.ShadowIcon
import com.esmeraldago.app.ui.common.ShinyIcon
import com.esmeraldago.app.ui.theme.GbaText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Lámina de los íconos de estado a los tamaños en que se usan (12–24 dp) y ampliados. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h500dp-xxhdpi")
class StatusIconsSheetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sheet() {
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFFF8F8F8)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(12, 18, 24, 64).forEach { s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GbaText("$s dp", size = 12.sp)
                        ShadowIcon(s.dp); PurifiedIcon(s.dp); ShinyIcon(s.dp); LuckyIcon(s.dp)
                    }
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
        File("build/screens/status_icons.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
