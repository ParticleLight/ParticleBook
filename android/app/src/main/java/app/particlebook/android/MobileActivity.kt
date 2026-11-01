package app.particlebook.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.particlebook.android.ui.ParticleBookApp

/**
 * Compose + Material 3 新门面。书架 / 设置两屏与 PDF 阅读器都在这里起。
 * 旧 WebView 阅读器（EPUB/HTML）仍由 MainActivity 承担，可从中跳转。
 */
class MobileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ParticleBookApp() }
    }
}
