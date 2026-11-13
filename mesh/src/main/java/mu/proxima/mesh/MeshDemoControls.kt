package mu.proxima.mesh

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stage control to force multi-hop when every phone is in one room (answers issue #8).
 *
 * - `allowedNeighbours = null` (default): connect to everyone in range.
 * - `allowedNeighbours = setOf("nodeB")`: this phone only keeps Nearby links to those node ids.
 *   Set A = {B}, B = {A, C}, C = {B} to get the chain A → B → C.
 *
 * Existing links to nodes outside the set are dropped as soon as the value changes.
 * Dev/demo tool only: it never touches packet contents.
 */
object MeshDemoControls {
    val allowedNeighbours: MutableStateFlow<Set<String>?> = MutableStateFlow(null)

    fun allows(nodeId: String): Boolean = allowedNeighbours.value?.contains(nodeId) ?: true

    /**
     * Acoustic fallback (P2), OFF by default so it never competes with voice SOS for the mic or talks over
     * the read-aloud steps. When ON and this phone has **no** Nearby link: HIGH/CRITICAL incidents it sends are
     * also chirped through the speaker (~3.7 s, twice), and the mic listens for chirps from other phones
     * (app on screen + RECORD_AUDIO needed). A heard SOS enters the mesh as an unverified incident.
     */
    val acoustic: MutableStateFlow<Boolean> = MutableStateFlow(false)
}
