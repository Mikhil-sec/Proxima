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
- UWB device-check tool written and compiles, **not yet run on hardware**: needs 2 UWB phones on one PC
  (S23+ + S24U/S23+), UWB ON, airplane OFF → `./gradlew installDebug` on both → `bash locate/tools/uwb_check.sh`.
  Record: distance y/n, azimuth y/n, typical error, range. Then log the result here + on #5.
- Files: `locate/src/debug/.../UwbCheckActivity.kt`, `locate/src/debug/AndroidManifest.xml`,
  `locate/src/main/AndroidManifest.xml` (UWB perm + overrideLibrary), `locate/tools/uwb_check.sh`, `locate/build.gradle.kts`.
- Next after the check: `AndroidLocateEngine` (fused location + rotation-vector heading → `track()`).

## Progress log
| Time | What |
|---|---|
| 15:00 Fri | Onboarding done: lane branch pushed, app installed on S23+ in SIM mode |
| 15:46 Fri | UWB device-check tool (debug-only activity + adb script) builds; waiting for 2 phones |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 15:40 Fri | UWB check exchanges session params over adb, not the mesh | Mesh is still a fake; answers "does UWB AoA work" today without blocking on MESH | Waiting for real mesh; manual typing of addresses on-screen |
| 15:40 Fri | Device-check screen lives in `locate/src/debug` | Never ships in release, doesn't touch UI lane's `app/` | Adding a screen to `app/` (not our lane) |
| 15:40 Fri | Static STS key for the check only; real engine will use per-session random key sent sealed | Simplest way to prove ranging works; security handled in the real engine | Provisioned STS (needs FiRa provisioning) |
