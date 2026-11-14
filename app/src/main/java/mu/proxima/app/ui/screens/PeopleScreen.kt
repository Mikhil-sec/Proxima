package mu.proxima.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.FriendLink
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.components.BackIcon
import mu.proxima.app.ui.components.LargeButton
import mu.proxima.app.ui.components.Sym
import mu.proxima.app.ui.components.SymbolIcon
import mu.proxima.app.ui.distanceLabel
import mu.proxima.app.ui.label
import mu.proxima.app.ui.theme.spacing
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.Peer

/**
 * My people (friend find, #38): pair before you split up; if one of you can't call for help, the others see the
 * 25 m square their phone was last heard in. Nothing is broadcast to look for someone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(graph: ProximaGraph, friends: FriendLink, nav: Nav) {
    val peers by graph.mesh.peers.collectAsStateWithLifecycle()
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val now = rememberNow()
    var pairWith by remember { mutableStateOf<Peer?>(null) }
    var manage by remember { mutableStateOf<String?>(null) }
    val rows = ListItemDefaults.colors(containerColor = Color.Transparent)
    // Phones in direct range that aren't already one of my people.
    val nearby = peers.filter { it.direct && it.publicKey != null && it.friendLabel == null }

    Scaffold(topBar = { TopAppBar(title = { Text("My people") }, navigationIcon = { BackIcon(nav::back) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                "Pair with the people you came with. If one of you can't call for help, the others can see where " +
                    "their phone was last heard. Nobody else can.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xl),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.lg))

            if (friends.labels.isEmpty() && friends.waiting.isEmpty()) {
                Text(
                    "Nobody paired yet. Stand next to them, both open My people, and pick each other below.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xl),
                )
            }
            friends.labels.forEach { label ->
                val heard = friends.heard(label, peers)
                val distance = heard?.location?.let { there -> me?.let { GeoMath.distanceM(it, there) } }
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Options for $label") { manage = label },
                    headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            when {
                                heard == null -> "Not heard yet. Their phone shows up once it is in reach of the mesh."
                                heard.location == null -> "Phone heard ${heardAgo(heard.lastSeenMs, now)}, no location yet"
                                else -> "Phone heard ${heardAgo(heard.lastSeenMs, now)}. Tap to find them."
                            },
                        )
                    },
                    leadingContent = {
                        SymbolIcon(
                            Sym.Person, null,
                            tint = if (heard != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = distance?.let { d -> { Text(distanceLabel(d), style = MaterialTheme.typography.titleMedium) } },
                    colors = rows,
                )
                HorizontalDivider()
            }

            friends.waiting.forEach { label ->
                ListItem(
                    headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("Waiting for them to tap \"The numbers match\" on their phone.") },
                    leadingContent = { SymbolIcon(Sym.Time, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = rows,
                )
                HorizontalDivider()
            }

            Spacer(Modifier.height(MaterialTheme.spacing.xxl))
            Text("Add someone next to you", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xl))
            if (nearby.isEmpty()) {
                Text(
                    "No phones in range right now. Ask them to open Proxima and stand close.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xl, vertical = MaterialTheme.spacing.sm),
                )
            }
            nearby.forEach { peer ->
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Pair with this phone") { pairWith = peer },
                    headlineContent = { Text("Phone ${friends.codeFor(peer) ?: peer.shortCode}", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("${peer.tier.label()}. Pick the phone showing the same number.") },
                    leadingContent = { SymbolIcon(Sym.Mesh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = rows,
                )
            }
            Spacer(Modifier.height(MaterialTheme.spacing.xxl))
        }
    }

    pairWith?.let { peer ->
        val key = peer.publicKey
        val code = friends.codeFor(peer)
        if (key == null || code == null) { pairWith = null; return@let }
        PairSheet(
            code = code,
            intro = "Check their screen. Both phones must show this number.",
            taken = friends.labels,
            onConfirm = { label -> friends.confirm(key, label); pairWith = null },
            onDismiss = { pairWith = null },
        )
    }

    manage?.let { label ->
        val heard = friends.heard(label, peers)
        ModalBottomSheet(onDismissRequest = { manage = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.xxl)) {
                Text(label, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                Text(
                    if (heard?.location != null) "Their phone was last heard ${heardAgo(heard.lastSeenMs, now)}, within a 25 m square."
                    else "Their phone hasn't been heard with a location yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.xxl))
                LargeButton(
                    "Show me the way", { manage = null; nav.go(Screen.FriendRadar(label)) },
                    sym = Sym.Way, enabled = heard?.location != null,
                )
                TextButton(
                    onClick = { friends.remove(label); manage = null },
                    modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm),
                ) { Text("Remove from my people") }
                Spacer(Modifier.height(MaterialTheme.spacing.lg))
            }
        }
    }
}

/** Radar to a paired friend's last-heard cell. */
@Composable
fun FriendRadarScreen(graph: ProximaGraph, friends: FriendLink, nav: Nav, label: String) {
    val target = remember(label) { friends.track(label) } ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val fixes = remember(label) { graph.locate.track(target) }
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    RadarView(
        "Finding $label", fixes, "Where their phone was last heard, to within 25 m.",
        doneLabel = "Found them", onDone = nav::back, onBack = nav::back, ownFix = me != null,
    )
}

/**
 * The pairing step on either phone: one number to compare, a private nickname, one action.
 * Shown from My people (you picked a phone) and on top of any screen when another phone picked you.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairSheet(code: String, intro: String, taken: List<String>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val label = name.trim()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.xxl).imePadding(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text("Pair phones", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(MaterialTheme.spacing.sm))
            Text(intro, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
            Text(code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                label = { Text("What do you call them?") },
                supportingText = {
                    Text(if (label in taken) "You already use that name. Pick another." else "Stays on this phone. For example: Brother.")
                },
                isError = label in taken,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
            LargeButton("The numbers match", { onConfirm(label) }, sym = Sym.Done, enabled = label.isNotEmpty() && label !in taken)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) {
                Text("They don't match")
            }
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
        }
    }
}

/** "just now" / "45s ago" / "3m ago" (ageLabel says "now", which reads badly after "heard"). */
private fun heardAgo(atMs: Long, nowMs: Long): String = ageLabel(atMs, nowMs).let { if (it == "now") "just now" else "$it ago" }

