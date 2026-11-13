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
- [ ] `AssetHelpPointRepository`: parse OSM → `HelpPoint` (amenity/emergency → `HelpCategory`, source "OpenStreetMap (ODbL)"),
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

## NEXT_SESSION_HANDOFF
**Works now**
- Onboarding done: `lane/trust` branch pushed, TEAM_ROLES row filled, `CLAUDE.local.md` written, GitHub CLI 2.104.0
  installed, Context7 MCP added (`claude mcp add` → connected; needs a Claude Code restart to expose the tools).
- **Raw dataset committed**: `trust/src/main/assets/data/osm_help_points_raw.json` (116 KB, 524 elements:
  359 nodes + 165 ways, all with usable coords, 345 named). Fetched 14:43 Fri 13 Nov via `scripts/fetch_help_points.sh`
  (overpass-api.de, succeeded on attempt 3 of 3); OSM base timestamp `2026-11-13T10:42:12Z`.

**Broken / blocked on this laptop**
- **No Android toolchain at all** — no JDK, no Android SDK, no adb, Android Studio not installed. So `./gradlew`
  has never run here and NO Kotlin in `trust/` has ever been compiled. Install Android Studio, open the project once
  (writes `local.properties`), then `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.
- `gh auth login` was started (device flow, code CF98-3144) but may not have been completed — re-check with
  `gh auth status`. Until it is authed: no bus comments, no `gh issue` at all.
- `TrustModule` still returns `FakeTrustEngine` / `EmptyHelpPointRepository` / `EmptyProtocolRepository`. Untouched.

**Exact next step**
1. `gh auth status`; if logged out, redo `gh auth login --hostname github.com --git-protocol https --web`.
2. Write `AssetHelpPointRepository` + `GeoMath`-based `nearest()` and a JVM unit test with a known Mauritius
   location. Parse the raw OSM file: `type=node` → `lat`/`lon`, `type=way` → `center.lat`/`center.lon`;
   map `amenity`/`emergency`/`healthcare` → `HelpCategory`; `source = "OpenStreetMap (ODbL)"`;
   id = `"osm:" + type + "/" + id`; skip the 179 unnamed features or label them by category.
3. Then `TinkTrustEngine` (**Context7: Tink first**), then `protocols.json` from IFRC 2020.

**Files touched this session**
`docs/TEAM_ROLES.md` (my row + phone note), `docs/lanes/TRUST.md`, `trust/src/main/assets/data/osm_help_points_raw.json`,
`CLAUDE.local.md` (gitignored). No `core/` changes, no interface changes.

## Progress log
| Time | What |
|---|---|
| 14:30 Fri | Onboarding checklist run. Machine was bare: only git. Installed GitHub CLI via winget; fixed a typo in the global git email (`diyaramkalwon6@` → `diyaramkalawon6@gmail.com`) so commits attribute correctly. |
| 14:38 Fri | `lane/trust` branch created and pushed (push succeeded → collaborator access confirmed, despite `doctor.sh` reporting "repo not reachable", which only reflects `gh` being logged out). TEAM_ROLES row filled. |
| 14:43 Fri | **P0 dataset done.** `fetch_help_points.sh` → 524 real OSM features for Mauritius, committed raw with fetch date. Counts: pharmacy 169, toilets 117, police 85, clinic 53, hospital 42, doctors 31, fire_station 11, drinking_water 6; healthcare tags add hospital 34 / clinic 42 / centre 7. |
| 14:44 Fri | Context7 MCP added and reporting Connected. |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 14:40 Fri | TRUST tests on a Galaxy S24 Ultra, not the planned Galaxy A56 | It is the phone the member actually has; waiting on hardware would burn P0 time | Borrowing an A56 (none available); swapping lanes with AI (would discard TRUST onboarding already done) |
| 14:41 Fri | Because of that, the "crypto + QR run on mid-range" claim must be proved on the UI lane's Honor X8b, not on the TRUST phone | The S24 Ultra is Tier 1 (RAM ≥ 7 GB) per `docs/ARCHITECTURE.md`, so it proves nothing about Tier 2 | Dropping the mid-range claim from the pitch (it is a real differentiator for a crowd where most phones are mid-range) |
| 14:43 Fri | Commit the **raw** Overpass response verbatim and parse it at runtime, rather than committing a pre-transformed `help_points_mu.json` | Provenance: judges and teammates can re-verify the ODbL source and the fetch timestamp against OSM; transformation logic stays reviewable in Kotlin and unit-testable | Transforming at fetch time in bash (loses provenance, hides the mapping, untestable); hand-curating the list (not "real data only") |
| 14:44 Fri | `ASSEMBLY_POINT` / `MEETING_POINT` / `AMBULANCE` / `DEFIBRILLATOR` will NOT come from OSM | The Mauritius extract contains zero such features (`emergency` has only 6 `yes` values) — pretending otherwise would ship empty categories | Querying a wider bbox (same gap); inventing assembly points (violates "real data only") |
