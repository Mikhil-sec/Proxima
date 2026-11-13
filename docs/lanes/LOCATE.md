# LANE: LOCATE (radar, UWB, position)
Owner: **Yorvan** (Member 2) · recommended phone: Galaxy S26+ · Branch `lane/locate` · Issue `[LANE:LOCATE]`

## Mission
"Where are they, and which way do I walk?" That has to work on every phone, with centimetre precision on UWB flagships and indoors for the demo.

## You own
`locate/` (incl. its manifest), `core/.../fake/FakeLocateEngine`, this file.
Implement `LocateEngine` and return it from `LocateModule.create()` when `!simulation`.

## Tasks
**P0 (device check by 15:00, M1 18:00)**
- [ ] **Device check:** `androidx.core.uwb` ranging between two Samsungs (S26+ ↔ S23+/S24U). Does it report distance AND azimuth (angle of arrival)? Use **Context7**. Log the result.
- [ ] `AndroidLocateEngine`: fused location (high accuracy, ~1 s) → `selfLocation`; `TYPE_ROTATION_VECTOR` azimuth,
      corrected to true north with `GeomagneticField`, smoothed → `headingDeg`.
- [ ] `track(target)`: GPS fix from `target.locations` + self → `RadarFix` (distance, bearing, relative bearing, proximity).
**P1 (M2/M3)**
- [ ] UWB mode: exchange session params over the mesh (`PacketKind.CONTROL`, `channel="uwb"`, built with `PacketFactory`).
      When both phones have UWB and are in range, switch `RadarFix.source` to UWB.
- [ ] RSSI fallback: no GPS fix → use `mesh.peers` RSSI of the target node for warmer/colder (`proximity` only, no bearing).
- [ ] Help-point bearing for the guided-response card (`GeoMath.bearingDeg` vs heading). Coordinate with UI.
**P2**
- [ ] Venue outline (OSM paths around Ganga Talao + the demo venue) for the Command Center map: needs an `interface-change` issue.
- [ ] Anonymous crowd-density grid from beacons (`GeoMath.cellKey`).
- [ ] Pitch slides (≤6) with UI, ready 10:30 Sat.

## Gotchas
GPS is poor indoors (the demo room), so UWB or RSSI must carry the stage demo. UWB needs API 31+: guard with `capabilities.hasUwb`.
If the manifest merger rejects the UWB lib's minSdk, use `tools:overrideLibrary` in the locate manifest.

## How my part works (5 lines for teammates; fill before 10:00 Sat)
1. `LocateModule.create()` returns `AndroidLocateEngine` when simulation is off (the fake otherwise).
2. Position = fused GPS every 1 s; heading = rotation-vector sensor, corrected to true north, smoothed; works flat or upright.
3. `track(target)` emits a `RadarFix` every 100 ms from the best source: UWB → GPS → mesh RSSI (warmer/colder) → last GPS.
4. UWB: the guardian's phone sends a signed CONTROL "uwb" REQUEST over the mesh; the finder's phone auto-answers; both range at ~0.3 m.
5. Direction arrow from UWB needs angle-of-arrival (S23+ yes, S24U no) and >0.3 m; otherwise distance-only.

## NEXT_SESSION_HANDOFF
_Updated 17:56 Fri. Branch `lane/locate` = `main` + nothing pending (PR #26 merged 17:50)._

**State: what works**
- Onboarded: S23+, @yorvan2401, `gh` logged in (keyring), Android Studio + SDK at default paths, JBR for JAVA_HOME.
- **UWB device check PASSED 16:07** (S24U controller ↔ S23+ controlee): distance on both (2 m → 2.11–2.15,
  5 m → 4.95–5.11, ~4 Hz, 0 dropouts). **Azimuth: S23+ yes, S24U NO.** At <0.3 m azimuth = noise.
  → Demo guardian phone = S23+. Re-run: `bash locate/tools/uwb_check.sh` (both phones on USB, UWB ON, airplane OFF).
- `AndroidLocateEngine` (in `main`, used when simulation=false): fused GPS 1 s, rotation-vector heading
  (flat or upright) + declination (true north) + smoothing. `track()` = UWB (<1.5 s) → GPS (<30 s) → RSSI → stale GPS.
- `UwbRanger`: UWB session over the mesh, signed CONTROL packets channel "uwb" (REQUEST/ACCEPT/STOP);
  tracker = controller; target auto-answers via responder started in `start()`. Retries 3 s, STOP on cancel.
- Tests: `RadarMathTest` (6) + `UwbControlTest` (5) green. Full `./gradlew assembleDebug test` green at 17:50.

**State: NOT yet verified**
- Engine + UWB radar have **never run on a phone** with simulation OFF. Azimuth sign unverified
  (if the arrow is mirrored, set `RadarMath.UWB_AZIMUTH_SIGN = -1`).
- #24 (ask MESH to deliver CONTROL "uwb"): reading `NearbyMeshEngine`, it already emits every packet kind on
  `inbound` + relays + fills `Peer.hasUwb` from beacons → probably already satisfied. Confirm on device, then
  comment + let MESH close it.

**Exact next step**
1. (no phones) Add a **debug-only adb trigger** in `locate/src/debug/`: a BroadcastReceiver, e.g.
   `adb shell am broadcast -a mu.proxima.locate.TRACK --es node <nodeId>` → calls `graph.locate.track(...)`
   and logs every `RadarFix` as `locate fix src=… d=… rel=… prox=…`. Problem to solve: the receiver needs the
   app's LocateEngine instance. `app/` is UI-owned, so either a static hook in `LocateModule` (debug) or ask UI.
   MESH did similar: see `mesh/src/main/java/mu/proxima/mesh/MeshDemoControls.kt` + `mesh/tools/mesh_device_test.sh`.
2. (📱 2 UWB phones, S23+ = guardian) Turn simulation OFF on both: it's SharedPreferences `proxima` /
   `simulation` (default true), read in `app/.../ProximaApp.kt` → set via `adb shell run-as mu.proxima.app` or
   ask UI for a toggle. Get node ids from `adb logcat -s Proxima:*` ("mesh start node=…"). Trigger TRACK on
   S23+ → expect `src=UWB` fixes with distance + arrow. Outdoors: compare heading with a real compass.
3. Then: help-point direction helper for guided response (TRUST `HelpPointRepository.nearest` + our heading;
   agree format with UI) → anonymous density grid (`GeoMath.cellKey`, beacons) → venue/assembly points
   (needs interface-change) → pitch slides with UI by 10:30 Sat.

**Open items on the bus**
- #14 (lost&found without wristband): no LOCATE impact; Yorvan to 👍 on GitHub (blocked for Claude).
- #24 LOCATE→MESH (see above). Diya's S24 Ultra was used for the UWB check and still has our debug build.

**Files touched (all in `main`)**: `locate/build.gradle.kts`, `locate/src/main/AndroidManifest.xml`,
`locate/src/main/java/mu/proxima/locate/{AndroidLocateEngine,UwbRanger,UwbControl,RadarMath,LocateModule}.kt`,
`locate/src/test/.../{RadarMathTest,UwbControlTest}.kt`, `locate/src/debug/...UwbCheckActivity.kt` + manifest,
`locate/tools/uwb_check.sh`, `docs/TEAM_ROLES.md`, this file.

**Machine notes**: phone driver = Samsung USB Driver (installed). adb = `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`
(not on PATH). `gh` = `/c/Program Files/GitHub CLI/gh.exe` (not on PATH in Claude's shell). Reading `build/` is denied:
judge tests by Gradle's BUILD SUCCESSFUL. Installing our build on a teammate's phone replaces theirs (signature
mismatch → uninstall first, tell them to reinstall).

## Progress log
| Time | What |
|---|---|
| 15:00 Fri | Onboarding done: lane branch pushed, app installed on S23+ in SIM mode |
| 15:46 Fri | UWB device-check tool (debug-only activity + adb script) builds; waiting for 2 phones |
| 15:54 Fri | AndroidLocateEngine (GPS + true-north heading + RSSI fallback) builds, RadarMath tests pass |
| 16:07 Fri | UWB device check passed: distance ±0.1–0.3 m on S23+ & S24U; azimuth only on S23+ |
| 16:15 Fri | UWB radar mode (mesh CONTROL handshake + UwbRanger) in `track()`; builds, 11 tests green |
| 16:30 Fri | Opened #24 (LOCATE→MESH: deliver CONTROL "uwb"); PR #26 opened |
| 17:50 Fri | Merged latest main (MESH real engine, TRUST, AI), resolved TEAM_ROLES conflict, full build+tests green, **PR #26 merged** |
| 17:56 Fri | Saving protocol: handoff + how-my-part-works written |
| 18:20 Fri | Debug adb radar trigger (TRACK/STOP/STATUS) + `locate/tools/radar_test.sh`; build + tests green, merged latest main |
| 18:10 Fri | **UWB radar proven on phones, simulation OFF** (S23+ guardian → S26+ target over the real mesh): TRACK → first `src=UWB` fix in 0.5 s, aoa=true, ~3 Hz, d 0.21–0.31 m side by side; arrow hidden <0.3 m as designed. CONTROL "uwb" delivered by NearbyMeshEngine (#24 satisfied) |
| 18:16 Fri | UWB arrow check (S23+ flat, S26+ at ~1.7 m): ahead → rel ≈ +5…+20°, 45° right → ≈ +30…+40°, 45° left → ≈ 340…350° (−10…−20°). **Sign correct, not mirrored** (`UWB_AZIMUTH_SIGN` stays +1). Angles compressed/biased ~+8° and raw azimuth jitters (single ±40° spikes, a few exact 270° values) → needs smoothing + outlier rejection before the UI arrow |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 15:40 Fri | UWB check exchanges session params over adb, not the mesh | Mesh is still a fake; answers "does UWB AoA work" today without blocking on MESH | Waiting for real mesh; manual typing of addresses on-screen |
| 15:40 Fri | Device-check screen lives in `locate/src/debug` | Never ships in release, doesn't touch UI lane's `app/` | Adding a screen to `app/` (not our lane) |
| 15:40 Fri | Static STS key for the check only; real engine will use per-session random key sent sealed | Simplest way to prove ranging works; security handled in the real engine | Provisioned STS (needs FiRa provisioning) |
| 15:50 Fri | Heading = horizontal sum of phone's top edge + back vector | One formula works flat, tilted and upright (camera grip) with no jump at a threshold | `getOrientation` azimuth (breaks when upright); remap at a pitch threshold (jumps) |
| 15:50 Fri | Apply magnetic declination (GeomagneticField) | Mauritius ≈ −20°: uncorrected arrow points ~20° off in a crowd | Magnetic heading only |
| 15:50 Fri | Fix chain GPS (fresh <30 s) → RSSI (heard <10 s) → stale GPS; `uwbAvailable=false` until UWB wired | Indoors GPS is stale, RSSI still says warmer/colder; never claim UWB we don't deliver | Report hardware UWB flag now |
| 16:10 Fri | UWB is the indoor stage-demo radar; S23+ is the guardian (arrow) phone | Measured: S23+ has azimuth, S24U distance only; GPS fails indoors | GPS-only radar indoors; S24U as guardian |
| 16:10 Fri | UWB radar: use azimuth only when distance > 0.3 m and AoA is supported, else distance-only | Azimuth is ±90° noise at contact range and absent on S24U | Always show the arrow |
| 16:15 Fri | UWB session setup over the mesh (signed CONTROL "uwb" REQUEST/ACCEPT/STOP), tracker = controller, target auto-answers | No pairing screen for a panicking parent; reuses the one pipeline/wire format | Separate BLE GATT exchange; manual pairing UI |
| 16:15 Fri | Per-session random static-STS key sent in the signed (not sealed) CONTROL payload | TrackTarget has no recipient key to seal to; key is single-use and carries no location/personal data | Fixed key (replayable); sealing (needs contract change) |
| 17:55 Fri | Device-test the radar via a debug-only adb trigger instead of waiting for the UI radar screen | Lets M1 "engine real" be proven today; same pattern MESH used | Waiting for UI's radar screen |
| 18:15 Fri | Trigger is a receiver registered by `AndroidLocateEngine.start()`, runtime-gated on FLAG_DEBUGGABLE | Has the live engine instance with no static hook and no edit to UI-owned `app/`; identical to MESH's PING | Static engine hook in `LocateModule` + manifest receiver in `src/debug`; asking UI to expose the graph |
| 18:08 Fri | Device tests install a side-by-side `mu.proxima.app.locatetest` (Gradle `-I locate/tools/locatetest.init.gradle`) | Teammates' builds are signed with other debug keys: installing over them fails, and uninstalling wipes their data | Uninstalling teammates' app; editing UI-owned `app/build.gradle.kts` |
| 18:17 Fri | Keep `UWB_AZIMUTH_SIGN = +1` | Measured: target to the right gives positive azimuth on S23+ | Flipping to −1 |
