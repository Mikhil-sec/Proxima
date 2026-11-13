package mu.proxima.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.VoiceCapture
import mu.proxima.app.ui.components.BackIcon
import mu.proxima.app.ui.components.Fact
import mu.proxima.app.ui.components.GuidedSteps
import mu.proxima.app.ui.components.LargeButton
import mu.proxima.app.ui.components.NearestHelp
import mu.proxima.app.ui.components.SeverityBadge
import mu.proxima.app.ui.components.SimLabel
import mu.proxima.app.ui.components.StateLabel
import mu.proxima.app.ui.components.Sym
import mu.proxima.app.ui.components.SymbolIcon
import mu.proxima.app.ui.components.criticalButtonColors
import mu.proxima.app.ui.incidentTitle
import mu.proxima.app.ui.label
import mu.proxima.app.ui.sym
import mu.proxima.app.ui.theme.container
import mu.proxima.app.ui.theme.fill
import mu.proxima.app.ui.theme.semantic
import mu.proxima.app.ui.theme.sizes
import mu.proxima.app.ui.theme.spacing
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.ProtocolCard
import mu.proxima.core.model.RawReport
import mu.proxima.core.model.TriageResult
import mu.proxima.core.pipeline.RuleTriageEngine

private sealed interface Stage {
    data object Capture : Stage
    data object Typing : Stage
    data class Review(val raw: RawReport, val result: TriageResult) : Stage
    data class Sent(val incident: Incident) : Stage
}

private const val AUTO_SEND_MS = 3_000

/**
 * Report flow: listen → show what the AI understood → auto-send after 3 s unless cancelled → "Alert sent".
 * The preview uses the same TriageEngine the pipeline uses, so what you see is what goes out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(graph: ProximaGraph, capture: VoiceCapture, nav: Nav, hint: IncidentType?, fromHold: Boolean) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var stage by remember { mutableStateOf<Stage>(Stage.Capture) }
    val reached = remember { mutableStateListOf<String>() }

    fun classify(text: String) = scope.launch {
        val raw = RawReport(text.trim(), System.currentTimeMillis(), graph.locate.selfLocation.value, hint)
        val result = runCatching { graph.triage.triage(raw) }.getOrElse { RuleTriageEngine().classify(raw) }
        stage = Stage.Review(raw, result)
    }

    fun send(raw: RawReport) = scope.launch {
        // Count the phones this alert was handed to directly.
        val watch = launch {
            withTimeoutOrNull(5_000) {
                graph.mesh.hops.collect { if (it.fromNodeId == graph.mesh.selfNodeId && it.toNodeId !in reached) reached.add(it.toNodeId) }
            }
        }
        // Runs on the graph scope so leaving the screen can never cancel an alert mid-send.
        val incident = graph.scope.async { graph.pipeline.report(raw) }.await()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        stage = Stage.Sent(incident)
        watch.join()
    }

    fun leave() { capture.cancel(); nav.reset(Screen.Home) }

    LaunchedEffect(Unit) { if (!fromHold) capture.start() }
    LaunchedEffect(capture.phase) {
        if (stage != Stage.Capture) return@LaunchedEffect
        when (capture.phase) {
            VoiceCapture.Phase.Done -> classify(capture.transcript)
            VoiceCapture.Phase.Failed -> stage = Stage.Typing
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (stage is Stage.Sent) "" else "Report") },
                navigationIcon = { if (stage !is Stage.Sent) BackIcon(::leave) },
                actions = {
                    // What kind of report this is: a label, not a control (nothing to tap).
                    if (hint != null && stage !is Stage.Sent) {
                        StateLabel(
                            hint.label(),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(MaterialTheme.spacing.lg))
                    }
                    // "Passed to 3 phones" is simulated until the demo runs with simulation off.
                    if (stage is Stage.Sent && graph.simulation) {
                        SimLabel()
                        Spacer(Modifier.width(MaterialTheme.spacing.lg))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = MaterialTheme.spacing.xl).imePadding()) {
            when (val s = stage) {
                Stage.Capture -> CaptureView(
                    capture, hint,
                    onDone = capture::stop,
                    onType = { capture.cancel(); stage = Stage.Typing },
                    onSkip = hint?.let { h -> { capture.cancel(); classify(defaultText(h)) } },
                )
                Stage.Typing -> TypingView(capture.error, hint, onCheck = ::classify)
                is Stage.Review -> ReviewView(s.raw, s.result, onSend = { send(s.raw) }, onCancel = ::leave)
                is Stage.Sent -> SentView(
                    graph, s.incident, reached.size,
                    onDone = { nav.reset(Screen.Home) },
                    onAlerts = { nav.reset(Screen.Home); nav.go(Screen.Feed) },
                )
            }
        }
    }
}

private fun defaultText(hint: IncidentType) = when (hint) {
    IncidentType.MEDICAL -> "Medical help needed"
    IncidentType.HAZARD -> "Hazard, people at risk"
    IncidentType.LOST_PERSON -> "Lost person"
    else -> hint.label()
}

private fun promptFor(hint: IncidentType?) = when (hint) {
    IncidentType.LOST_PERSON -> "Who is missing? Say their age and clothes."
    IncidentType.MEDICAL -> "What happened to them?"
    IncidentType.HAZARD -> "What's the danger, and where?"
    else -> "Tell us what's happening and where."
}

@Composable
private fun ColumnScope.CaptureView(
    capture: VoiceCapture,
    hint: IncidentType?,
    onDone: () -> Unit,
    onType: () -> Unit,
    onSkip: (() -> Unit)?,
) {
    val listening = capture.phase == VoiceCapture.Phase.Listening
    Text(
        if (listening) "Listening" else "Working out what's needed",
        style = MaterialTheme.typography.labelLarge,
        color = if (listening) MaterialTheme.semantic.critical else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(MaterialTheme.spacing.sm))
    Text(promptFor(hint), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(MaterialTheme.spacing.xxxl))
    Waveform(capture.levels, active = listening, modifier = Modifier.fillMaxWidth().height(MaterialTheme.sizes.touchTarget * 2))
    Spacer(Modifier.height(MaterialTheme.spacing.xxl))
    Text(
        capture.transcript.ifBlank { if (listening) "Speak now. Any language is fine." else "" },
        style = MaterialTheme.typography.headlineSmall,
        color = if (capture.transcript.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
    )
    if (listening) LargeButton("Done talking", onDone, sym = Sym.Done)
    Row(Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onType) {
            SymbolIcon(Sym.Keyboard, null, size = MaterialTheme.sizes.iconSmall)
            Spacer(Modifier.width(MaterialTheme.spacing.sm))
            Text("Type instead")
        }
        if (onSkip != null) TextButton(onClick = onSkip) { Text("Send without describing") }
    }
}

/** Live voice: mirrored bars, newest on the right. A flat line when silent so it never looks broken. */
@Composable
private fun Waveform(levels: List<Float>, active: Boolean, modifier: Modifier) {
    val activeColor = MaterialTheme.semantic.critical
    val idleColor = MaterialTheme.colorScheme.outlineVariant
    val gapDp = MaterialTheme.spacing.xs
    Canvas(modifier.semantics { contentDescription = if (active) "Voice level" else "Voice captured" }) {
        val n = 40
        val gap = gapDp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val mid = size.height / 2
        for (i in 0 until n) {
            val idx = levels.size - n + i
            val lv = if (idx >= 0) levels[idx] else 0f
            val h = (w + lv * (size.height - w)).coerceAtMost(size.height)
            val color = if (active) activeColor.copy(alpha = 0.35f + 0.65f * lv) else idleColor
            drawRoundRect(color, Offset(i * (w + gap), mid - h / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

@Composable
private fun ColumnScope.TypingView(error: String?, hint: IncidentType?, onCheck: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    // Scrolls, so the button stays pinned above the keyboard even with a long prompt or 130% font.
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        if (error != null) {
            // A voice fallback, not an emergency: a quiet tonal note, so the critical red stays reserved for SOS.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(Modifier.padding(MaterialTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(Sym.Mic, null)
                    Spacer(Modifier.width(MaterialTheme.spacing.md))
                    // The voice engine's reason already says what to do ("Offline voice not available. Type the report instead.").
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
        }
        Text(promptFor(hint), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(MaterialTheme.spacing.xxl))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(280) },
            label = { Text("What's happening") },
            placeholder = { Text("For example: man collapsed near the temple steps") },
            supportingText = { Text("Say where, if you can. No names.") },
            minLines = 3,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(MaterialTheme.spacing.md))
    LargeButton("Check alert", { onCheck(text) }, enabled = text.isNotBlank())
    Spacer(Modifier.height(MaterialTheme.spacing.lg))
}

@Composable
private fun ColumnScope.ReviewView(raw: RawReport, result: TriageResult, onSend: () -> Unit, onCancel: () -> Unit) {
    val countdown = remember { Animatable(1f) }
    var sending by remember { mutableStateOf(false) }
    fun fire() { if (!sending) { sending = true; onSend() } }
    LaunchedEffect(Unit) {
        countdown.animateTo(0f, tween(AUTO_SEND_MS, easing = LinearEasing)) // a countdown timer: linear by definition
        fire()
    }
    val secondsLeft = kotlin.math.ceil(countdown.value * AUTO_SEND_MS / 1000f).toInt()
    val (fill, onFill) = result.severity.fill()
    val title = incidentTitle(result.type, result.subtype)

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("This is what we'll send", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(MaterialTheme.spacing.lg))
        Surface(color = fill, contentColor = onFill, shape = MaterialTheme.shapes.small) {
            Text(
                result.severity.label(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
            )
        }
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (title != result.type.label()) {
            Text(result.type.label(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(MaterialTheme.spacing.xxl))
        Text("“${raw.text}”", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(MaterialTheme.spacing.lg))
        result.landmark?.let { Fact("Where", it) }
        result.peopleCount?.let { Fact("People", it.toString()) }
        Fact("Sorted by", result.source.label())
        Fact("Confidence", "${(result.confidence * 100).toInt()}%")
    }

    Text(if (sending) "Sending" else "Sending in $secondsLeft", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(MaterialTheme.spacing.sm))
    LinearProgressIndicator(
        progress = { countdown.value },
        color = MaterialTheme.semantic.critical,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(MaterialTheme.spacing.lg))
    LargeButton(if (sending) "Sending" else "Send alert now", ::fire, sym = Sym.Sos, colors = criticalButtonColors(), enabled = !sending)
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) { Text("Cancel") }
}

@Composable
private fun ColumnScope.SentView(graph: ProximaGraph, incident: Incident, reached: Int, onDone: () -> Unit, onAlerts: () -> Unit) {
    var protocol by remember { mutableStateOf<ProtocolCard?>(null) }
    LaunchedEffect(incident.protocolId) { protocol = incident.protocolId?.let { runCatching { graph.protocols.byId(it) }.getOrNull() } }
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val (container, content) = incident.severity.container()

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        SymbolIcon(Sym.Done, description = "Alert sent", tint = MaterialTheme.semantic.safe, size = MaterialTheme.sizes.iconLarge)
        Spacer(Modifier.height(MaterialTheme.spacing.lg))
        Text("Alert sent", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Text(
            when (reached) {
                0 -> "It will pass to the next phone that comes in range."
                1 -> "Passed to 1 phone nearby. It keeps spreading from there."
                else -> "Passed to $reached phones nearby. It keeps spreading from there."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.xxl))
        ListItem(
            headlineContent = { Text(incidentTitle(incident.type, incident.subtype), style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(incident.summary) },
            leadingContent = { SeverityBadge(incident.type.sym(), container, content, null) },
        )
        Spacer(Modifier.height(MaterialTheme.spacing.lg))
        NearestHelp(graph, incident.location ?: me, incident.type.helpCategories)
        protocol?.let { card ->
            Spacer(Modifier.height(MaterialTheme.spacing.xxl))
            Text("What to do now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(MaterialTheme.spacing.xs))
            GuidedSteps(graph, card)
        }
    }
    LargeButton("Done", onDone)
    TextButton(onClick = onAlerts, modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) { Text("See all alerts") }
}
