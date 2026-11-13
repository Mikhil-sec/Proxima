package mu.proxima.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.VoiceCapture
import mu.proxima.app.ui.components.BackButton
import mu.proxima.app.ui.components.Glyph
import mu.proxima.app.ui.components.GlyphIcon
import mu.proxima.app.ui.components.PrimaryAction
import mu.proxima.app.ui.components.QuietAction
import mu.proxima.app.ui.components.Rail
import mu.proxima.app.ui.label
import mu.proxima.app.ui.subtypeLabel
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.color
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.app.ui.theme.mono
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

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stage !is Stage.Sent) BackButton(::leave)
            Spacer(Modifier.weight(1f))
            if (hint != null && stage !is Stage.Sent) HintTag(hint)
        }
        when (val s = stage) {
            Stage.Capture -> CaptureView(
                capture, hint,
                onDone = capture::stop,
                onType = { capture.cancel(); stage = Stage.Typing },
                onSkip = hint?.let { h -> { capture.cancel(); classify(defaultText(h)) } },
            )
            Stage.Typing -> TypingView(capture.error, hint, onCheck = ::classify)
            is Stage.Review -> ReviewView(s.raw, s.result, onSend = { send(s.raw) }, onCancel = ::leave)
            is Stage.Sent -> SentView(graph, s.incident, reached.size, onDone = { nav.reset(Screen.Home) }, onAlerts = { nav.reset(Screen.Home); nav.go(Screen.Feed) })
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
    IncidentType.LOST_PERSON -> "Describe who is missing: age and clothes. No names."
    IncidentType.MEDICAL -> "What happened to them?"
    IncidentType.HAZARD -> "What's the danger, and where?"
    else -> "Tell us what's happening and where."
}

@Composable
private fun HintTag(hint: IncidentType) {
    val glyph = when (hint) {
        IncidentType.MEDICAL -> Glyph.Medical
        IncidentType.HAZARD -> Glyph.Hazard
        else -> Glyph.Person
    }
    Row(
        Modifier.border(1.dp, Px.Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, Px.Text, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(hint.label(), style = grotesk(14, FontWeight.Medium))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CaptureView(
    capture: VoiceCapture,
    hint: IncidentType?,
    onDone: () -> Unit,
    onType: () -> Unit,
    onSkip: (() -> Unit)?,
) {
    val listening = capture.phase == VoiceCapture.Phase.Listening
    Spacer(Modifier.height(12.dp))
    Text(if (listening) "Listening" else "Understanding", style = grotesk(16, FontWeight.Medium, if (listening) Px.Crimson else Px.Dim))
    Spacer(Modifier.height(8.dp))
    Text(promptFor(hint), style = grotesk(28))
    Spacer(Modifier.height(32.dp))
    Waveform(capture.levels, active = listening, modifier = Modifier.fillMaxWidth().height(112.dp))
    Spacer(Modifier.height(28.dp))
    Text(
        capture.transcript.ifBlank { if (listening) "Speak now. Any language is fine." else "" },
        style = grotesk(24, FontWeight.Medium, if (capture.transcript.isBlank()) Px.Dim else Px.Text),
        modifier = Modifier.weight(1f),
    )
    if (listening) PrimaryAction("Done talking", onDone, glyph = Glyph.Check)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        QuietAction("Type instead", onType)
        if (onSkip != null) QuietAction("Send without describing", onSkip, color = Px.Dim)
    }
    Spacer(Modifier.height(8.dp))
}

/** Live voice: mirrored bars, newest on the right. Flat line when silent so it never looks broken. */
@Composable
private fun Waveform(levels: List<Float>, active: Boolean, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = if (active) "Voice level" else "Voice captured" }) {
        val n = 40
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val mid = size.height / 2
        for (i in 0 until n) {
            val idx = levels.size - n + i
            val lv = if (idx >= 0) levels[idx] else 0f
            val h = (4.dp.toPx() + lv * (size.height - 4.dp.toPx())).coerceAtMost(size.height)
            val color = if (active) Px.Crimson.copy(alpha = 0.35f + 0.65f * lv) else Px.Dim.copy(alpha = 0.5f)
            drawRoundRect(color, Offset(i * (w + gap), mid - h / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TypingView(error: String?, hint: IncidentType?, onCheck: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Spacer(Modifier.height(12.dp))
    if (error != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Mic, Px.Amber, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("Microphone didn't work. Type what's happening instead.", style = grotesk(15, FontWeight.Medium, Px.Amber))
        }
        Spacer(Modifier.height(12.dp))
    }
    Text(promptFor(hint), style = grotesk(28))
    Spacer(Modifier.height(24.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Px.Surface)
            .padding(20.dp),
    ) {
        if (text.isEmpty()) Text("For example: man collapsed near the temple steps", style = grotesk(20, FontWeight.Medium, Px.Dim))
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(280) },
            textStyle = grotesk(20, FontWeight.Medium),
            cursorBrush = SolidColor(Px.Text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.weight(1f))
    PrimaryAction("Check alert", { onCheck(text) }, enabled = text.isNotBlank())
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReviewView(
    raw: RawReport,
    result: TriageResult,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val countdown = remember { Animatable(1f) }
    var sending by remember { mutableStateOf(false) }
    fun fire() { if (!sending) { sending = true; onSend() } }
    LaunchedEffect(Unit) {
        countdown.animateTo(0f, tween(AUTO_SEND_MS, easing = LinearEasing))
        fire()
    }
    val secondsLeft = (countdown.value * AUTO_SEND_MS / 1000f).let { kotlin.math.ceil(it).toInt() }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        Text("This is what we'll send", style = grotesk(16, FontWeight.Medium, Px.Dim))
        Spacer(Modifier.height(20.dp))
        Row(Modifier.height(IntrinsicSize.Min)) {
            Rail(result.severity.color(), Modifier.fillMaxHeight())
            Spacer(Modifier.width(16.dp))
            Column {
                Text(result.type.label(), style = grotesk(16, FontWeight.Medium, Px.Dim))
                Text(subtypeLabel(result.subtype) ?: result.type.label(), style = grotesk(32))
                Spacer(Modifier.height(6.dp))
                Text(result.severity.label(), style = grotesk(18, FontWeight.Bold, result.severity.color()))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("“${raw.text}”", style = grotesk(18, FontWeight.Medium, Px.Text))
        result.landmark?.let {
            Spacer(Modifier.height(16.dp))
            Fact("Where", it)
        }
        result.peopleCount?.let { Fact("People", it.toString()) }
        Spacer(Modifier.height(16.dp))
        Fact("Sorted by", result.source.label())
        Fact("Confidence", "${(result.confidence * 100).toInt()}%")
    }

    // Countdown: a draining bar and a number. The send happens on its own; Cancel is always one tap away.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (sending) "Sending" else "Sending in", style = grotesk(16, FontWeight.Medium, Px.Dim))
        Spacer(Modifier.weight(1f))
        if (!sending) Text("$secondsLeft", style = mono(20, FontWeight.Bold, Px.Text))
    }
    Spacer(Modifier.height(8.dp))
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        drawRoundRect(Px.Line, cornerRadius = CornerRadius(2.dp.toPx()))
        drawRoundRect(Px.Crimson, size = Size(size.width * countdown.value, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
    }
    Spacer(Modifier.height(16.dp))
    PrimaryAction("Send alert now", ::fire, color = Px.Crimson, enabled = !sending)
    QuietAction("Cancel", onCancel, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
}

/** Label on the left, value on the right: aligned facts instead of dot-joined meta strings. */
@Composable
fun Fact(label: String, value: String, valueMono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = grotesk(15, FontWeight.Medium, Px.Dim), modifier = Modifier.width(112.dp))
        Text(value, style = if (valueMono) mono(15, color = Px.Text) else grotesk(15, FontWeight.Medium))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SentView(
    graph: ProximaGraph,
    incident: Incident,
    reached: Int,
    onDone: () -> Unit,
    onAlerts: () -> Unit,
) {
    var protocol by remember { mutableStateOf<ProtocolCard?>(null) }
    LaunchedEffect(incident.protocolId) { protocol = incident.protocolId?.let { graph.protocols.byId(it) } }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(24.dp))
        GlyphIcon(Glyph.Check, Px.Green, 56.dp, description = "Alert sent")
        Spacer(Modifier.height(20.dp))
        Text("Alert sent", style = grotesk(34))
        Spacer(Modifier.height(8.dp))
        Text(
            when (reached) {
                0 -> "It will pass to the next phone that comes in range."
                1 -> "Passed to 1 phone nearby. It keeps spreading from there."
                else -> "Passed to $reached phones nearby. It keeps spreading from there."
            },
            style = grotesk(18, FontWeight.Medium, Px.Dim),
        )
        Spacer(Modifier.height(28.dp))
        Row(Modifier.height(IntrinsicSize.Min)) {
            Rail(incident.severity.color(), Modifier.fillMaxHeight())
            Spacer(Modifier.width(16.dp))
            Column {
                Text(subtypeLabel(incident.subtype) ?: incident.type.label(), style = grotesk(20))
                Text(incident.summary, style = grotesk(15, FontWeight.Medium, Px.Dim))
            }
        }
        protocol?.let { card ->
            Spacer(Modifier.height(28.dp))
            Text("What to do now", style = grotesk(22))
            Spacer(Modifier.height(12.dp))
            card.steps.forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 8.dp)) {
                    Text("${i + 1}", style = mono(18, FontWeight.Bold, Px.Text), modifier = Modifier.width(32.dp))
                    Text(step.text, style = grotesk(19, FontWeight.Medium))
                }
            }
            Text("Source: ${card.source}", style = grotesk(13, FontWeight.Medium, Px.Dim))
        }
    }
    PrimaryAction("Done", onDone)
    QuietAction("See all alerts", onAlerts, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
}
