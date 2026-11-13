package mu.proxima.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import mu.proxima.app.ProximaGraph
import mu.proxima.core.model.FoundNotice
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType

/**
 * Lost & found session state that must outlive any one screen.
 * Guardian side: the latest sealed FoundNotice per lost report. Finder side: the alert being answered.
 */
class LostFound(private val graph: ProximaGraph) {
    /** Lost incident id → newest notice from the person who is with them. */
    val notices = mutableStateMapOf<String, FoundNotice>()
    private val alerted = mutableSetOf<String>()

    var sharingFor by mutableStateOf<IncidentRecord?>(null); private set
    var sharingSinceMs by mutableStateOf(0L); private set
    private var job: Job? = null

    /** Records a notice; true the first time we hear about this lost report (time to interrupt the guardian). */
    fun onNotice(notice: FoundNotice): Boolean {
        // Store-and-forward can flush older notices after newer ones (#14): keep the newest, so the radar never jumps back.
        val known = notices[notice.tagId]
        if (known == null || notice.atMs >= known.atMs) notices[notice.tagId] = notice
        return alerted.add(notice.tagId)
    }

    /** Finder: start telling this guardian where we are. False if the alert can't be answered (no reporter key). */
    fun startSharing(lost: IncidentRecord): Boolean {
        if (lost.originKey == null) return false
        stopSharing()
        job = graph.pipeline.shareLocationWithGuardian(lost, everyMs = 5_000L)
        sharingFor = lost
        sharingSinceMs = System.currentTimeMillis()
        return true
    }

    fun stopSharing() {
        job?.cancel()
        job = null
        sharingFor = null
    }

    fun isMine(record: IncidentRecord) = record.incident.reporterNodeId == graph.mesh.selfNodeId

    /** This phone's open lost-person reports (guardian view). */
    fun myOpenLost(records: List<IncidentRecord>) =
        records.filter { it.incident.type == IncidentType.LOST_PERSON && !it.resolved && isMine(it) }

    /** Lost-person alerts from other phones that a finder could answer. */
    fun othersOpenLost(records: List<IncidentRecord>) =
        records.filter { it.incident.type == IncidentType.LOST_PERSON && !it.resolved && !isMine(it) }
}
