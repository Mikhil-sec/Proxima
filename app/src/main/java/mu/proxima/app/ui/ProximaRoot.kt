package mu.proxima.app.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.components.rememberReducedMotion
import mu.proxima.app.ui.screens.BootScreen
import mu.proxima.app.ui.screens.CommandCenterScreen
import mu.proxima.app.ui.screens.DevSheet
import mu.proxima.app.ui.screens.FeedScreen
import mu.proxima.app.ui.screens.FinderScreen
import mu.proxima.app.ui.screens.FoundAlertScreen
import mu.proxima.app.ui.screens.RadarScreen
import mu.proxima.app.ui.screens.HomeScreen
import mu.proxima.app.ui.screens.ReportScreen
import mu.proxima.app.ui.theme.Px
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.IncidentType

sealed interface Screen {
    data object Boot : Screen
    data object Home : Screen
    /** [fromHold]: the SOS ring is already listening; don't start a second capture. */
    data class Report(val hint: IncidentType? = null, val fromHold: Boolean = false) : Screen
    data object Feed : Screen
    data object Finder : Screen
    /** Guardian: someone is with your child ([lostId] = your LOST_PERSON incident). */
    data class Found(val lostId: String) : Screen
    data class Radar(val lostId: String) : Screen
    data object Command : Screen
}

/** Plain back stack. ~7 screens don't justify navigation-compose + the serialization plugin in :app. */
class Nav(private val stack: SnapshotStateList<Screen>) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    fun go(screen: Screen) { stack.add(screen) }
    fun back() { if (canGoBack) stack.removeAt(stack.lastIndex) }
    /** Replace the whole stack, e.g. after boot or when an alert has been sent. */
    fun reset(screen: Screen) { stack.clear(); stack.add(screen) }
}

fun missingPermissions(context: Context, required: List<String>): List<String> =
    required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

@Composable
fun ProximaRoot(graph: ProximaGraph) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val uiScope = rememberCoroutineScope()
    val capture = remember { VoiceCapture(graph.voice, uiScope) }
    val lostFound = remember { LostFound(graph) }

    val homeScreen = if (graph.capabilities.tier == DeviceTier.COMMAND_CENTER) Screen.Command else Screen.Home
    val needsBoot = remember { missingPermissions(context, graph.requiredPermissions).isNotEmpty() }
    val stack = remember { mutableStateListOf(if (needsBoot) Screen.Boot else homeScreen) }
    val nav = remember { Nav(stack) }
    var ready by remember { mutableStateOf(!needsBoot) }
    var devOpen by remember { mutableStateOf(false) }

    // Engines start once permissions are settled. They run on the graph scope, not the UI's.
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        graph.pipeline.start()
        graph.scope.launch { graph.mesh.start() }
        graph.scope.launch { graph.locate.start() }
    }

    // Haptic: a new phone joined the mesh.
    LaunchedEffect(Unit) {
        var last = 0
        graph.mesh.peers.collect { peers ->
            if (peers.size > last && last > 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            last = peers.size
        }
    }

    // Guardian: the first sealed notice about our lost report interrupts whatever is on screen.
    LaunchedEffect(Unit) {
        graph.pipeline.foundNotices.collect { notice ->
            if (lostFound.onNotice(notice)) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                nav.go(Screen.Found(notice.tagId))
            }
        }
    }

    BackHandler(enabled = nav.canGoBack) {
        if (nav.current is Screen.Report) capture.cancel()
        nav.back()
    }

    Box(Modifier.fillMaxSize().background(Px.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        AnimatedContent(
            targetState = nav.current,
            transitionSpec = {
                if (reducedMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 }) togetherWith fadeOut(tween(120))
            },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.Boot -> BootScreen(graph, onDone = { ready = true; nav.reset(homeScreen) }, onDev = { devOpen = true })
                Screen.Home -> HomeScreen(graph, capture, lostFound, nav, onDev = { devOpen = true })
                is Screen.Report -> ReportScreen(graph, capture, nav, screen.hint, screen.fromHold)
                Screen.Feed -> FeedScreen(graph, lostFound, nav)
                Screen.Finder -> FinderScreen(graph, lostFound, nav)
                is Screen.Found -> FoundAlertScreen(graph, lostFound, nav, screen.lostId)
                is Screen.Radar -> RadarScreen(graph, lostFound, nav, screen.lostId)
                Screen.Command -> CommandCenterScreen(graph, onDev = { devOpen = true })
            }
        }
        if (devOpen) DevSheet(graph, nav, onDismiss = { devOpen = false })
    }
}
