# LANE: MESH
Owner: **Chanum** (Member 3) · recommended phone: Galaxy S23+ · Branch `lane/mesh` · Issue `[LANE:MESH]`

## Mission
Packets reach every phone in the crowd with no network: multi-hop, de-duplicated, store-and-forward, alive in the background.

## You own
`mesh/` (incl. `mesh/src/main/AndroidManifest.xml` for permissions + service), `core/.../fake/FakeMeshEngine`, this file.
Implement `MeshEngine` (`core/.../engine/Engines.kt`) and return it from `MeshModule.create()` when `!simulation`.

## Tasks
_`[~]` = coded + compiles, not yet verified on phones._
**P0 (by 15:00 device check, M1 18:00)**
- [~] `NearbyMeshEngine` on Google Nearby Connections, `Strategy.P2P_CLUSTER`; advertise and discover at the same time; auto-accept;
      serviceId `mu.proxima.mesh`; endpoint name = `nodeId|tier`. Check API and permissions via **Context7**.
- [~] Permissions per API level declared in the mesh manifest + exposed in `requiredPermissions`
      (BLE scan/advertise/connect, NEARBY_WIFI_DEVICES, location for older APIs).
- [~] Payload = `ProximaJson` `MeshPacket` as BYTES. Log tag `Proxima`.
- [~] Relay: receive → drop if `packetId` already seen (Room table) → deliver to `inbound` →
      if `ttl > 1` forward to all other endpoints with `ttl-1`, `hopCount+1`, `path + selfNodeId` → emit `HopEvent`s.
- [~] Foreground service (connectedDevice type) so relaying continues with the screen off / in airplane mode with BT+Wi-Fi on.
**P1 (M2 23:00)**
- [~] (in-memory, Room persistence TODO) Store-and-forward: keep live packets in Room ~30 min; on a new connection, flush them, CRITICAL first.
- [~] Beacons every ~10 s (`PacketKind.BEACON`, `Beacon` payload, coarse location) → `peers` includes indirect nodes (`direct=false`).
- [~] **Demo multi-hop control:** neighbour allow-list so phones in one room form a chain A→B→C. Expose e.g.
      `object MeshDemoControls { val allowedNeighbours: MutableStateFlow<Set<String>?> }` and tell UI via a `request` issue.
- [~] `selfNodeId` must equal `trust.identity.nodeId`.
**P2**
- [ ] Acoustic FSK chirp fallback (AudioTrack/AudioRecord) for a tiny SOS packet when RF is unavailable.
- [ ] Film the backup demo video (09:30 Sat, outdoors).

## Gotchas
Location services must be ON for discovery on many devices. Test the A56 + Honor (Tier 2) early.
Nearby has per-device connection limits, so the cluster may not be a full mesh: relaying covers that.

## How my part works (5 lines for teammates; fill before 10:00 Sat)

## NEXT_SESSION_HANDOFF
- **Works (compiles, 5/5 JVM tests):** `NearbyMeshEngine` (P2P_CLUSTER, auto-accept, tie-break connect + 6 s fallback),
  `MeshRelay` (pure relay rules), Room dedup `SeenStore`, 30-min in-memory outbox flushed CRITICAL-first on a new link,
  beacons every 10 s → indirect peers, `MeshDemoControls.allowedNeighbours` (#8), `MeshService` (connectedDevice FGS).
  `MeshModule` returns the real engine when `simulation == false`.
- **NOT yet run on a phone.** Nothing verified over the air.
- **Gaps:** outbox is in memory (lost on restart; Room persistence = P1). Beacon `location` = null, `llmReady` = false
  (mesh has no locate/AI handle). App has no permission prompt or sim toggle yet (UI lane), so the test script grants
  permissions and writes `simulation=false` prefs via adb.
- **Exact next step:** plug in 2 phones → `bash mesh/tools/mesh_device_test.sh` → `bash mesh/tools/mesh_device_test.sh logs`
  (expect `connected <node>`) → `... ping <serial>` → the other phone logs `rx CONTROL`. Then 3 phones + allow-list for A→B→C.
- **Machine note (Chanum):** build output goes to `C:/dev/proxima-build` via `~/.gradle/init.d/` (OneDrive locks `build/`).
- Files: `mesh/src/main/AndroidManifest.xml`, `mesh/src/main/java/mu/proxima/mesh/{NearbyMeshEngine,MeshRelay,SeenStore,MeshService,MeshDemoControls,MeshModule}.kt`,
  `mesh/src/test/java/mu/proxima/mesh/MeshRelayTest.kt`, `mesh/tools/mesh_device_test.sh`.

## Progress log
| Time | What |
|---|---|
| 15:05 Fri | Onboarded (gh, Android Studio, SDK, assembleDebug ✅), lane/mesh pushed |
| 15:20 Fri | NearbyMeshEngine + relay + dedup + outbox + beacons + FGS + allow-list; 5/5 relay tests green |
| 15:28 Fri | Debug adb PING hook + `mesh/tools/mesh_device_test.sh`; waiting on phones for the device check |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 15:10 Fri | `path` = every node the packet has left, origin first; origin sends `path=[self]` | Last entry is always the previous hop → real `fromNodeId` in HopEvents (#8) and a "never send back along path" loop guard | Empty path at origin (first hop unknown to receiver) |
| 15:12 Fri | Relay rules in a pure `MeshRelay` object | Unit-testable on the JVM before phones arrive; engine stays thin | Logic inline in Nearby callbacks |
| 15:14 Fri | Connect tie-break: smaller nodeId requests, other side retries after 6 s | P2P_CLUSTER phones that both request at once often both fail | Both request at once; only one side ever requests |
| 15:15 Fri | Dedup = in-memory set in front of Room `seen_packets` | Hot path skips disk; Room keeps dedup across restarts | Room only (slow); memory only (re-relays after restart) |
| 15:16 Fri | Multi-hop demo via allow-list `MeshDemoControls.allowedNeighbours` in `:mesh` | Picks exact links for A→B→C in one room; no `core/` interface change | `setMaxDirectPeers(n)` (can't choose which link drops) |
| 15:17 Fri | No HopEvents for BEACON packets | Beacons every 10 s from every phone would flood the hop visualizer | Animate beacons too |
| 15:25 Fri | Debug-only adb PING sends CONTROL/`ping`, not an INCIDENT | Tests multi-hop with no UI and no fake incidents in the pipeline (real-data rule) | Fake INCIDENT packets |
