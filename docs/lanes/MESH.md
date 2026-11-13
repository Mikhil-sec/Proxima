# LANE: MESH
Owner: **Chanum** (Member 3) · recommended phone: Galaxy S23+ · Branch `lane/mesh` · Issue `[LANE:MESH]`

## Mission
Packets reach every phone in the crowd with no network: multi-hop, de-duplicated, store-and-forward, alive in the background.

## You own
`mesh/` (incl. `mesh/src/main/AndroidManifest.xml` for permissions + service), `core/.../fake/FakeMeshEngine`, this file.
Implement `MeshEngine` (`core/.../engine/Engines.kt`) and return it from `MeshModule.create()` when `!simulation`.

## Tasks
_`[x]` = verified on phones · `[~]` = coded + unit-tested, not yet verified on phones._
**P0 (by 15:00 device check, M1 18:00)**
- [x] `NearbyMeshEngine` on Google Nearby Connections, `Strategy.P2P_CLUSTER`; advertise and discover at the same time; auto-accept;
      serviceId `mu.proxima.mesh`; endpoint name = `nodeId|tier`. Check API and permissions via **Context7**.
- [x] Permissions per API level declared in the mesh manifest + exposed in `requiredPermissions`
      (BLE scan/advertise/connect, NEARBY_WIFI_DEVICES, location for older APIs).
- [x] Payload = `ProximaJson` `MeshPacket` as BYTES. Log tag `Proxima`.
- [x] Relay (verified 3 phones, 16:46): receive → drop if `packetId` already seen (Room table) → deliver to `inbound` →
      if `ttl > 1` forward to all other endpoints with `ttl-1`, `hopCount+1`, `path + selfNodeId` → emit `HopEvent`s.
- [x] Foreground service (connectedDevice type) so relaying continues with the screen off / in airplane mode with BT+Wi-Fi on.
**P1 (M2 23:00)**
- [x] Store-and-forward (Room; restart flush seen on device): keep live packets in Room ~30 min; on a new connection, flush them, CRITICAL first.
- [~] Beacons every ~10 s (`PacketKind.BEACON`, `Beacon` payload, coarse location) → `peers` includes indirect nodes (`direct=false`).
- [x] **Demo multi-hop control:** neighbour allow-list so phones in one room form a chain A→B→C. Expose e.g.
      `object MeshDemoControls { val allowedNeighbours: MutableStateFlow<Set<String>?> }` and tell UI via a `request` issue.
- [~] `selfNodeId` must equal `trust.identity.nodeId`.
**P2**
- [ ] Acoustic FSK chirp fallback (AudioTrack/AudioRecord) for a tiny SOS packet when RF is unavailable.
- [ ] Film the backup demo video (09:30 Sat, outdoors).

## Gotchas
Location services must be ON for discovery on many devices. Test the A56 + Honor (Tier 2) early.
Nearby has per-device connection limits, so the cluster may not be a full mesh: relaying covers that.

## How my part works (5 lines for teammates; fill before 10:00 Sat)
1. Every phone runs Google **Nearby Connections** (Bluetooth + Wi-Fi, no internet): it advertises and searches at the same time and auto-connects to other Proxima phones in range.
2. A message is a signed `MeshPacket`. When a phone receives one, it drops it if it has seen that `packetId` before, otherwise shows it and passes it to every *other* neighbour.
3. Each hop lowers `ttl` by 1 and appends the phone to `path`, so a CRITICAL alert travels up to 10 hops and never bounces back the way it came. The signature stays valid because relays only touch ttl/hop/path.
4. Phones keep live alerts for 30 min (Room database) and send them to anyone who walks into range later, most severe first: a crowd moving around becomes the delivery network.
5. A foreground service keeps relaying with the screen off. For the stage demo, `MeshDemoControls` limits who links to whom, so judges can watch A → B → tablet in one room.

## NEXT_SESSION_HANDOFF
- **Works:** `NearbyMeshEngine` (P2P_CLUSTER, auto-accept, tie-break connect + 6 s fallback, advertise/discovery retry
  every 10 s), `MeshRelay` (pure relay + flush-order rules), Room dedup `SeenStore`, Room store-and-forward
  (`stored_packets`, 30 min, restored on start, CRITICAL first), beacons every 10 s → indirect peers,
  `MeshDemoControls.allowedNeighbours` (#8), `MeshService` (connectedDevice FGS). 10/10 JVM tests.
- **Verified on 2 phones (15:48):** connect, beacons, PING both ways, discovery retry after unlock. Not yet on device:
  3-phone forwarding, allow-list, store-and-forward flush after restart, screen-off relaying.
- node ids change every launch (trust still gives `SELF-xxxx`) → allow-list needs stable ids from TRUST (#19).
- **Gaps:** beacon `location` = null, `llmReady` = false (mesh has no locate/AI handle). App has no permission prompt or
  sim toggle yet (UI lane), so the test script grants permissions and writes `simulation=false` prefs via adb.
- **Exact next step (needs 3 phones, after #19):** `bash mesh/tools/mesh_device_test.sh` on all 3, then set the
  allow-list A={B}, B={A,C}, C={B} and `... ping <A serial>` → C logs `rx CONTROL ... hops=1`. Then screen-off test.
- **Machine note (Chanum):** build output goes to `C:/dev/proxima-build` via `~/.gradle/init.d/` (OneDrive locks `build/`).
- Files: `mesh/src/main/AndroidManifest.xml`, `mesh/src/main/java/mu/proxima/mesh/{NearbyMeshEngine,MeshRelay,SeenStore,MeshService,MeshDemoControls,MeshModule}.kt`,
  `mesh/src/test/java/mu/proxima/mesh/MeshRelayTest.kt`, `mesh/tools/mesh_device_test.sh`.

## Progress log
| Time | What |
|---|---|
| 15:05 Fri | Onboarded (gh, Android Studio, SDK, assembleDebug ✅), lane/mesh pushed |
| 15:20 Fri | NearbyMeshEngine + relay + dedup + outbox + beacons + FGS + allow-list; 5/5 relay tests green |
| 15:28 Fri | Debug adb PING hook + `mesh/tools/mesh_device_test.sh`; waiting on phones for the device check |
| 15:48 Fri | ✅ **Device check passed** (S947B API 37 + S23+ API 36): Nearby connect ~6 s, beacons + PING both ways. Fixed: location needed on API 37, discovery retry |
| 15:57 Fri | Store-and-forward persisted in Room (survives restart) + 5 more JVM tests (10/10); "how my part works" written |
| 16:10 Fri | Reconnect loop with backoff (2→30 s + jitter); 3 more JVM tests (13/13) | Device log showed a dropped link that never came back |
| 16:46 Fri | ✅ **3-phone test** (S947B, Honor X8b relay, S23+): forced chain via allow-list, PING relayed hops=1 in 0.09–0.17 s (was 7–10 s before one-at-a-time connects); reconnect after 15 s radio-off in 31 s; relay with Honor screen off ~80 s (Doze); stored packets flushed after restart |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 15:10 Fri | `path` = every node the packet has left, origin first; origin sends `path=[self]` | Last entry is always the previous hop → real `fromNodeId` in HopEvents (#8) and a "never send back along path" loop guard | Empty path at origin (first hop unknown to receiver) |
| 15:12 Fri | Relay rules in a pure `MeshRelay` object | Unit-testable on the JVM before phones arrive; engine stays thin | Logic inline in Nearby callbacks |
| 15:14 Fri | Connect tie-break: smaller nodeId requests, other side retries after 6 s | P2P_CLUSTER phones that both request at once often both fail | Both request at once; only one side ever requests |
| 15:15 Fri | Dedup = in-memory set in front of Room `seen_packets` | Hot path skips disk; Room keeps dedup across restarts | Room only (slow); memory only (re-relays after restart) |
| 15:16 Fri | Multi-hop demo via allow-list `MeshDemoControls.allowedNeighbours` in `:mesh` | Picks exact links for A→B→C in one room; no `core/` interface change | `setMaxDirectPeers(n)` (can't choose which link drops) |
| 15:17 Fri | No HopEvents for BEACON packets | Beacons every 10 s from every phone would flood the hop visualizer | Animate beacons too |
| 15:46 Fri | `requiredPermissions` lists coarse+fine location on every API level | Seen on device: API 37 Nearby discovery fails 8034 without it | Location only ≤ API 32 (per older docs) |
| 15:47 Fri | Retry advertising/discovery every 10 s while running | Phone locked at start = location denied → discovery never recovered | Fail once and wait for app restart |
| 16:08 Fri | Keep endpoints after disconnect; 1 s loop retries with exponential backoff 2→30 s + 0–1 s jitter; forget after 10 min unseen | Device log: link dropped at 15:48 and Nearby never re-reported the phone as found → link stays dead | Remove on disconnect and wait for rediscovery (never comes); fixed-interval retry (both phones collide) |
| 16:40 Fri | One connection attempt in flight at a time (15 s timeout), fewest-attempts first; forget endpoints after 8 failed attempts; drop stale endpoint ids of a re-found node | Honor X8b log: parallel requestConnection → 8007/8012 errors + radio busy; relay latency 7–10 s → 0.1 s after fix | Parallel attempts with backoff only (still collide); never forgetting ghosts |
| 15:25 Fri | Debug-only adb PING sends CONTROL/`ping`, not an INCIDENT | Tests multi-hop with no UI and no fake incidents in the pipeline (real-data rule) | Fake INCIDENT packets |
