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
- [~] (coarse 25 m cell + llmReady coded; device check pending) Beacons every ~10 s (`PacketKind.BEACON`, `Beacon` payload, coarse location) → `peers` includes indirect nodes (`direct=false`).
- [x] **Demo multi-hop control:** neighbour allow-list so phones in one room form a chain A→B→C. Expose e.g.
      `object MeshDemoControls { val allowedNeighbours: MutableStateFlow<Set<String>?> }` and tell UI via a `request` issue.
- [x] `selfNodeId` must equal `trust.identity.nodeId` (stable ids pending TRUST #19).
**P2**
- [~] (FskModem + 16-byte SOS + engine wiring, off by default; 16 JVM tests on synthetic audio; phone check pending) Acoustic FSK chirp fallback (AudioTrack/AudioRecord) for a tiny SOS packet when RF is unavailable.
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

## Backup demo video plan (MESH films 09:30 Sat, everyone acts)
**Goal:** ~2 min, outdoors (GPS works; Nearby range is visible), **simulation OFF**, every phone in airplane mode with BT + Wi-Fi + Location ON.
Same story as the stage demo, but lost & found follows #14 (lost-person alert, "I'm with them"; no wristband).
**Slot:** 09:00 pre-flight · 09:30–10:15 film · 10:15–10:45 edit · link to TRUST for the README (#23) by 11:00.

**Cast & devices** (camera = Chanum's S26+, NOT in the mesh)
| Who | Device | Role |
|---|---|---|
| Seeya (AI) | S24U | SOS caller; then the **finder** (UWB distance only) |
| (A56 holder) | A56 (Tier 2) | 2nd report of the same event → AI offload |
| Yorvan (LOCATE) | S23+ | **Guardian** (only phone with the UWB arrow, #5) |
| Mikhil (UI) | Tab S6 Lite | Command Center: topology, merged incidents, heatmap |
| Diya (TRUST) | Honor X8b | Relay, **in a pocket, screen off**, halfway between the SOS phone and the tablet |
| Chanum (MESH) | S26+ | Films + directs; voice-over afterwards |

**Pre-flight (09:00, 10 min).** Run my shared-phone safety routine first (battery > 50 %, temperature < 40 °C).
- The sim=OFF release build is on every phone, and no "SIM" badge is visible.
- Airplane mode ON, then BT, Wi-Fi and Location turned back ON.
- Screen timeout 10 min; media volume max (TTS); DND off.
- Allow-list chain S24U → Honor → Tab, set in the dev sheet (#8), so the hop is visible.
- Old incidents cleared.
- Every phone shows its peers before rolling.

**Shot list (target 2:00)**
| # | Time | Shot | On screen / action | Voice-over (short) |
|---|---|---|---|---|
| 1 | 0:00–0:10 | Wide: 5 people spread ~40 m apart on open ground | Close-up: airplane-mode icon on 2 phones | "Grand Bassin, Maha Shivaratri. Tens of thousands of people, no network." |
| 2 | 0:10–0:40 | S24U close-up | Hold SOS: "a man collapsed near the food stalls, he's not breathing" → CRITICAL card, `by: on-device AI`; CPR step read aloud; nearest health centre + distance | "One button. On-device AI turns panic into a 200-byte alert and a vetted CPR card." |
| 3 | 0:40–0:55 | Tablet screen | Topology animates S24U → Honor → Tab; the Honor is shown coming out of a pocket, screen off | "It hops phone to phone, even through a phone asleep in a pocket." |
| 4 | 0:55–1:05 | A56 then tablet | Same event reported on the A56 → "2 reports", merged; `triagedBy` shows the offload | "A budget phone borrows a neighbour's AI. The tablet merges duplicates." |
| 5 | 1:05–1:45 | Guardian S23+, then finder S24U ~30 m away | Guardian: "Lost child", description only → finder sees the alert, taps "I'm with them" → guardian gets the sealed alert + radar; walks over; under ~10 m the radar switches to UWB arrow + distance | "No names, no photos. The child's location is sealed so only the guardian can read it." |
| 6 | 1:45–2:00 | Tablet | Heatmap (anonymous 25 m cells) + incident list → closing line | "No servers, no data plan. Proxima: powered to protect." |
| 7 (optional, filmed last) | +0:10 | S26+ sends, S23+ receives, both Bluetooth/Wi-Fi OFF; the Honor films | `acoustic on` on both; the S26+ raises a CRITICAL SOS → the S23+ shows it marked "unverified". It's silent (18 kHz), so subtitle it. Only S26+ → S23+ works (the S23+ speaker is too weak) | "No radio at all? Phones can still pass an SOS by inaudible sound." |

**Rules while filming.** These are product rules too, and judges check them.
- No bystanders' faces. Only teammates who agreed to be filmed.
- An adult teammate plays the "child" in the description.
- Real data only. If a feature isn't working at 09:30, leave it out rather than fake it. Never film SIM mode without its badge.
- Use each phone's built-in screen recorder (media audio on) for screen shots. The camera films the hands and the wide shots, and the two get spliced in the edit (clearer than filming screens).
- Take 2 takes of each shot. Shot 5 first, while batteries and GPS are freshest (it's the riskiest one).
- Afterwards: `cleanup` on any phone that got a test build; restore BT/Wi-Fi state.

**Edit.** Clipchamp (built into Windows 11): 1080p landscape, subtitles for the voice-over, ≤ 2:10.
Upload unlisted and post the link on #1 and #23.

## NEXT_SESSION_HANDOFF
_Saved 18:10 Fri. Nearby mesh is on `main` (PR #27 + #32). Acoustic fallback, the CONTROL no-store fix and the video plan are on `lane/mesh` (PR to main pending)._
- **Works (verified on real phones, simulation off, no internet):** `NearbyMeshEngine` on Nearby P2P_CLUSTER:
  - 2 phones: connect ~6 s, packets both ways ~0.2 s.
  - 3 phones, chain S947B → Honor X8b (Tier 2) → S23+ forced by allow-list: relay hops=1 in 0.09–0.17 s.
  - Reconnect after 15 s radio-off: 31 s. Relay with the relay phone's screen off (Doze ~80 s). Stored packets re-sent after restart.
- **Design:** `MeshRelay` (pure relay/flush rules), `ReconnectPolicy` (backoff 2→30 s, ONE connect attempt in flight,
  forget ghosts after 8 tries), Room `seen_packets` dedup + `stored_packets` store-and-forward (30 min, CRITICAL first),
  beacons every 10 s → indirect peers, `MeshDemoControls.allowedNeighbours` (#8), `MeshBeaconInfo` (anonymous 25 m cell
  location + llmReady in beacons), `MeshService` connectedDevice FGS. CONTROL packets (UWB #24) relay live but are never
  stored (`MeshRelay.isStorable`). 33/33 mesh JVM tests.
- **Acoustic fallback (P2), JVM-tested + partly verified on phones (18:51 Fri, S26+ ↔ S23+ ~table distance):** `mesh/.../acoustic/`.
  - `FskModem`: BFSK at **18.0/18.7 kHz (near-ultrasound, inaudible to most adults)**, 50 bit/s, preamble + sync 0x2DD4 + length + CRC-16, Goertzel demodulation over 8 phases.
  - `AcousticSos`: 16-byte frame (type, severity, 4-byte node id, lat/lon ×1e4, nonce), 3.7 s on air.
  - `AcousticReceiver`: rolling window + 60 s dedup. `AcousticLink`: AudioTrack on the alarm stream, plays the frame twice;
    AudioRecord on UNPROCESSED or MIC (VOICE_RECOGNITION broke decoding on the S23+), NS/AGC/AEC disabled.
  - **OFF by default** (`MeshDemoControls.acoustic`). When ON with no Nearby link, HIGH/CRITICAL sends are also chirped and
    the mic listens (app must be on screen). A heard SOS enters the mesh as an unsigned INCIDENT, which the pipeline shows
    as unverified, and store-and-forward re-sends it over RF later.
  - adb: `mesh_device_test.sh acoustic <serial> on|off` (also drops/restores RF links) and `chirp <serial>`.
  - **Phone results:** 4/5 kHz decoded both ways once MIC was used, but it was piercing and disturbed other teams. 18.0/18.7 kHz decodes
    S26+ → S23+ 2/2, but S23+ → S26+ 0/2: the S23+ speaker is weaker up there (heard ~10–15 dB lower). The restart fix was
    checked once: Nearby reported the disconnect in 2 s and reconnected under the new id in 7 s (Yorvan's silent case not reproduced).
    Watch out: Bixby popping up on the S26+ put our app in the background, and the mic was silenced.
- **Coded, NOT yet verified on phones:** beacon location/llmReady (#28, wired by UI).
- **Waiting on others:** #19 TRUST `TinkTrustEngine` by M2 23:00 → stable 8-hex node ids (until then phones show `SELF-xxxx`
  that change per launch; UI's dev sheet does tap-to-allow from `mesh.peers`, so no stopgap needed). #8 stays open until
  UI's dev-sheet allow-list ships. App still lacks a runtime-permission prompt (UI); location is foreground-only.
- **Exact next steps, in order:**
  1. PR `lane/mesh` → main once CI is green (acoustic is off by default, so it's safe for the demo).
  2. Acoustic: decide with Chanum whether to keep ultrasonic (silent, one-way with the S23+ as sender) or lower it to ~17 kHz
     and test again; otherwise drop it from the demo (it is off by default, so nothing breaks).
  3. When phones are free: verify beacon location (#28) on 2–3 phones; 2-phone UWB radar with LOCATE (#24);
     after #19 lands, re-test the allow-list with stable ids.
  4. 09:00 Sat: run the video pre-flight; 09:30 film per "Backup demo video plan" above.
  5. README MESH section is DONE (PR #47). Update its numbers if M2 or the video shows anything new (e.g. a longer chain, outdoor range).
- **Follow-up (from #24):** after a peer app restart, its old per-launch node id stays `direct=true` on the other phone (new id shows as indirect). Check that `onDisconnected` fires and clears the old endpoint; stable ids (#19) remove most of it.
- **Ask UI (not yet filed):** add a dev-sheet toggle for `MeshDemoControls.acoustic` if we want the acoustic fallback on stage.
- **Device testing:** `bash mesh/tools/mesh_device_test.sh health|setup|ids|allow <serial> <ids|*>|ping <serial>|acoustic <serial> on|off|chirp <serial>|logs [n]|cleanup`.
  Installs side by side as `mu.proxima.app.meshtest` (needs `-Pmeshtest` from the machine-local init script), only
  force-stops teammates' `mu.proxima.app`, `cleanup` uninstalls only the test build. Safety routine: health first
  (battery, temp < 40 °C, storage), note BT/Wi-Fi state and restore it after, keep screens unlocked, `cleanup` at the end.
  Honor hides app logs: `adb -s <serial> shell setprop log.tag.Proxima V` (reset with `""`). TRUST (19:00) saw the Honor LLY-LX2 return 0 logcat lines even so: if it looks dead, check with `dumpsys activity` or the other phones' logs, not logcat. Samsung Secure Folder → always `--user 0`.
  USB hub dropped phones twice: prefer direct cables/powered hub for the stage.
- **Machine notes (Chanum's laptop):** JDK = Android Studio JBR; `~/.gradle/init.d/proxima-builddir-outside-onedrive.gradle.kts`
  puts build output in `C:/dev/proxima-build` (OneDrive locks `build/`) and adds `-Pmeshtest` suffix; no Python installed;
  `gh` at `C:/Program Files/GitHub CLI/gh.exe`, adb at `%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe`.
- **Files:** `mesh/src/main/AndroidManifest.xml`; `mesh/src/main/java/mu/proxima/mesh/{NearbyMeshEngine,MeshRelay,ReconnectPolicy,SeenStore,MeshService,MeshDemoControls,MeshBeaconInfo,MeshModule}.kt`;
  `mesh/src/main/java/mu/proxima/mesh/acoustic/{FskModem,AcousticSos,AcousticReceiver,AcousticLink}.kt`;
  `mesh/src/test/java/mu/proxima/mesh/{MeshRelayTest,StoreAndForwardTest,ReconnectPolicyTest,MeshBeaconInfoTest,AcousticModemTest}.kt`; `mesh/tools/mesh_device_test.sh`.

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
| 17:15 Fri | PR #27 merged to main (CI green). Beacon coarse location + llmReady via `MeshBeaconInfo` (16/16 JVM tests); needs 2-line UI wiring |
| 17:40 Fri | UI wired #28 on lane/ui; TRUST shipped NodeIds format (#19, keystore by M2). PR #32 (beacon location) merged after CI green. Saving protocol |
| 17:55 Fri | Bus: #24 answered (CONTROL already relays; now never stored), 👍 #14 (+ newest-atMs note), ack #30. 17/17 tests |
| 18:00 Fri | Backup demo video plan written (cast, pre-flight, 6-shot list, filming rules, edit) |
| 18:05 Fri | Acoustic FskModem + 16-byte AcousticSos, 13 JVM tests on synthetic audio (noise, echo, drift, CRC) |
| 18:08 Fri | Acoustic wired into NearbyMeshEngine (off by default), AcousticReceiver + mapping tests, adb acoustic/chirp; 33/33, app builds. No phones attached for the device check |
| 18:20 Fri | #24 closed: LOCATE verified UWB CONTROL handshake over the real mesh on S23+ → S26+ (fix 0.5 s, arrow OK). Follow-up: after a peer app restart, its old per-launch id stays `direct=true` until Nearby reports the disconnect |
| 18:51 Fri | Phone check S26+ ↔ S23+: restart fix OK (reconnect 7 s); acoustic decodes with MIC source; 4/5 kHz too loud for the venue → 18.0/18.7 kHz near-ultrasound: S26+→S23+ 2/2, S23+→S26+ 0/2 (weak speaker). Phones cleaned up |
| 19:10 Fri | PR #44 merged (restart fix + ultrasonic acoustic). README MESH section + AI-disclosure row merged (PR #47), #23 answered |
| 19:15 Fri | Validated #51 on its branch (whole project green), found + fixed the restart/LOST-report gap (PR #54 merged), 👍 #51 with "no mid-session rotation before freeze", told TRUST on #19 that MESH is ready for the found-notice test |
| 19:30 Fri | Found (no phones) and fixed store flooding by 10 s finder position updates: newest SEALED per (finder, guardian) only (PR #59; merged before CI by a script slip, CI on PR and on main both passed afterwards). 36/36 tests |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 15:10 Fri | `path` = every node the packet has left, origin first; origin sends `path=[self]` | Last entry is always the previous hop → real `fromNodeId` in HopEvents (#8) and a "never send back along path" loop guard | Empty path at origin (first hop unknown to receiver) |
| 15:12 Fri | Relay rules in a pure `MeshRelay` object | Unit-testable on the JVM before phones arrive; engine stays thin | Logic inline in Nearby callbacks |
| 15:14 Fri | Connect tie-break: smaller nodeId requests, other side retries after 6 s | P2P_CLUSTER phones that both request at once often both fail | Both request at once; only one side ever requests |
| 15:15 Fri | Dedup = in-memory set in front of Room `seen_packets` | Hot path skips disk; Room keeps dedup across restarts | Room only (slow); memory only (re-relays after restart) |
| 15:16 Fri | Multi-hop demo via allow-list `MeshDemoControls.allowedNeighbours` in `:mesh` | Picks exact links for A→B→C in one room; no `core/` interface change | `setMaxDirectPeers(n)` (can't choose which link drops) |
| 16:25 Fri | Device tests install a side-by-side build `mu.proxima.app.meshtest` (machine-local Gradle flag) | Shared phones carry teammates' builds signed by other laptops; replacing them meant uninstalling their data | Uninstall others' builds each time; commit a shared debug keystore (keystores must never be committed) |
| 17:35 Fri | No stopgap for stable node ids before M2 (#19) | UI's tap-to-allow from live `mesh.peers` works with per-launch ids; TRUST's time is better spent on TinkTrustEngine | Persist `FakeTrustEngine.identity` in `core/fake` (needs lead nod, throwaway) |
| 15:17 Fri | No HopEvents for BEACON packets | Beacons every 10 s from every phone would flood the hop visualizer | Animate beacons too |
| 15:46 Fri | `requiredPermissions` lists coarse+fine location on every API level | Seen on device: API 37 Nearby discovery fails 8034 without it | Location only ≤ API 32 (per older docs) |
| 15:47 Fri | Retry advertising/discovery every 10 s while running | Phone locked at start = location denied → discovery never recovered | Fail once and wait for app restart |
| 16:08 Fri | Keep endpoints after disconnect; 1 s loop retries with exponential backoff 2→30 s + 0–1 s jitter; forget after 10 min unseen | Device log: link dropped at 15:48 and Nearby never re-reported the phone as found → link stays dead | Remove on disconnect and wait for rediscovery (never comes); fixed-interval retry (both phones collide) |
| 16:40 Fri | One connection attempt in flight at a time (15 s timeout), fewest-attempts first; forget endpoints after 8 failed attempts; drop stale endpoint ids of a re-found node | Honor X8b log: parallel requestConnection → 8007/8012 errors + radio busy; relay latency 7–10 s → 0.1 s after fix | Parallel attempts with backoff only (still collide); never forgetting ghosts |
| 17:15 Fri | Beacons carry only the centre of the 25 m `GeoMath.cellKey` cell (accuracy = 25 m), set via `MeshBeaconInfo` in `:mesh` which the app wires from LOCATE/AI | Privacy rule: density = anonymous grid cells, raw GPS never leaves the phone; same cells as the heatmap; no `core/` change | Raw GeoPoint (tracks people); rounding at the Command Center (raw position already broadcast); mesh reading GPS itself (duplicates LOCATE) |
| 15:25 Fri | Debug-only adb PING sends CONTROL/`ping`, not an INCIDENT | Tests multi-hop with no UI and no fake incidents in the pipeline (real-data rule) | Fake INCIDENT packets |
| 17:55 Fri | CONTROL packets are relayed live but never stored for store-and-forward (`MeshRelay.isStorable`) | #24: a UWB REQUEST replayed 20 min later to a phone walking into range would start a stale session; LOCATE already retries every 3 s | Store everything (stale handshakes); a separate CONTROL transport (second message system, breaks "one pipeline") |
| 18:00 Fri | Backup video: camera phone outside the mesh, phone screen recordings spliced with handheld shots, riskiest shot (lost & found + UWB) filmed first | Clean screens, no camera phone altering topology, batteries/GPS freshest for the hardest shot | Filming screens with a camera (glare, unreadable); story order (risk running out of time on the key shot) |
| 18:03 Fri | Acoustic modem: BFSK 4/5 kHz, 20 ms symbols, preamble + sync + CRC-16, Goertzel over 8 phases | Long symbols survive echo and speaker ringing; 4–5 kHz sits above crowd/voice energy and is still loud on phone speakers; Goertzel = 2 bins, tiny CPU | Near-ultrasonic 18 kHz (weak on cheap speakers, many mics filter it); higher baud (echo smears symbols); external lib (e.g. ggwave: native code, no time) |
| 18:04 Fri | Acoustic SOS = 16 bytes, unsigned, shown as unverified, then relayed over RF by the hearing phone | A 64 B signature adds ~10 s on air; merging into the normal pipeline keeps "one feature, many uses"; no names/free text | Signed frame (14 s chirp); separate acoustic message system |
| 18:06 Fri | Acoustic is OFF by default and only active when there is no Nearby link; foreground-only mic; no microphone FGS | Never compete with voice SOS for the mic (Android 10+ gives it to SpeechRecognizer anyway) or talk over the read-aloud steps; an always-on background mic is a privacy red flag | Always listening; microphone foreground service |
| 18:46 Fri | Acoustic mic source = UNPROCESSED else MIC (+ NS/AGC/AEC off), not VOICE_RECOGNITION | On the S23+ VOICE_RECOGNITION heard the tones loud but never decoded; MIC decoded both ways | VOICE_RECOGNITION (fails on S23+) |
| 18:50 Fri | Acoustic tones moved to near-ultrasound 18.0/18.7 kHz | Chanum: 4–5 kHz was piercing and disturbed other hackathon teams; ultrasound is inaudible to most adults. 19.5 kHz was too high for the S23+ speaker → S26+ mic path | 4/5 kHz (works both ways, too loud); 1/1.5 kHz (pleasant, did not decode once); 18.5/19.5 kHz (one-way only) |
| 19:13 Fri | After an app restart the mesh replays its stored INCIDENT/SEALED packets (≤ 30 min) once into this phone's own pipeline | Found while validating #51: the pipeline keeps incidents in memory, and the mesh drops a re-forwarded copy of the guardian's own LOST report as already seen, so after a restart the finder's notice was refused. Fixed in `:mesh`, no core change | Forget seen ids on restart (would re-relay everything); persist incidents in core (core change near freeze) |
| 19:39 Fri | Store-and-forward keeps only the newest SEALED packet per (finder, guardian) | A finder shares its position every 10 s as CRITICAL; 180 per 30 min filled the 200-slot store (the original SOS was evicted first) and flooded newly met phones with stale positions, oldest first | Bigger store (still floods); evict lowest severity (notices are CRITICAL too); fewer updates (LOCATE's radar needs them) |
