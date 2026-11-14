# LANE: TRUST (crypto, wristband QR, real dataset)
Owner: **Diya** (Member 5, @diya-r6) - test phone **Galaxy S24 Ultra** (Tier 1, not the A56 this lane
assumed) - Branch `lane/trust` - Issue `[LANE:TRUST]` #6

## Mission
Make the app trustworthy and real: signed packets, child location only the guardian can read, and the Mauritian dataset that powers "nearest help".

## You own
`trust/` (incl. `trust/src/main/assets/data/`), `scripts/fetch_help_points.sh`, this file, and the final **README assembly**.
Implement `TrustEngine`, `HelpPointRepository`, `ProtocolRepository` and return them from `TrustModule`.

## Tasks
**P0 (M1 18:00)**
- [x] **Dataset first** (it's a hard rule): `bash scripts/fetch_help_points.sh` (OSM Overpass; retries mirrors; it was
      overloaded at 13:40 Fri) → `trust/src/main/assets/data/osm_help_points_raw.json`. Commit the raw file + fetch date.
      Optionally cross-check health facilities with data.govmu.org.
- [x] `AssetHelpPointRepository`: parse OSM → `HelpPoint` (amenity/emergency → `HelpCategory`, source "OpenStreetMap (ODbL)"),
      `nearest()` with `GeoMath`. Unit test with a known location.
- [x] `TinkTrustEngine` (**Context7: Tink**) **DONE 18:10 Fri** - 21/21 on JVM and on the S24 Ultra Keystore (18:36): Ed25519 sign/verify of `MeshPacket.signingBytes()`; HPKE (X25519) hybrid seal/open;
      keysets persisted with the Android Keystore. `identity.publicKey` must carry both public keys (e.g. `sigB64.encB64`);
      `nodeId` = first 8 hex chars of SHA-256(publicKey). Tell MESH it must use this nodeId.
**P1 (M2/M3)**
- [~] Wristband: `wristbandQrBitmap(tag, sizePx)` (ZXing) **DONE** + `WristbandScanner(onTag)` composable (CameraX + ML Kit;
      add the compose plugin to `trust/build.gradle.kts`). Persist minted tags (`myWristbands()`).
- [x] `protocols.json` + `AssetProtocolRepository`: cards **transcribed from an authoritative source** (IFRC International First Aid
      Guidelines 2020 / Red Cross / St John). Never AI-invented. `source` on every card. Suggested: CPR adult, unconscious
      breathing (recovery position), severe bleeding, heat exhaustion, seizure, fracture, fire/smoke, fallen branch cordon,
      crowd crush, lost child (guardian + finder). A second member proof-reads them.
      Subtype codes must match `RuleTriageEngine` (CARDIAC_ARREST, UNCONSCIOUS, BLEEDING, …).
**P2**
- [ ] "Official" node badge (QR-verified volunteer/police keys → green verified).
- [ ] **README assembly** by 10:30 Sat (each lane supplies its section).

## How my part works (5 lines for teammates; fill before 10:00 Sat)
1. **Dataset:** `scripts/fetch_help_points.sh` pulls real Mauritius facilities from OpenStreetMap (ODbL) via Overpass;
   the raw response is committed so the source and fetch date stay verifiable. 524 points: hospitals, clinics,
   pharmacies, police, fire stations, water, toilets.
2. **Nearest help:** `AssetHelpPointRepository.nearest(location, type.helpCategories)` ranks them with `GeoMath`
   (haversine distance + compass bearing) and hands the guided-response screen the closest few.
3. **Keys:** every node has an Ed25519 signing key and an X25519 encryption key; `publicKey` carries both and
   `nodeId` is derived from it, so a node id cannot be forged.
4. **Sealed child location:** a FoundNotice is HPKE-encrypted to the guardian's key, so relays carry it but
   cannot read it. Wristband QR holds only a random tag id plus the guardian's public key - no names, no photos.
5. **Protocols:** guidance cards are transcribed from the IFRC 2020 first-aid guidelines with a cited source.
   The AI only *selects* a card; it never writes a medical step.


## NEXT_SESSION_HANDOFF
_Saved 05:58 Sat 14 Nov. `lane/trust` merged to main (#94). **Code is done; freeze 08:00 (docs only after), submit 12:30.**
Plan: #88; my task issue: #89 (done except the lead's tablet check). Diya: merge without waiting for 👍 but inform;
ask her only for key decisions. **Never install onto demo devices**; adb file pushes are fine._

### State - everything TRUST built is on main and has run on real phones
| Item | Status |
|---|---|
| TinkTrustEngine (Ed25519 + HPKE, Keystore) | 22/22 S24 Ultra, 21/21 Honor. M2 T3 (lost & found, sim OFF): 23 sealed updates relayed by bystanders, none opened |
| Per-session identity (#52) | Live; MESH #54 replays own alerts (<= 30 min) after restart |
| Verified roles (#55, #72, MESH #77, UI) | **Passed on phones 05:55 Sat**: Honor = "Medic tent 2" (pass valid 12 h, to ~17:53 Sat); S24U Nearest help lists it first (5 m). Tablet ring = lead to confirm on #89 |
| Friend find (#61, #62, MESH #78, UI) | **Passed on phones (M2 T6)**: S24U <-> A56 paired, recognised by rotating tag |
| README | Merged (#94): M2 results, friend find + roles claimed, assembly block removed, Vet Clinic limit, 8.5 km fix |
| #79 flaky core test | Fixed (PR #80) |

### THE ONE THING THAT MATTERS NEXT
1. **Check #89** for the lead's tablet result (green ring "Medic tent 2"). If the Honor ever loses the role (reinstall /
   clear data = new identity): plug it into THIS laptop, `adb shell mkdir -p /sdcard/Android/data/mu.proxima.app/files/role
   && chmod 777 <that dir>`, restart app, `adb exec-out cat .../role/holder_key.txt > ~/.proxima/holder_key.txt`,
   `./gradlew :trust:issueRolePass -Prole=MEDIC -Plabel="Medic tent 2" -Phours=12` (NO -Pholder), `adb push` the pass with a
   Windows path (`cygpath -w`), chmod 644, restart. Script: scratchpad `role_phone.sh` (step 1 there uses run-as, which
   fails on the Honor - use the mkdir+chmod above). Delete local holder_key/pass afterwards.
2. **~10:00 Sat:** AI (Seeya) compiles the final 5-10 key decisions in README (#90); help pick TRUST's (blockchain
   rejection, contract-first tests, no uncited crush card, organiser key off-repo, per-session identity).
3. **Before 12:30:** final README proof-read; the `<!-- DRAFT shortlist -->` comment in Key decisions is AI's to remove.
4. Open but not blocking: #45 protocol proof-read (AI); friend-storage instrumented run (#53 ask, unanswered; friend find
   already passed on phones, so low value now). Deprecated `TinkProtoKeysetFormat` calls stay (no wire change).
5. **Organiser private key** `~/.proxima/organiser_private.json`: never commit/post; back it up (USB) before the demo.

### Gotchas learned this session
- Connected tests print no per-test lines; read `adb logcat -d -s TestRunner:*`. The Honor has logcat OFF entirely:
  use `adb -s <serial> shell am instrument -w -r mu.proxima.trust.test/androidx.test.runner.AndroidJUnitRunner`.
- Honor LLY-LX2 = TIER1 in the app (7.47 GiB >= 7.0): neither test phone exercises Tier 2 (told UI on #2).
- `./gradlew :trust:test --tests` fails; use `:trust:testDebugUnitTest --tests "..."`. `--rerun` to force tests.
- Contract test lives in `src/testFixtures` (AGP forbids sharing a dir between test and androidTest); method names use
  underscores (D8 rejects spaces below DEX 040).
- `javap` on the Tink jar is refused by the permission layer: Context7 only for Tink APIs.

### What works, merged to `main`, verified
- **Dataset** `trust/src/main/assets/data/osm_help_points_raw.json` - 524 real Mauritius OSM features (ODbL),
  fetched 14:43 Fri, OSM base `2026-11-13T10:42:12Z`. Raw Overpass response committed for provenance.
  Re-fetch with `bash scripts/fetch_help_points.sh`.
- **Nearest help** `OsmHelpPoints` (pure parser + `rankNearest`) and `AssetHelpPointRepository`, wired in
  `TrustModule.createHelpPoints`.
- **Protocols** `assets/data/protocols.json` - 9 cards transcribed from the real IFRC 2020 PDF with a page
  citation each. `AssetProtocolRepository` + `Protocols.bestFor`, wired in `TrustModule.createProtocols`.
- **`NodeIds`** - `derive` / `publicKey` / `split`, plain SHA-256, 3 fixed vectors pinned (#25).
- **`WristbandQr`** - works, round-trip tested, but **UNUSED**: the wristband was dropped at 15:35 (#14).
  Marked in its file header. Delete it if the lead says so.
- **README** assembled and merged (#22). It is a TRUST deliverable.
- **34 unit tests**, all passing. `./gradlew :trust:test`.

### Tests that exist to protect other lanes - do not weaken them
- `NodeIdsTest` - fixed vectors MESH's #8 allow-list depends on.
- `ProtocolsTest > card ids the AI guide switches by are stable` - #30 switches cards by id.
- `ProtocolsTest > every subtype the live engine emits...` - drives the real `RuleTriageEngine`, so an
  AI-lane subtype rename fails here.
- `TrustEngineContractTest > verify still passes after a legitimate relay` - protects MESH's relay path
  (`ttl` / `hopCount` / `path` must stay outside `signingBytes()`).

### Known gaps, all deliberate and logged
1. ~~Simulation-OFF is not demo-safe.~~ **Fixed 18:10 Fri** by `TinkTrustEngine`, device-verified 18:36. Also: two `TinkProtoKeysetFormat` calls are deprecated in Tink 1.24; Context7
   does not document the replacement, so they stay, with warnings visible, until it can be checked.
2. **No CROWD_CRUSH / ELECTRICAL / FALLEN_BRANCH / FLOOD card - DECIDED 18:00 Fri, do not revisit.**
   Approved by Diya: we ship without them. IFRC 2020 has zero crush content and UK HSE is organiser
   prevention only, so there is nothing citable. `bestFor` returns null by design. **Do not invent these.**
   The only remaining work is UI-side: render a hazard with `protocolId == null` and no steps panel (#20,
   left open for UI to confirm). If someone produces a Mauritian source - St John Ambulance Mauritius, the
   Fire and Rescue Service, or a printed Ganga Talao briefing sheet - a photo is enough and I can transcribe
   a cited card in under an hour.
3. **`protocols.json` has NOT been proof-read by a second member.** The lane requires it before the demo.
4. ~~This lane has never run on a phone.~~ Keystore contract passed on the S24 Ultra 18:36 Fri. `installDebug` of the full app still untried by this lane.
5. OSM Mauritius has no assembly points, ambulance stations or defibrillators. `docs/PRODUCT.md` still
   wrongly claims defibrillators - not my file, flagged in PR #22.

### My open asks on other lanes
- **#11** UI - nearest-help must render kilometre distances (Ganga Talao's nearest help is 8.5 km).
- **#20** UI - CROWD_CRUSH card decision is **settled** (no card). Open only until UI confirms it
  renders a hazard with `protocolId == null` cleanly.
- **#23** all lanes - one README section each by 10:30 Sat. **I compile it, so chase this.**

### Machine notes
- `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` (JDK 25, works fine).
- `gh` is not on PATH: `export PATH="$PATH:/c/Program Files/GitHub CLI"` and set `GH_PAGER=cat`,
  or `gh issue view` prints nothing.
- `build/` is deny-listed, so `:trust:test` prints per-test results to the console instead. Plain
  `assembleDebug` can report success from cache in ~3 s - use `--rerun-tasks` when you need real proof.
- `local.properties` uses forward slashes. Writing it with `printf` and backslashes silently mangles the path.

### Files I own
`trust/**`, `scripts/fetch_help_points.sh`, `docs/lanes/TRUST.md`, my row in `docs/TEAM_ROLES.md`,
and `README.md` (assembly). **No `core/` changes have been made by this lane at any point.**

## Progress log
| Time | What |
|---|---|
| 14:30 Fri | Onboarding checklist run. Machine was bare: only git. Installed GitHub CLI via winget; fixed a typo in the global git email (`diyaramkalwon6@` → `diyaramkalawon6@gmail.com`) so commits attribute correctly. |
| 14:38 Fri | `lane/trust` branch created and pushed (push succeeded → collaborator access confirmed, despite `doctor.sh` reporting "repo not reachable", which only reflects `gh` being logged out). TEAM_ROLES row filled. |
| 14:43 Fri | **P0 dataset done.** `fetch_help_points.sh` → 524 real OSM features for Mauritius, committed raw with fetch date. Counts: pharmacy 169, toilets 117, police 85, clinic 53, hospital 42, doctors 31, fire_station 11, drinking_water 6; healthcare tags add hospital 34 / clinic 42 / centre 7. |
| 14:44 Fri | Context7 MCP added and reporting Connected. |
| 14:52 Fri | `OsmHelpPoints` (pure parser + `rankNearest`) and `AssetHelpPointRepository` written; `TrustModule.createHelpPoints` now returns the real repository. 7 unit tests against the real dataset. **Not compiled yet** - no SDK. |
| 14:55 Fri | Found that `IncidentType.helpCategories` never routes to `PHARMACY`/`WATER`/`TOILETS` (292 of 524 points). Resolved without a `core/` change: UI passes explicit categories. Not folded into `MEDICAL` - see decision log. |
| 14:56 Fri | Android Studio installed via winget (JBR = JDK 25). SDK still pending the first-run wizard. |
| 15:10 Fri | SDK installed (platform android-37.0, build-tools 36.0.0, adb). `local.properties` written. |
| 15:12 Fri | **`./gradlew :trust:test` BUILD SUCCESSFUL - 8/8 tests pass, no compiler warnings.** First compile of this lane's code. |
| 15:20 Fri | PR #12 merged to `main` - dataset + help-point repository integrated. Merged `origin/main` back in first, resolving 2 conflicts from Mikhil's lane-assignment commit. |
| 15:25 Fri | `wristbandQrBitmap` + `WristbandQr.matrix` + camera permission in `trust/AndroidManifest.xml`. Round-trip test decodes the symbol back to the same `WristbandTag`. Half of #7. |
| 15:30 Fri | Raised #13 (`interface-change`): `TrustEngine` is the only engine with no `requiredPermissions`, so #7's "boot screen asks for camera" cannot work as specified. Offered a no-core-change option B. |
| 15:45 Fri | **`protocols.json` done** - 9 cards transcribed from the real IFRC 2020 PDF (downloaded, 133 pages, text-extracted), citation on every card. `AssetProtocolRepository` + `Protocols.bestFor` wired into `TrustModule`. 22/22 tests pass. |
| 15:50 Fri | PR #17 merged (wristband QR + protocol cards). |
| 15:55 Fri | **Lead dropped the QR wristband (#14).** 👍 given. Removed CAMERA from `trust/AndroidManifest.xml` (PR #18, merged), closed my #13, commented on the cancelled #7. `WristbandQr` kept but marked UNUSED. Flagged to the lead that `FoundNotice.tagId` now carries a lost-incident id, so the field name misleads. |
| 16:00 Fri | Diagnosed why Context7 never loaded: `claude mcp add` had registered it in **local/project** scope under the key `C:/Users/.../Proxima` (capital C), but sessions can resolve the cwd as `c:/...` (lowercase), which is a different project entry in `~/.claude.json`. Re-registered at **user scope**. |
| 16:05 Fri | Answered #19 (MESH, stable nodeId). Found the real cause: **`TrustModule.createTrust` ignores the `simulation` flag** and always returns `FakeTrustEngine`, so simulation-OFF runs fake crypto - `seal()` is base64 not ciphertext, `verify()` accepts any non-null signature. Flagged on #1 and #6 as not demo-safe. |
| 16:15 Fri | Searched for a citable CROWD_CRUSH source: IFRC 2020 has **zero** hits for stampede / trampling / compressive asphyxia; UK HSE event-safety is organiser prevention only; general web is blogs and law firms. Raised #20 asking the lead to decide, recommending no card + route the casualty through the existing cited CPR cards. |
| 16:20 Fri | **README assembled and merged (#22).** Filled problem & user, both data sources (licence, fetch date, counts, gaps), tech stack, how to run, privacy, AI disclosure, draft decision shortlist. Corrected the claims #14 invalidated (no wristband, no camera, CameraX/ML Kit/ZXing out of the stack). Appended 3 team decisions to `docs/DECISIONS.md`, which still only had the superseded 13:20 wristband entry. Raised #23 asking each lane for its section by 10:30 Sat. |
| 16:30 Fri | **`NodeIds.derive` merged (#25)** - the format half of #19, plain SHA-256, no Tink, so MESH can build the #8 allow-list now. 3 fixed vectors pinned. 28/28 tests pass. |
| 16:40 Fri | Merged 23 commits from `main` (clean, no conflicts); `assembleDebug test` green across all modules. Noticed AI changed `RuleTriageEngine` precedence (#16), so replaced my hand-transcribed coverage check with one that **drives the live engine**: 16 trigger phrases, all classify as assumed. 29/29 tests. Merged as #29. |
| 16:50 Fri | Direction change proposed (blockchain instead of the crypto) and **rejected** after laying out the costs; staying with Ed25519 + HPKE. |
| 17:00 Fri | **`TrustEngineContractTest` + `FakeTrustEngineSecurityTest` merged (#31).** The spec `TinkTrustEngine` must satisfy, written before the implementation, plus 4 passing tests that prove the fake accepts forged signatures, misses tampering, and base64s instead of encrypting. 33/33 tests. |
| 17:50 Fri | 👍 #30 (AI: GuideEngine + CrowdRiskEngine). Their guide switches cards **by id**, so pinned `cpr-adult` / `unresponsive-breathing` / `severe-bleeding` / `seizure` with a test - card ids are now a cross-lane API. Warned them feature D raises `CROWD_CRUSH`, which has no card. Escalated #20. 34/34 tests. |
| 18:00 Fri | CROWD_CRUSH decision **approved**: no card, UI renders hazards with `protocolId == null` and no steps panel. Confirmed on #20; left open until UI confirms the rendering. |
| 18:05 Fri | Context7 confirmed loaded (`resolve-library-id` / `query-docs`). Tink APIs checked there; a `javap` look at the jar was refused by the permission layer, so anything Context7 did not document was designed around rather than guessed. |
| 18:10 Fri | **`TinkTrustEngine` done and wired** for simulation OFF. Contract moved to `src/testFixtures`, strengthened (cross-device, impersonation, key substitution, bit-flip, unusable key) and passing 21/21 on the JVM. 51/51 trust tests; whole project green. Instrumented test compiles; no device to run it. |
| 18:12 Fri | Found + fixed two build traps on the way: AGP 7.2+ forbids sharing one test dir between `test` and `androidTest` (-> test fixtures), and D8 rejects backtick test names below DEX 040 (-> underscores). Test logging now prints failure messages. |
| 18:36 Fri | **First on-device run of this lane.** `:trust:connectedDebugAndroidTest` on the S24 Ultra (Android 17): 21/21 pass under the real Android Keystore, incl. `simulationOffUsesTheRealEngine`. Read via the `TestRunner` logcat tag since Gradle prints no per-test lines for connected tests. |
| 18:50 Fri | Keystore contract **21/21 on the Honor LLY-LX2** (Android 14) too, via `am instrument` since that phone logs nothing to logcat. Found that the app classes it **TIER1** (7.47 GiB >= 7.0), so neither test phone is Tier 2 - reported to UI. Two-phone mesh test deferred until all lanes are on one build. |
| 19:00 Fri | Bus: closed #11, nudged #23 (UI/MESH/AI owe sections), opened #45 (AI to proof-read protocols), told UI the Honor is TIER1 (#2), warned MESH the Honor has no logcat (#19). Docs merged (#43). |
| 19:05 Fri | Answered #38: per-session identity + roles tonight, friend find only if both merge by ~01:00. |
| 19:08 Fri | **Per-session identity part A merged (#50)**, no runtime change. 78/78. Part B (core) up as interface change **#51 / PR #52**, held for 👍. Restart test proven to fail on the old pipeline. |
| 19:13 Fri | Diya authorised merging interface changes without waiting for 👍 (inform instead). Merged #52: **per-session identity live**. Informed on #51 and closed it. No phone connected for the device run. |
| 19:20 Fri | **Verified roles** core + TRUST merged (#55, PR #56): 9 tests (replay, forgery, edits, expiry, no key). |
| 19:30 Fri | Organiser keypair generated on the laptop (public shipped, private off-repo), issuer for the organiser phone, guard test that the shipped key is public-only. PR #57. 88/88. MESH/UI asked on #55 to wire it, optional. |
| 19:47 Fri | Everything merged before the 19:45 cutoff (#58); 👍 on #53. **M2 step 3 PASS: 22/22 on the S24 Ultra** with per-session identity under the real Keystore, built from main. Paused until main reopens. |
| 19:53 Fri | **Friend find built** on `iface/friend-find` (11 tests, 99/99): rotating HMAC beacon tags, 6-digit pairing code, sealed invites bound to the confirmed peer, friend book AES-GCM under the Keystore. Interface #61, PR #62 held until main reopens. |
| 20:15 Fri | Pulled main `fb20894` (#68), build + 88/88 green, reported on #68. `iface/friend-find` merged with fb20894, 99/99, pushed (PR #62 still held). README updated on `lane/trust` (per-session identity, Ed25519 detail, device results, section status). Saving protocol. |
| 20:28 Fri | Main still frozen, no M2 results yet. **MESH #54 fixed a gap my #52 restart test missed**: `LostAndFoundTest`'s LoopMesh does not dedup, so it passed, but the real mesh dropped the re-forwarded lost report as already seen. MESH now replays stored INCIDENT/SEALED packets (<= 30 min) into the phone's own pipeline on start. README restart claim narrowed to that 30-min window. |
| 20:38 Fri | Main reopened (20:25). **Friend find merged (#62), README merged (#70).** 99/99, project green. Informed #61, #38, #1. No phone connected for the friend-storage device run. |
| 20:43 Fri | No M2 results posted yet (README result pending). AI delivered its README sections; only UI owes #23. 👍 on MESH #69 (roles in beacons) and #61 (friend tag in beacons) after checking both against the TRUST rules. |
| 20:47 Fri | **Role mode merged (#72)**: stable identity for role phones, laptop pass issuer with a check against the shipped public key (smoke run proved the key pair matches), adb delivery. 101/101. Wiring line + adb steps on #55. |
| 20:49 Fri | Saving protocol. No M2 results posted yet. Corrected two log times that had been written ahead of the clock. |
| 20:51 Fri | Session resumed. Context7 confirmed (`/tink-crypto/tink`); it still documents no replacement for the 2 deprecated `TinkProtoKeysetFormat` calls, so they stay (wire format must not change before freeze). No M2 results, so no TRUST fix is due. Asked the lead on #53 for the S24 friend-storage test run; #23 chased (only UI owes a section). |
| 22:43 Fri | **#79 fixed (PR #80 merged, CI green)**: MESH's CI flake in `LostAndFoundTest` restart test. Cause: `Thread.sleep(100)` raced SharedFlow subscriptions (no replay), so `relay-1` was dropped on a slow runner. `LoopMesh.awaitReady()` waits on `onSubscription` + `subscriptionCount`; timeouts 2 s -> 5 s. Test-only. 5/5 reruns, whole project green after merging #77/#78. |
| 05:55 Sat | **#89 done.** Honor = **Medic tent 2** (12 h pass): Developer sheet shows the verified role; S24 Ultra's Nearest help lists it first (5 m). #88's `run-as mkdir` fails on Android 14 (no /storage access for run-as); a shell-made `role/` with chmod 777 works, the app then owns holder_key.txt. README (#94): M2 results, friend find + roles claimed as tested on phones, assembly block removed, Vet Clinic (OSM mis-tag) noted, item 7 corrected to 8.5 km. |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 14:40 Fri | TRUST tests on a Galaxy S24 Ultra, not the planned Galaxy A56 | It is the phone the member actually has; waiting on hardware would burn P0 time | Borrowing an A56 (none available); swapping lanes with AI (would discard TRUST onboarding already done) |
| 14:41 Fri | Because of that, the "crypto + QR run on mid-range" claim must be proved on the UI lane's Honor X8b, not on the TRUST phone | The S24 Ultra is Tier 1 (RAM ≥ 7 GB) per `docs/ARCHITECTURE.md`, so it proves nothing about Tier 2 | Dropping the mid-range claim from the pitch (it is a real differentiator for a crowd where most phones are mid-range) |
| 14:43 Fri | Commit the **raw** Overpass response verbatim and parse it at runtime, rather than committing a pre-transformed `help_points_mu.json` | Provenance: judges and teammates can re-verify the ODbL source and the fetch timestamp against OSM; transformation logic stays reviewable in Kotlin and unit-testable | Transforming at fetch time in bash (loses provenance, hides the mapping, untestable); hand-curating the list (not "real data only") |
| 14:44 Fri | `ASSEMBLY_POINT` / `MEETING_POINT` / `AMBULANCE` / `DEFIBRILLATOR` will NOT come from OSM | The Mauritius extract contains zero such features (`emergency` has only 6 `yes` values) — pretending otherwise would ship empty categories | Querying a wider bbox (same gap); inventing assembly points (violates "real data only") |
| 14:52 Fri | Keep the distance/bearing ranking in the pure `OsmHelpPoints.rankNearest`, with `AssetHelpPointRepository.nearest()` as a one-line delegate | `nearest()` is the logic judges and the guided-response screen depend on; this way it is covered by a plain JVM unit test instead of needing an instrumented device or Robolectric | Robolectric (extra dependency, slow, and the build does not compile here yet); testing only the parser and leaving ranking unverified |
| 14:53 Fri | Unnamed OSM features keep a category label ("Pharmacy", "Police station") instead of being dropped | ~180 of 524 real facilities have no `name` tag; hiding a pharmacy 100 m away because OSM never named it would be worse than showing it by category | Dropping unnamed features (loses a third of the dataset); showing a blank name (unusable for a panicking parent) |
| 14:54 Fri | Asset stays `data/osm_help_points_raw.json`; no derived `help_points_mu.json` is generated | One file, one provenance chain; `docs/ARCHITECTURE.md` names `help_points_mu.json` but that is a doc in another lane's path, so it is flagged on the bus rather than edited silently | Generating a second trimmed asset at build time (two files to keep in sync, and the raw one still has to ship for ODbL) |
| 14:55 Fri | Do NOT add `PHARMACY` to `IncidentType.MEDICAL.helpCategories`; UI queries it as a separate non-emergency list instead | Ranking is by distance alone, so a pharmacy 80 m away would outrank a hospital 800 m away for a cardiac arrest - that is bad guidance, and "AI selects, never authors medical advice" cuts the same way for routing | Adding PHARMACY to the MEDICAL set (unsafe ordering); making `helpCategories` severity-aware (a `core/` interface change for something the UI can already do by passing explicit categories) |
| 15:25 Fri | Wristband QR uses error correction **M**, not H | The payload is ~150 bytes because it embeds the guardian public key; at H the symbol is dense enough that a wristband printed at ~25 mm drops below the ~0.5 mm per module a phone camera can resolve, so the "more robust" setting is the one that fails to scan | H (unscannable at wristband size); shortening the payload (needs a `core/` change to `WristbandTag`) |
| 15:25 Fri | QR is rendered dark-on-light even though the app is OLED black | A finder may scan with a non-Proxima app and inverted QR codes are not universally supported | Matching the dark theme (risks an unreadable code in the one moment it matters) |
| 15:40 Fri | Transcribe protocols from the **actual IFRC PDF** (downloaded and text-extracted), not from web pages via WebFetch | WebFetch answers through a summarising model, so what comes back is already a paraphrase - transcribing a paraphrase is precisely what "never AI-invented" forbids. The PDF gives the exact wording and page numbers | WebFetch summaries of redcross.org / IFRC pages (paraphrase, uncitable); writing the steps from memory (explicitly forbidden) |
| 15:45 Fri | Leave CROWD_CRUSH, ELECTRICAL, FALLEN_BRANCH and FLOOD **without** protocol cards, and let `bestFor` return null | The IFRC 2020 guidelines contain no protocol for them. Showing an unrelated card to someone standing over a casualty is worse than showing none, and inventing one breaks the lane's core rule | Inventing cards (forbidden); mapping CROWD_CRUSH to the generic assessment card (actively misleading for a crush, where the scene is the danger) |
| 15:45 Fri | Split the seizure "call EMS" step into two steps | It was 322 characters, over the speakable limit the test enforces. Splitting keeps every condition from the source and matches how IFRC lists them as sub-items a-i | Truncating the list (drops clinical conditions); raising the length limit (TTS reads it aloud to someone under pressure) |
| 15:55 Fri | Remove `CAMERA` from the trust manifest the moment the wristband was dropped, rather than leaving it until the scanner question settled | An app that requests a permission it never uses undercuts the privacy story that is explicitly being judged, and it was already merged to `main` in #17 | Leaving it in case the scanner came back (would have shipped an unjustified permission); waiting for the lead to chase it (it was my file and my merge) |
| 15:55 Fri | Keep `WristbandQr` + its test as dead code, marked UNUSED in the header with the date and reason | ~77 lines, self-contained, round-trip tested, zero runtime cost, and the surprise card could still want a printable wristband. The header stops anyone at 03:00 thinking it is live | Deleting it (git history keeps it, but re-adding costs time we may not have); leaving it unmarked (someone would wire it up by mistake) |
| 16:00 Fri | Register Context7 at **user** scope rather than project scope | Project-scoped MCP entries are keyed by the literal cwd string, and the drive-letter casing varies between sessions, so the server silently did not load and I could not write Tink. User scope is casing-proof | Re-adding at project scope (same bug would recur); working without Context7 (CLAUDE.md section 3 forbids it for Tink) |
| 16:05 Fri | Report the simulation-OFF crypto gap to the whole team immediately rather than quietly fixing it inside TinkTrustEngine | The demo rule is "final demo = simulation OFF", and the team may have assumed that already yields real signing and sealing. It does not, and #14 just made lost & found depend on sealing - the lead needs that in his demo planning now, not when I finish | Fixing it silently (the team would keep believing a claim that is currently false); leaving it in my lane file only (nobody reads another lane's file mid-sprint) |
| 16:15 Fri | Do not write a `CROWD_CRUSH` card at all, and route a crush casualty through the existing IFRC-cited CPR / unresponsive cards instead | No authoritative source for crush response exists in IFRC 2020 or UK HSE, and the only web sources are blogs and law firms. A crush casualty is clinically an unresponsive or non-breathing patient, which is already covered by cited cards, so the gap is *scene* guidance, not medical guidance. This is the card most likely to be read aloud to someone in real trouble, so an uncited one is the worst possible option | Writing crush steps from model knowledge (violates the lane rule, highest-stakes card); citing a blog or a law-firm page as authoritative (dishonest `source` field); mapping CROWD_CRUSH to the generic assessment card (actively misleading - in a crush the scene is the danger, not the patient) |
| 16:20 Fri | Put a **blocker note inside the README** saying its privacy/signing claims are not yet true in the build, rather than writing softer claims now | The claims will be true once `TinkTrustEngine` lands, and weakening them pre-emptively would undersell the product; but shipping an untrue privacy claim is the one thing judges will actually test. A note the team must act on is the honest middle | Writing the claims as if already true (dishonest); writing hedged claims now (undersells a design that should work by M2); leaving the sections empty (the README is due 10:30 Sat) |
| 16:20 Fri | Lead the README with "at Ganga Talao the nearest medical facility is 8.5 km away" | It turns our own dataset into the argument for the product - help is far and the network you would use to call it fails first. A judge remembers a number from real data far better than "networks get congested" | Opening with the mesh architecture (impressive to engineers, not to a judge asking "why does this matter"); hiding the distance because it sounds like a weakness |
| 16:30 Fri | Split `NodeIds.derive` out of `TinkTrustEngine` and ship it on its own | It is plain SHA-256 with no Tink, so it was not blocked by the Context7 gate, and MESH was waiting on the format for the #8 allow-list. Shipping the contract early with pinned vectors unblocks another lane hours before the crypto lands | Waiting to deliver it inside `TinkTrustEngine` (MESH idle meanwhile); giving MESH the format in an issue comment only (no test, nothing to stop a silent change breaking their allow-list) |
| 16:40 Fri | Replace the hand-transcribed subtype list in `ProtocolsTest` with a test that drives the real `RuleTriageEngine` | The old test only asserted what I believed the engine emits, so a rename or reordering in the AI lane would have kept it green while the guided-response screen quietly stopped finding cards. That lane edited the engine's precedence the same afternoon (#16), so the risk was live | Keeping the hand-transcribed list (silent drift); asking AI to notify me on subtype changes (relies on a human remembering at 03:00); parsing the engine's source in a test (brittle) |
| 16:50 Fri | **Keep Ed25519 + HPKE; do not replace the trust layer with a blockchain** | A blockchain is a replicated *public* log: it gives integrity but zero confidentiality, so a child's location on a ledger is the exact inverse of the product's headline privacy rule. It also needs consensus, and consensus needs connectivity - the one thing Proxima assumes is absent. And blockchains are *built on* Ed25519-style signatures, so it would not have removed any of the work, only added to it at 16:50 Fri with freeze at 08:00 | Replacing sign/seal with a ledger (breaks privacy and cannot run offline); adding a signed `prevHash` chain for tamper-evidence on top (defensible and offline-safe, but a `core/` interface change after the last P0, so deferred rather than dismissed) |
| 17:00 Fri | Write the `TrustEngine` security contract as an abstract test **before** implementing Tink, and pin the fake's insecurity as passing tests | Two reasons. The contract stops a rushed 03:00 implementation shipping without tamper-rejection or confidentiality, which is exactly what gets dropped under time pressure. And a comment saying "the fake is insecure" never appears in anyone's build, whereas a test does - and it will flip to failing the moment the real engine lands, which is a free reminder to delete it | Implementing first and testing after (the untested property would be the one that matters); documenting the fake's weakness in the lane file only (invisible to other lanes) |
| 17:50 Fri | Treat the protocol card **ids** as a published cross-lane API and enforce them with a test | #30's hands-free guide switches card by id on a voice command ("he stopped breathing" -> `cpr-adult`). That turns an internal string into an integration contract, and the failure mode is silent - the voice command just stops working, probably mid-demo at 03:00. A test makes a rename break my build instead | Promising it in an issue comment (nothing stops a later rename); asking AI to switch on subtype instead (their condition changes do not map to triage subtypes) |
| 18:00 Fri | **APPROVED by Diya: ship with no `CROWD_CRUSH` protocol card** (option A on #20). `bestFor` returns null and the UI renders the hazard with no steps panel | No authoritative source exists - IFRC 2020 has zero crush content, UK HSE is organiser prevention only, the rest is blogs. Confirming the default as a deliberate decision rather than letting it stand by silence, so the team can build against it before the 08:00 freeze instead of waiting on a source that is not coming | Option B, waiting for a Mauritian source (St John / Fire & Rescue) - still welcome if one appears, I can transcribe in under an hour; inventing the card (forbidden, and the highest-stakes card in the set) |
| 18:05 Fri | Public keys travel as **Tink public keysets serialized without secrets** (317 chars), not raw 32-byte keys | Importing raw key bytes needs `Ed25519PublicKey.create` / `HpkePublicKey.create`, which Context7 does not document, and CLAUDE.md forbids writing crypto APIs from memory. The keyset format is documented, and Tink itself parses a peer's key, so there is no hand-rolled byte handling on attacker-supplied input | Raw 32-byte keys (88 chars total, but undocumented factories); JSON keyset format (documented, but roughly twice the size in every packet) |
| 18:05 Fri | `verify()` also requires `originNodeId == NodeIds.derive(originKey)` | A signature only proves *some* key signed the packet. Without this check an attacker signs validly with their own key while claiming the victim's node id, which defeats MESH's allow-list (#8) and the README's "a node id cannot be forged" claim. Pinned by an impersonation test | Trusting `originNodeId` as sent (forgeable); keying the allow-list on full public keys (MESH already built it on node ids) |
| 18:05 Fri | `seal()` to an unusable recipient key **throws** instead of returning something | The interface returns a String, so a quiet fallback is tempting, but the only safe failure for a child's location is to not send it. A crash in testing is far better than a readable notice on the mesh | Returning an empty/marker string (the pipeline would mesh it as if sealed); falling back to the fake's base64 (exactly the bug this replaces) |
| 18:08 Fri | Put key storage behind a `KeysetStore` seam and run the full security contract on the **JVM**, with the Keystore covered by a separate instrumented test | No phone has ever been connected to this laptop, so an instrumented-only spec would mean shipping crypto whose security properties were never executed. The JVM run still re-serializes keysets on every load, so "restart" is real | Instrumented-only tests (would have run zero times before the freeze); Robolectric (new dependency, and its Keystore is a shadow, so it proves nothing more) |
| 18:10 Fri | Delete `FakeTrustEngineSecurityTest` even though it still passes | It constructed the fake directly, so it could never have flipped to failing when Tink landed, which was its whole purpose. Its real job (catching simulation-OFF handing out the fake) now belongs to `simulationOffUsesTheRealEngine` | Keeping it (its header would then be false); rewriting it (it would only re-prove a dev-only fake is insecure) |
| 18:10 Fri | Keep two deprecated `TinkProtoKeysetFormat` calls, warnings visible | Context7 documents the methods but not their 1.24 replacement. Guessing a crypto API to silence a warning is the exact failure section 3 exists to prevent | Suppressing the warning (hides it from whoever can fix it); guessing a replacement |
| 19:05 Fri | **#38: commit to per-session identity + verified roles tonight; friend find only if both merge by ~01:00** (approved by Diya) | Per-session ids fix a real tracking hole (stable nodeId + 25 m beacon cell). Roles close the 8.5 km help gap with real, signed data and are this lane's P2 anyway. Friend find is the biggest (~4h), depends on per-session ids, and is the feature most likely to be challenged against "never track individuals". Design constraints posted: retired encryption keys kept 24h so lost & found survives a restart; role phones keep a stable key; a role claim must also be signed by `holderKey` | All three (~8h, no slack before the 08:00 freeze); only the privacy fix (leaves the 8.5 km gap open); per-launch keys with no retired-key window (a guardian's restart would silently lose the finder's notices) |
| 19:06 Fri | Split per-session identity into **A (TRUST-only, merged) and B (core interface change, held for 👍)**, and keep the app on `STABLE` until B lands | The pipeline checks `recipientKey == identity.publicKey` and `reporterNodeId == selfNodeId`, so rotating keys alone would silently drop a guardian's found-notices after a restart - worse than the tracking it fixes. Shipping A first lets the engine work be reviewed and merged without changing behaviour | Rotating now and fixing the pipeline later (a regression in the hero flow in the meantime); changing `core/` without an issue (CLAUDE.md 1 forbids it) |
| 19:06 Fri | Keep previous sessions' keys **24 h, max 10**, on the phone only | One event day covers a search; private keys never leave the device, so retention does not link sessions for anyone else. The cap bounds how many keys `open()` tries per notice | No retention (a restart loses the notices); indefinite retention (an old key would outlive the event for no reason) |
| 19:07 Fri | New `TrustEngine` methods get **default implementations** (equality), and the pipeline keeps the `mesh.selfNodeId` check alongside | Defaults leave `FakeTrustEngine` and simulation untouched, so the interface change is two lines of behaviour. In simulation the fake mesh and fake trust ids differ, so dropping the old check would break sim lost & found | Abstract methods (every fake must change); replacing the selfNodeId check (breaks simulation) |
| 19:18 Fri | A role is checked ONLY as `verifyRoleClaim(pass, packet, now)`, never as a bare pass check | A pass travels in the clear, so a bare signature check lets anyone replay an overheard medic pass. Requiring the carrying packet to be signed by `holderKey` makes the claim unforgeable without the holder's private key. Default `false` means the fake and any build without the organiser key never show a role | A public `verifyRolePass(pass)` as the lead proposed (replayable); sealing the pass (it is not secret, sealing adds nothing) |
| 19:24 Fri | **Organiser key generated on the laptop, private half outside the repo** (approved by Diya) | One build serves every phone, and `~/.proxima/` cannot be picked up by any `git add`, which is stronger than a gitignore entry. The generator refuses to overwrite, since a new key silently voids every pass already issued | Generating on the organiser phone (needs a phone now and a second build round); a gitignored folder in the repo (one `-f` away from a leak) |
| 19:30 Fri | Ask MESH/UI to wire roles as **optional** (approved by Diya) | Their own P0/P1s before the 08:00 freeze matter more, and TRUST-side roles are complete and tested either way, so the README can state it honestly | Pushing roles as a priority (risks other lanes' core features); not asking (the capability would never be visible) |
| 19:52 Fri | **Build friend find** (approved by Diya, against my recommendation to skip it) | Diya's call: 1 and 2 merged well before the ~01:00 cutoff promised on #38. Built so it cannot hurt the M2 build: on its own branch, PR held until main reopens, every interface method defaults to off | Skipping it (my recommendation: about 4 h across three lanes, and the feature most likely to be challenged on privacy) |
| 19:55 Fri | Friend matching happens **only on the friend's phone**, from a 10-min rotating HMAC tag; pairing code is **6 digits**; an invite is accepted only from the peer whose code was confirmed | No search is ever broadcast, so relays and bystanders learn nothing about who looks for whom - the answer to "never track individuals". 4 digits is 1 in 10,000. Binding the invite to the confirmed key stops a third phone slipping in its own secret | A broadcast "where is X" query (tells everyone who is searching); a static tag (linkable all day); accepting any invite (impersonation) |
| 19:56 Fri | Beacon tags use JDK `HmacSHA256`; the friend book is encrypted with Tink AES-256-GCM under the Keystore | The friend secret must be shared as raw bytes inside a sealed invite, which a JDK HMAC takes directly; Tink stays responsible for everything at rest (AEAD API checked in Context7) | A Tink MAC keyset (would mean shipping serialized secret keysets between phones); storing friends in plain prefs (a backup or rooted phone would leak who your people are) |
| 20:12 Fri | README claims only what is **on main and tested**: per-session identity yes; roles and friend find stay out until they are on screen in the demo build | Judges test privacy claims against the app. A capability that exists only in TRUST code and never appears on screen would read as an overclaim | Listing roles/friend find as features now (not visible in the demo); leaving them out of the notes entirely (they are real, tested work - kept as a pre-submit decision in the status block) |
| 20:28 Fri | Lesson logged: a pipeline test on a fake transport must model the real transport's dedup, or it proves less than it claims. Left the test as is (it still guards the TRUST half: previous keys/ids are recognised) and credited MESH's fix rather than patching their lane | Editing `LoopMesh` to dedup now (main is frozen, and MESH's own tests cover the replay); claiming end-to-end restart safety from my test alone (it was not) |
| 20:44 Fri | **Passes are issued on the organiser's laptop and delivered by adb**, not by an organiser phone; role phones are selected by an adb-created `role/` folder | The organiser private key never has to leave the laptop (strictly safer than pushing it to a phone), and the flow needs no new screens from a UI lane that is at capacity. A medic tent is a post, not a person, so its stable identity does not track anyone | Organiser phone issuing over the mesh (private key on a phone, plus an issuing screen and a delivery message type); a dev-sheet toggle for role mode (UI work) |
