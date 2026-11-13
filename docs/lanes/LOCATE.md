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
- [x] Help-point bearing for the guided-response card: `LocateTargets.helpPoint()` + `track()`. UI wiring requested.
**P2**
- [ ] Venue outline (OSM paths around Ganga Talao + the demo venue) for the Command Center map: needs an `interface-change` issue.
- [~] ~~Anonymous crowd-density grid~~: already built by AI (`CrowdRiskEngine`, #30); not duplicated.
- [ ] Pitch slides (≤6) with UI, ready 10:30 Sat.

## Gotchas
GPS is poor indoors (the demo room), so UWB or RSSI must carry the stage demo. UWB needs API 31+: guard with `capabilities.hasUwb`.
If the manifest merger rejects the UWB lib's minSdk, use `tools:overrideLibrary` in the locate manifest.

## How my part works (5 lines for teammates; fill before 10:00 Sat)
1. `LocateModule.create()` returns `AndroidLocateEngine` when simulation is off (the fake otherwise).
2. Position = fused GPS every 1 s; heading = rotation-vector sensor, corrected to true north, smoothed; works flat or upright.
3. `track(target)` emits a `RadarFix` every 100 ms from the best source: UWB → GPS → mesh RSSI → last GPS. (Real Nearby gives no RSSI, so on phones it's UWB → GPS.) Help points: `track(LocateTargets.helpPoint(p))`.
4. UWB: the guardian's phone sends a signed CONTROL "uwb" REQUEST over the mesh; the finder's phone auto-answers; both range at ~0.3 m.
5. Direction arrow from UWB needs angle-of-arrival (S23+ yes, S24U no) and >0.5 m; it is median+EMA smoothed; otherwise distance-only.

## NEXT_SESSION_HANDOFF
_Updated 18:53 Fri. `lane/locate` = `main` (PR #39 merged 18:34) + README/lane-doc commits not yet in main._

**State: what works (verified on phones, simulation OFF)**
- **UWB radar end-to-end** S23+ (guardian) → S26+ (target) over the real NearbyMeshEngine: TRACK → first `src=UWB` fix 0.5 s,
  ~3 Hz, aoa=true, **arrow not mirrored** (`UWB_AZIMUTH_SIGN = +1` confirmed). #24 confirmed + commented (MESH may close; we opened it).
- GPS path on one phone: fixed target 100 m N → `d=100.5 brg=0`, live relative bearing; declination −18.3°.
- Debug tooling: `LocateDebugTrigger` (adb `mu.proxima.locate.TRACK --es node … [--es lat/lon] / STOP / STATUS`, debug builds only) +
  `bash locate/tools/radar_test.sh setup|status|track G T [gps]|fixes|stop|simon|cleanup`. Installs side-by-side
  `mu.proxima.app.locatetest` (via `-I locate/tools/locatetest.init.gradle`), never replaces teammates' apps.
- In main: `UwbAzimuthFilter` (edge/contact drop → median-5 → EMA → 1.5 s hold; tests replay the real recording),
  `LocateTargets.helpPoint()` (help-point direction via `track()`), UWB only starts when target is a mesh peer with `hasUwb`.
- README: LOCATE radar table under *How it works* + AI-disclosure row (on lane branch → next PR). Commented #23.

**State: NOT verified / known gaps**
- Smoothed arrow (filter) + help-point direction + hasUwb gating **not yet re-run on phones** (built after the phone session).
- Outdoor 2-phone GPS radar + heading vs a real compass: never done.
- RSSI fallback is **simulation-only** (real Nearby gives no `rssiDbm`); documented in README, decided not to work around.
- `app/` doesn't set `MeshBeaconInfo.location` → AI's crowd heatmap empty with sim OFF (asked UI in #40).

**Exact next step**
1. ~~Open PR lane/locate → main~~ done 19:00 (#42 merged).
2. (📱 2 phones, ~10 min OUTDOORS) `radar_test.sh setup` → `track <S23+> <other> gps` → compare distance/bearing; check
   `status` heading against a real compass app. Re-check UWB arrow smoothness indoors. Then `cleanup`.
3. Watch #40 (UI wiring of help-point arrow + beacon location) and #14/#15 (`trackFinder` fix); help UI if asked.
4. Remaining: venue/assembly points (needs interface-change), pitch slides with UI by 10:30 Sat, "How my part works" final pass.

**Open items on the bus**: #40 (LOCATE→UI, open), #14/#15 (voted 👍 18:52 with trackFinder ask), #23 (done), #24 (verified; can close).

**Files touched this session**: `locate/src/main/java/mu/proxima/locate/{LocateDebugTrigger,UwbAzimuthFilter,LocateTargets,AndroidLocateEngine}.kt`,
`locate/src/test/.../{LocateDebugTriggerTest,UwbAzimuthFilterTest,LocateTargetsTest}.kt`, `locate/tools/{radar_test.sh,locatetest.init.gradle}`,
`README.md` (LOCATE parts only), this file.

**Machine/phone notes**: adb = `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`, gh = `/c/Program Files/GitHub CLI/gh.exe` (neither on PATH).
Samsung Device Care suspends idle debug apps ("paused" screen) → `adb shell pm unsuspend --user 0 <pkg>` (setup does it).
Teammate phones replugged often default to **USB "charging only"** → adb sees nothing; switch to file transfer.
Chanum's S26+ (SM-S947B, API 37) has UWB + AoA support flag; comma-decimal locale. Node ids change on every reinstall.

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
| 18:00 Fri | Debug adb radar trigger (TRACK/STOP/STATUS) + `locate/tools/radar_test.sh`; build + tests green, merged latest main |
| 18:10 Fri | **UWB radar proven on phones, simulation OFF** (S23+ guardian → S26+ target over the real mesh): TRACK → first `src=UWB` fix in 0.5 s, aoa=true, ~3 Hz, d 0.21–0.31 m side by side; arrow hidden <0.3 m as designed. CONTROL "uwb" delivered by NearbyMeshEngine (#24 satisfied) |
| 18:16 Fri | UWB arrow check (S23+ flat, S26+ at ~1.7 m): ahead → rel ≈ +5…+20°, 45° right → ≈ +30…+40°, 45° left → ≈ 340…350° (−10…−20°). **Sign correct, not mirrored** (`UWB_AZIMUTH_SIGN` stays +1). Angles compressed/biased ~+8° and raw azimuth jitters (single ±40° spikes, a few exact 270° values) → needs smoothing + outlier rejection before the UI arrow |
| 18:27 Fri | #24 confirmed on phones + commented; merged main (MESH dbb943e: no store-and-forward for CONTROL, fine with our 3 s retry). Test builds removed from S23+ and Chanum's S26+ |
| 18:30 Fri | `UwbAzimuthFilter` (edge/contact rejection → median-5 → EMA → 1.5 s hold) wired into `track()`; 5 tests replay the real 18:14 recording (max arrow step 138° raw → <25°); locate tests green |
| 18:31 Fri | Help-point direction: `LocateTargets.helpPoint(HelpPoint)` → reuse `track()` (GPS + compass, live relative bearing); UWB now starts only once the target is a mesh peer with `hasUwb` (no REQUEST flood for help points / non-UWB phones). Build + tests green |
| 18:40 Fri | PR #39 merged (CI green). Asked UI in #40 (help-point arrow + wire `MeshBeaconInfo.location`). README radar section + AI-disclosure row written (#23) |
| 18:53 Fri | Reviewed #14/#15 for LOCATE: 👍 + non-blocking `trackFinder` fix (newest `atMs`, same finder). Saving protocol |
| 19:00 Fri | New session: merged main (#41), build + locate tests green, **PR #42 merged** (README radar + lane doc). Reviewed UI's RadarScreen (lane/ui) against `RadarFix`: compatible; posted ±0 m accuracy-label nit on #40 |
| 19:13 Fri | **Smoothed UWB arrow verified on phones** (S23+ guardian → Chanum S26+, normal `mu.proxima.app` reinstalled on both at user request, sim OFF, mesh connect 3 s). 313 fixes ~3 Hz: ahead ≈ 352–358° at 1.8 m, right ≈ +52…+62°, left ≈ −23…−38° (at ~0.5 m); arrow follows a turn in ~1 s; at ≥0.4 m no visible step >30° (raw had ±40° spikes). Only jumps (35–89°) at 0.30–0.34 m, mostly across hidden gaps. Arrow hidden <0.3 m as designed |
| 19:25 Fri | Outdoor GPS radar, site 1 (S26+ fixed point, S23+ walks): distance smooth, 59.5 m at the far spot vs 70 steps (≈49–60 m), bearing steady ±1°, auto handover UWB→GPS at ~6 m and back to UWB at ~1 m. **Heading ≈ +70° off** (pointing at target read 250° vs 180° true), steady, also on the walk back |
| 19:37 Fri | Outdoor GPS radar, site 2 (35 steps ≈ 26 m): **heading correct** (walking away 285–290° vs 278–286° true; pointing at target rel 353–1°; walking back rel 0–19°), spin tracked smoothly. GPS distance peaked 23.8 m, drifted to 18 m while standing (±6 m GPS). UWB re-acquired at **10.8 m** walking back, arrow rel 0–5° to contact. → heading code OK; the site-1 error was environment/uncalibrated magnetometer, not code |
| 19:45 Fri | Sync (#53): merged main, build + locate tests green, **PR #60 merged** at the 19:45 cutoff; 👍 + M2 heads-ups on #53. Paused until main reopens. Next: arrow cutoff 0.3→0.5 m, compass-calibration hint (heading accuracy from rotation vector) |
| 20:15 Fri | Pulled main fb20894 (#68), build + all tests green. UWB arrow cutoff 0.3 → 0.5 m (+ boundary tests), README row updated (0.5 m, UWB from 10.8 m outdoors) |
| 20:25 Fri | Compass reliability logging: rotation-vector heading accuracy (`values[4]`) + accuracy status in the debug STATUS line and on every status change. Needed before a calibration hint: must know what the S23+ reports when it is right vs ~70° off |

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
| 17:58 Fri | Trigger is a receiver registered by `AndroidLocateEngine.start()`, runtime-gated on FLAG_DEBUGGABLE | Has the live engine instance with no static hook and no edit to UI-owned `app/`; identical to MESH's PING | Static engine hook in `LocateModule` + manifest receiver in `src/debug`; asking UI to expose the graph |
| 18:08 Fri | Device tests install a side-by-side `mu.proxima.app.locatetest` (Gradle `-I locate/tools/locatetest.init.gradle`) | Teammates' builds are signed with other debug keys: installing over them fails, and uninstalling wipes their data | Uninstalling teammates' app; editing UI-owned `app/build.gradle.kts` |
| 18:17 Fri | Keep `UWB_AZIMUTH_SIGN = +1` | Measured: target to the right gives positive azimuth on S23+ | Flipping to −1 |
| 18:29 Fri | Clean UWB azimuth with median-of-5 + EMA, drop |az| ≥ 85° and < 0.3 m, hide arrow after 1.5 s without a good angle | Recorded data had single ±40° spikes and exact −90° "don't know" values; median kills spikes without the lag a heavy EMA would add; a stale arrow is worse than none | Raw azimuth (flickers); EMA only (spikes still drag the arrow); Kalman filter (overkill for the deadline) |
| 18:31 Fri | Help-point direction = a static `TrackTarget` on the existing radar, no new API | One radar for child, finder and help point ("one feature, many uses"); no `core/` interface change; UI gets distance + live turn arrow + proximity for free | New `LocateEngine.directionTo(point)` (contract change); UI computing bearing − heading itself (duplicates heading logic) |
| 18:31 Fri | Start UWB ranging only when the target is a mesh peer advertising `hasUwb` | Otherwise every track() of a help point or non-UWB phone sends a CONTROL REQUEST over the mesh every 3 s forever | Always try UWB (mesh spam); a node-id prefix check only (misses non-UWB phones) |
| 18:37 Fri | Drop the LOCATE density grid | AI's `CrowdRiskEngine` (#30) already buckets `mesh.peers[].location` into the same 25 m cells for the Command Center; two would be a parallel system | Building `DensityGrid` in locate |
| 18:39 Fri | README states RSSI fallback is simulation-only | Real `NearbyMeshEngine` never fills `Peer.rssiDbm` (Nearby Connections exposes no RSSI); judges reward honest limits | Claiming warmer/colder on all phones |
| 18:52 Fri | 👍 #14 (lost & found without wristband), fix to `trackFinder` as a non-blocking ask | Finder id = mesh node id, so the radar/UWB path works unchanged; only ordering/multi-finder mixing is wrong, and it's small and in `core` | Blocking #15 until fixed (would stall UI's lost-child flow) |
| 18:52 Fri | Don't build a beacon-cell fallback for the missing RSSI | Needs UI's beacon wiring, only says "within ~25 m", and shows an unsealed location on the radar; on stage UWB carries the demo | Beacon-cell coarse radar |
| 20:15 Fri | Raise the UWB arrow cutoff 0.3 → 0.5 m | 19:12 phone re-check: the only 35–89° arrow jumps were at 0.30–0.34 m; at ≥0.4 m no step > 30°. Within 0.5 m the parent can see the child and the screen already says "together" | Keep 0.3 m (arrow jumps as you close in); 1 m (loses the useful last-metre arrow) |
