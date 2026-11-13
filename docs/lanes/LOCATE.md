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

## NEXT_SESSION_HANDOFF
- Onboarded (S23+, @yorvan2401). App installs and runs in SIM mode.
- **UWB device check DONE 16:07 Fri (S24U controller ↔ S23+ controlee):** distance works on both
  (2 m → 2.11–2.15, 5 m → 4.95–5.11, ~4 Hz, 0 dropouts in 5 min). **Azimuth: S23+ yes, S24U NO**
  (`isAzimuthalAngleSupported=false`). S23+ facing peer: −8°…+5°; responds to turning (+48° one way, smaller
  swing the other — re-check rotation on the real radar). At <0.3 m azimuth is noise (±90°): ignore it there.
  → Demo: guardian phone = S23+ (shows the arrow); S24U gets UWB distance only.
  Re-run: `./gradlew installDebug` on both, UWB ON, airplane OFF → `bash locate/tools/uwb_check.sh`.
- Files: `locate/src/debug/.../UwbCheckActivity.kt`, `locate/src/debug/AndroidManifest.xml`,
  `locate/src/main/AndroidManifest.xml` (UWB perm + overrideLibrary), `locate/tools/uwb_check.sh`, `locate/build.gradle.kts`.
- `AndroidLocateEngine` written + wired in `LocateModule` (used when simulation=false). Fused GPS 1 s,
  rotation-vector heading (works flat or upright) + true-north declination + smoothing, `track()` = GPS → RSSI
  → stale GPS. `RadarMath` pure maths has 6 passing JVM tests. **Not yet tested on a phone** (S23+ is with MESH).
  `uwbAvailable=false` until UWB is wired into `track()`.
- On-phone test when S23+ is back: needs a sim=false build (ask UI how to toggle) → check heading vs a real compass
  outdoors, `adb logcat -s Proxima:*` shows "declination" and "fused location started".
- **UWB mode wired into `track()` (16:15, builds, 11 JVM tests green, NOT yet run on phones).** `UwbRanger`:
  tracker = controller sends CONTROL/"uwb" `UwbControl` REQUEST (sessionId, addr, channel, preamble, random STS key)
  → target's always-on responder (started in `start()`) answers ACCEPT and ranges as controlee → tracker ranges.
  Retries every 3 s, STOP on cancel. `track()` chain is now UWB (<1.5 s) → GPS → RSSI → stale GPS.
  `uwbAvailable` = hasUwb && API 31+.
- **Needs for a phone test:** simulation OFF + MESH's real Nearby engine delivering CONTROL packets (fake mesh
  can't link two real phones). 2 UWB phones (S23+ as guardian). Check: arrow direction — if mirrored, flip
  `RadarMath.UWB_AZIMUTH_SIGN` to −1.
- Next: help-point bearing helper for the guided-response card (coordinate with UI). Venue data must include
  assembly points (TRUST: OSM has none).

## Progress log
| Time | What |
|---|---|
| 15:00 Fri | Onboarding done: lane branch pushed, app installed on S23+ in SIM mode |
| 15:46 Fri | UWB device-check tool (debug-only activity + adb script) builds; waiting for 2 phones |
| 15:54 Fri | AndroidLocateEngine (GPS + true-north heading + RSSI fallback) builds, RadarMath tests pass |
| 16:07 Fri | UWB device check passed: distance ±0.1–0.3 m on S23+ & S24U; azimuth only on S23+ |
| 16:15 Fri | UWB radar mode (mesh CONTROL handshake + UwbRanger) in `track()`; builds, 11 tests green |

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
