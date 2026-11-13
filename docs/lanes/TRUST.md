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
- [ ] `TinkTrustEngine` (**Context7: Tink**): Ed25519 sign/verify of `MeshPacket.signingBytes()`; HPKE (X25519) hybrid seal/open;
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
**Done and verified (22/22 unit tests pass, whole project builds)**
- Onboarding complete; toolchain works (Android Studio + JBR **JDK 25**, SDK `android-37.0`, adb,
  `local.properties` with forward-slash `sdk.dir`). gh authed as @diya-r6. Context7 MCP connected.
- **Dataset** `assets/data/osm_help_points_raw.json` - 524 real Mauritius OSM features (ODbL),
  fetched 14:43 Fri, OSM base `2026-11-13T10:42:12Z`. **Merged to `main` in PR #12.**
- **Help points** `OsmHelpPoints` + `AssetHelpPointRepository`, wired in `TrustModule`.
- **Wristband QR** `wristbandQrBitmap(tag, sizePx)` + Android-free `WristbandQr.matrix`.
  Round-trip tested: the test decodes the symbol back and asserts it parses to the identical tag.
- **Protocols** `assets/data/protocols.json` - 9 cards transcribed from the real IFRC 2020 PDF with a
  citation each. `AssetProtocolRepository` + `Protocols.bestFor`, wired in `TrustModule`.
- `trust/AndroidManifest.xml` declares CAMERA, with `camera.any` not required.
- Verified properly: `./gradlew :app:assembleDebug --rerun-tasks` recompiles the whole app against this
  code and succeeds. Plain `assembleDebug` reports success from cache in ~3 s - do not trust that alone.

**Not done**
- `TrustEngine` is still `FakeTrustEngine`. **This is the biggest remaining item and MESH is waiting on it**
  (they need the `nodeId` derivation to finalise node identity).
- `WristbandScanner` composable - the other half of #7. Needs the compose plugin + CameraX + ML Kit
  (stubbed as comments in `trust/build.gradle.kts`) and **Context7 first** (CLAUDE.md section 3).
- 4 protocol cards missing - see the gap below. `protocols.json` has **not been proof-read by a second
  member yet**, which the lane requires before the demo.
- Never run on a phone. `installDebug` untried, the S24 Ultra has never been connected.
- README assembly (due 10:30 Sat) not started.

**Exact next step**
1. **Restart Claude Code** so the Context7 tools load, then `TinkTrustEngine`: Ed25519 over
   `MeshPacket.signingBytes()`, HPKE X25519 seal/open, keysets in the Android Keystore,
   `identity.publicKey = "<sigB64>.<encB64>"`, `nodeId` = first 8 hex of SHA-256(publicKey).
   **Then tell MESH on the bus what the nodeId derivation is.**
2. `WristbandScanner` (Context7: CameraX + ML Kit).
3. Source the 4 missing protocol cards, or decide to ship without them.
4. `installDebug` on the S24 Ultra.

**Protocol coverage gap (deliberate, pinned by a test)**
`CROWD_CRUSH`, `ELECTRICAL`, `FALLEN_BRANCH`, `FLOOD` have no card: the IFRC 2020 guidelines contain no
protocol for them, and `bestFor` returns null rather than substituting an unrelated card.
**`CROWD_CRUSH` is the one that hurts** - CRITICAL severity and the Maha Shivaratri scenario, so a crowd
crush currently produces an incident with no guidance. Needs a non-IFRC authoritative source
(e.g. a crowd-safety / event-safety guide) or an explicit team decision to ship without it.
The lost-person subtypes (`CHILD`/`ELDERLY`/`PERSON`) are operational, not medical, so they must not be
cited to IFRC; they need their own honestly-sourced card.
`ProtocolsTest` fails if this gap list changes, so it cannot grow unnoticed.

**Open asks on the bus**
- #13 `interface-change` - `TrustEngine.requiredPermissions`, waiting on @Mikhil-sec to pick A or B.
- #11 to UI - km distances (Ganga Talao's nearest help is 8.5 km) + pharmacies via explicit categories.
- #7 from UI - QR done, scanner pending Context7.

**Files I own and have touched**
`trust/**` (OsmHelpPoints, AssetHelpPointRepository, WristbandQr, AssetProtocolRepository, TrustModule,
AndroidManifest, build.gradle.kts, assets/data/*.json, 4 test classes), `docs/lanes/TRUST.md`,
`docs/TEAM_ROLES.md` (my row + phone note). **No `core/` changes. No interface changes.**

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
