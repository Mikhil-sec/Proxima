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
}
