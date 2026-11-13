package mu.proxima.app.ui

import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.FixSource
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain words a stressed person understands. Strings live here so FR / Kreol can replace them in one place. */

fun IncidentType.label(): String = when (this) {
    IncidentType.LOST_PERSON -> "Lost person"
    IncidentType.SIGHTING -> "Possible sighting"
    IncidentType.FOUND -> "Person found"
    IncidentType.MEDICAL -> "Medical emergency"
    IncidentType.HAZARD -> "Hazard"
    IncidentType.ALL_CLEAR -> "All clear"
}

fun Severity.label(): String = when (this) {
    Severity.CRITICAL -> "Critical"
    Severity.HIGH -> "Urgent"
    Severity.MEDIUM -> "Needs attention"
    Severity.LOW -> "Low"
}

private val subtypeNames = mapOf(
    "CARDIAC_ARREST" to "Not breathing",
    "CROWD_CRUSH" to "Crowd crush risk",
    "FIRE" to "Fire or smoke",
    "UNCONSCIOUS" to "Unconscious person",
    "SEIZURE" to "Seizure",
    "BLEEDING" to "Bleeding",
    "CHEST_PAIN" to "Chest pain",
    "HEAT_EXHAUSTION" to "Heat exhaustion",
    "FRACTURE" to "Possible broken bone",
    "ELECTRICAL" to "Electrical danger",
    "FALLEN_BRANCH" to "Fallen branch",
    "FLOOD" to "Flooding",
    "CHILD" to "Child",
)

/** "CARDIAC_ARREST" → "Not breathing". Unknown codes are made readable rather than shown raw. */
fun subtypeLabel(code: String?): String? = code?.let {
    subtypeNames[it] ?: it.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

fun TriageSource.label(): String = when (this) {
    TriageSource.ON_DEVICE_LLM -> "On-device AI"
    TriageSource.OFFLOADED_LLM -> "AI on a nearby phone"
    TriageSource.RULES -> "Keyword rules"
    TriageSource.MANUAL -> "Reported by hand"
}

fun DeviceTier.label(): String = when (this) {
    DeviceTier.TIER1_COMPUTE -> "Relay phone, runs AI on device"
    DeviceTier.TIER2_SCOUT -> "Scout phone"
    DeviceTier.COMMAND_CENTER -> "Command Center"
}

fun FixSource.label(): String = when (this) {
    FixSource.UWB -> "UWB"
    FixSource.GPS -> "GPS"
    FixSource.RSSI -> "Signal"
    FixSource.SIMULATED -> "SIM"
}

/** Compact age: "now", "45s", "12m", "3h". */
fun ageLabel(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val s = ((nowMs - atMs) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "now"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}

fun distanceLabel(m: Double): String = when {
    m < 10 -> "%.1f m".format(m)
    m < 1000 -> "${m.toInt()} m"
    else -> "%.1f km".format(m / 1000)
}

private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
fun clockLabel(atMs: Long): String = clockFormat.format(Date(atMs))

/** Last 4 characters of an ephemeral node id. Never a person identifier. */
fun shortNode(nodeId: String): String = nodeId.takeLast(4).uppercase()
