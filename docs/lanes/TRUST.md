# LANE: TRUST (crypto, wristband QR, real dataset)
Owner: Member 5 — Diya (@Diya-Ramkalawon) · test phone **Galaxy S24 Ultra** (Tier 1, not the A56 this lane assumed)
· Branch `lane/trust` · Issue `[LANE:TRUST]`

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
- [ ] Wristband: `wristbandQrBitmap(tag, sizePx)` (ZXing) + `WristbandScanner(onTag)` composable (CameraX + ML Kit;
      add the compose plugin to `trust/build.gradle.kts`). Persist minted tags (`myWristbands()`).
- [ ] `protocols.json` + `AssetProtocolRepository`: cards **transcribed from an authoritative source** (IFRC International First Aid
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
**Works (written, NOT yet compiled)**
- Onboarding complete: `lane/trust` pushed, TEAM_ROLES row filled, gh authed as @diya-r6 (lane issue **#6**),
  Context7 MCP connected, Android Studio installed (JBR = JDK 25).
- **P0 dataset done**: `trust/src/main/assets/data/osm_help_points_raw.json`, 524 real Mauritius OSM features,
  fetched 14:43 Fri, OSM base `2026-11-13T10:42:12Z`. All 524 map to a `HelpCategory`.
- **P0 repository done**: `OsmHelpPoints` (pure parser + `rankNearest`) and `AssetHelpPointRepository`
  (Android asset load, cached, off-main-thread). `TrustModule.createHelpPoints` returns the real one.
- 7 unit tests in `trust/src/test/.../OsmHelpPointsTest.kt`, asserted against the real dataset by stable OSM id.
  Every assertion was verified numerically against the data before committing, but by re-implementing the logic in
  Python - that is NOT a substitute for running them.

**Blocked / unverified**
- **Nothing in `trust/` has ever been compiled.** No Android SDK yet (Studio installed but the first-run wizard,
  which creates the SDK and `local.properties`, has not been completed). So `./gradlew :trust:test` has never run.
  **Do not merge this branch to `main` until it does.**
- `TrustEngine` is still `FakeTrustEngine`. `ProtocolRepository` is still `EmptyProtocolRepository`.

**Exact next step**
1. Finish the Studio first-run wizard, then:
   `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` and `./gradlew :trust:test`.
   Expect possible fixes: unused imports, and Gradle/AGP acceptance of JDK 25 (may need a toolchain pin to 17).
2. `TinkTrustEngine` - **query Context7 for Tink before writing any of it** (CLAUDE.md section 3): Ed25519 over
   `MeshPacket.signingBytes()`, HPKE X25519 seal/open, keysets in the Android Keystore,
   `identity.publicKey = "<sigB64>.<encB64>"`, `nodeId` = first 8 hex of SHA-256(publicKey).
   **Then tell MESH on the bus that it must use this nodeId.**
3. `protocols.json` transcribed from IFRC International First Aid Guidelines 2020. Never AI-written.
   Subtype codes must match `RuleTriageEngine`.

**Open asks on the bus (posted on #6)**
- UI: "works on mid-range" needs the Honor X8b; my S24 Ultra is Tier 1.
- LOCATE: assembly/meeting points must come from venue data - OSM Mauritius has none.
- UI: `IncidentType.helpCategories` never routes to `PHARMACY`/`WATER`/`TOILETS` (292 of 524 points).
  **No core change needed** - UI can call `nearest(loc, setOf(PHARMACY))` directly for a non-emergency list.
  Deliberately NOT added to `MEDICAL`: a pharmacy 80 m away would then outrank a hospital for a cardiac arrest.

**Files touched**
`trust/src/main/java/mu/proxima/trust/{OsmHelpPoints,AssetHelpPointRepository,TrustModule}.kt`,
`trust/src/test/java/mu/proxima/trust/OsmHelpPointsTest.kt`,
`trust/src/main/assets/data/osm_help_points_raw.json`, `docs/lanes/TRUST.md`, `docs/TEAM_ROLES.md` (my row).
No `core/` changes. No interface changes.

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
