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
_Saved 17:55 Fri 13 Nov. Branch `lane/trust`, synced with `main`, 34/34 tests pass, whole project builds._

### THE ONE THING THAT MATTERS NEXT
**`TinkTrustEngine`.** It is the last P0, MESH is blocked on it (#19), and until it lands the product's
headline privacy claim is false. **Check Context7 is loaded before writing a line of it** (CLAUDE.md section 3):
the tools are `resolve-library-id` / `query-docs`. It is registered at **user** scope and reports Connected, so
it should appear now. If it does not, do NOT write Tink from memory - say so and stop.

What it must do (the spec is already written as a test - see below):
- Ed25519 sign/verify over `MeshPacket.signingBytes()`.
- HPKE (X25519) seal/open.
- Keysets persisted via the **Android Keystore**, so identity survives restart.
- `identity.publicKey = NodeIds.publicKey(sigB64, encB64)`; `identity.nodeId = NodeIds.derive(publicKey)`.
  **Both helpers already exist and are tested - use them, do not re-derive.**
- Then flip `TrustModule.createTrust`: `if (!simulation) return TinkTrustEngine(context)`.
- Then `class TinkTrustEngineTest : TrustEngineContractTest() { override fun newEngine() = ... }` in
  **`src/androidTest`** (the Keystore needs a real `Context`, so it is instrumented, not JVM).
- Then **delete `FakeTrustEngineSecurityTest`** - it will start failing, which is the intended signal.
- Then comment on #19 and #1 that simulation-OFF is now genuinely signed and sealed.

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
1. **Simulation-OFF is not demo-safe.** `TrustModule.createTrust` ignores the flag and returns
   `FakeTrustEngine`, whose `seal()` is recoverable base64 and whose `verify()` accepts anything.
   Proven by `FakeTrustEngineSecurityTest`. Fixed by `TinkTrustEngine`. Reported on #19 and #1.
   **The README has a blocker note about this - its privacy claims must not be submitted until this is true.**
2. **No CROWD_CRUSH / ELECTRICAL / FALLEN_BRANCH / FLOOD card - DECIDED 18:00 Fri, do not revisit.**
   Approved by Diya: we ship without them. IFRC 2020 has zero crush content and UK HSE is organiser
   prevention only, so there is nothing citable. `bestFor` returns null by design. **Do not invent these.**
   The only remaining work is UI-side: render a hazard with `protocolId == null` and no steps panel (#20,
   left open for UI to confirm). If someone produces a Mauritian source - St John Ambulance Mauritius, the
   Fire and Rescue Service, or a printed Ganga Talao briefing sheet - a photo is enough and I can transcribe
   a cited card in under an hour.
3. **`protocols.json` has NOT been proof-read by a second member.** The lane requires it before the demo.
4. **This lane has never run on a phone.** `adb devices` was empty all session; `installDebug` untried.
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
