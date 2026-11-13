package mu.proxima.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import mu.proxima.app.ui.ProximaRoot
import mu.proxima.app.ui.theme.ProximaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge; bar icons follow the system light/dark theme, like the app.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as ProximaApp).graph
        // The manifest handles config changes in place (dark mode at sunset, keyboard, tablet rotation): the back stack
        // lives in Compose memory, so a recreation would throw a half-written report away.
        // Phones are portrait-locked (one-handed, panicking user); the tablet Command Center rotates freely.
        if (!graph.capabilities.isLargeScreen) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContent { ProximaTheme { ProximaRoot(graph) } }
    }
}
