# Proxima

**Offline, phone-to-phone safety network for big crowds in Mauritius.** When tens of thousands gather at
Ganga Talao for Maha Shivaratri, at Ganesh Chaturthi, concerts or on campus, mobile networks collapse.
Proxima lets people raise and route emergencies, find lost children and get guided help with **no network at all**.

> Hackathon *Life Powered by Tech*, 13–14 Nov 2026 · Theme 1: **Powered to Protect**

<!-- ASSEMBLY STATUS (TRUST owns this file; remove this block before submitting)
     Filled: Problem & user · Data sources · Tech stack · How to run · AI disclosure (TRUST row) · draft decisions.
     STILL NEEDED from each lane, by 10:30 Sat:
       UI (Mikhil)   – the diagram in "How it works"; screenshots; AI-disclosure row
       MESH (Chanum) – one line on hop limit / dedup / store-and-forward actually measured; AI row
       AI (Seeya)    – which on-device LLM runtime shipped, and the tiering; AI row
       LOCATE (Yorvan) – UWB vs GPS vs RSSI: what works on which phone; AI row
     Also: "Surprise card" once drawn, and the final 5–10 decision shortlist (AI lane compiles ~10:00 Sat).

     *** BLOCKER BEFORE SUBMITTING ***
     The "Privacy" section and the signing/sealing claims in "How it works" describe the DESIGN. As of 16:00 Fri
     they are NOT true in the build: TrustModule.createTrust ignores the `simulation` flag, so seal() is base64
     and verify() accepts any signature (see #19). Either TinkTrustEngine lands, or these claims must be
     softened before this README is submitted. Do not ship an untrue privacy claim — it is what judges check.
-->

## Problem & user

**Hero user: a parent with a young child on the Maha Shivaratri pilgrimage to Ganga Talao (Grand Bassin).**
Tens of thousands of people, cell networks congested or dead, and the child gets separated. Today there is no
way to alert the people standing next to you, and no way to find the child once somebody has them.

The same gap hits anyone who *sees* an emergency — a collapse, a fire, a fallen branch, a crush building — at
Ganesh Chaturthi, Père Laval, concerts, rallies and university campuses: they cannot call for help and cannot
warn the crowd around them.

**Why "offline" is the whole point.** Our own dataset makes the case. At Ganga Talao the nearest medical
facility is **8.5 km away**, the nearest police station 8.5 km, the nearest fire station 10.7 km. Help is not
around the corner, and the network you would use to call it is the first thing to fail.

## How it works

Everything is one `Incident` on one pipeline — lost person, sighting, found, medical, hazard, all-clear.

1. **One-button SOS (voice or tap).** On-device AI turns panicked speech into a ~200-byte structured incident
   (type, severity, landmark). A rules-based classifier (`RuleTriageEngine`, keywords in English, French and
   Kreol Morisien) runs on every phone as the fallback, so the feature never depends on a model loading.
2. **Multi-hop mesh.** The incident is signed and relayed phone to phone over Bluetooth / Wi-Fi with no
   network. Severity sets the hop budget (3 / 5 / 7 / 10); duplicates are dropped by packet id.
3. **Guided response.** The incident resolves to a **vetted protocol card with a cited source**, read aloud,
   plus the **nearest real help point** from the Mauritian dataset with distance and bearing.
4. **Lost & found, privately.** The guardian reports a lost child — minimal description, **no name, no photo**.
   A finder who is with the child answers that alert, and the location travels **sealed to the guardian's key**,
   so relays carry it but cannot read it. The guardian then gets a radar pointing the way.
5. **Command Center** (tablet at a first-aid or police post): live incident heatmap from **anonymous grid
   cells only**, mesh topology, and merged report counts ("Fire — 15 reports").

<!-- UI: architecture diagram here. Source: docs/ARCHITECTURE.md -->

## Data sources

Two real datasets, both shipped in the APK so they work with no network.

### 1. Mauritius help points — OpenStreetMap

| | |
|---|---|
| Source | [OpenStreetMap](https://www.openstreetmap.org/) via the [Overpass API](https://overpass-api.de/) |
| Licence | **ODbL** (Open Database License) — attribution carried on every record |
| Fetched | 13 Nov 2026, 14:43 (+04) · OSM base timestamp `2026-11-13T10:42:12Z` |
| Reproducible | `bash scripts/fetch_help_points.sh` |
| In repo | `trust/src/main/assets/data/osm_help_points_raw.json` (raw response, committed verbatim) |
| Records | **524** features, all with usable coordinates, 345 named |

Pharmacies 169 · toilets 117 · health centres 94 · police 85 · hospitals 42 · fire stations 11 · drinking water 6.

**What it powers:** `HelpPointRepository.nearest(location, categories)` ranks every facility by haversine
distance and compass bearing, and the guided-response screen shows the closest ones for that incident type.

We commit the **raw** Overpass response rather than a pre-baked list, so anyone can re-verify the licence, the
fetch date and every coordinate against OpenStreetMap. The tag mapping to our categories lives in reviewable,
unit-tested Kotlin, not in a one-off script.

**Honest gap:** OpenStreetMap Mauritius contains **no** assembly points, ambulance stations or defibrillators —
`emergency=*` returns only six generic values. So those categories are empty, and we say so rather than
shipping a map with invented pins. Lost-person alerts therefore route to police, and hazards to fire and
police, both of which are real and well covered.

### 2. Response protocols — IFRC

| | |
|---|---|
| Source | [IFRC *International first aid, resuscitation, and education guidelines 2020*](https://www.ifrc.org/sites/default/files/2022-02/EN_GFARC_GUIDELINES_2020.pdf) |
| Method | Transcribed from the published PDF (133 pages), **not** from a summary or from a model's memory |
| In repo | `trust/src/main/assets/data/protocols.json` |
| Cards | **9**, each carrying the chapter and page it came from |

CPR · recovery position · severe bleeding · seizure · heat exhaustion and heatstroke · fracture · chest pain ·
burns · generic casualty assessment.

**The AI only *selects* a card.** It never writes, edits or reorders a medical step. Selection is
`(incident type, subtype) → card`, and the UI always shows which classifier chose it (`triagedBy`).

**Honest gap:** crowd crush, electrical injury, fallen branch and flooding have **no card**, because the IFRC
guidelines contain no protocol for them. The repository returns *nothing* rather than substituting an unrelated
card — showing a confident-looking wrong protocol to someone kneeling over a casualty is worse than showing
none. A crush casualty is clinically an unresponsive or non-breathing patient, which the cited CPR and recovery
cards already cover.

## Tech stack

Kotlin · Jetpack Compose · Google Nearby Connections · Room · androidx UWB · Fused Location ·
Google Tink (Ed25519 signing, HPKE sealing) · on-device LLM (runtime: *AI lane to confirm*) ·
Android SpeechRecognizer / TextToSpeech

Six Gradle modules, one per developer (`core`, `app`, `mesh`, `ai`, `locate`, `trust`), wired by manual DI
through one factory per lane, so five people worked in parallel without merge conflicts.
AGP 9.5.1 · Kotlin 2.4.21 · Gradle 9.9.1 · minSdk 26 · compileSdk 37.

## AI tools used (disclosure)

| Tool | What we used it for |
|---|---|
| Claude Code (Anthropic) | Project scaffolding, shared interfaces, docs; each lane lists further use below |
| Claude Code — TRUST lane | Wrote the OSM tag-mapping and ranking code, the protocol-selection logic and all 22 unit tests; drove the Overpass fetch. The IFRC protocol steps were **transcribed by hand from the published PDF**, deliberately not generated — and no medical step was written by a model. |
<!-- UI / MESH / AI / LOCATE: add your row -->

## Key decisions

<!-- DRAFT shortlist, assembled by TRUST from docs/DECISIONS.md and the lane logs.
     AI lane compiles the final 5–10 at ~10:00 Sat; trim this then. All times Fri 13 Nov. -->

| Time | Decision | Why |
|---|---|---|
| ~13:05 | Hero user = a parent at Maha Shivaratri, not "everyone at events" | A specific Mauritian user with real, measurable network congestion |
| ~13:10 | Cut the duress PIN, decoy calculator, cyber-warfare branding and LLM chatbot | Too many features, none finished; they did not serve the user |
| ~13:15 | Unify lost / sighting / found / medical / hazard / all-clear as one `Incident` pipeline | One bus to build, test and explain, instead of five subsystems |
| ~13:25 | The AI **selects** a cited protocol card and never authors medical advice | Removes the possibility of a hallucinated instruction being read aloud |
| 13:35 | Six Gradle modules, one owner each, fakes behind factories | Five developers in parallel with zero merge conflicts |
| 14:43 | Commit the **raw** Overpass response and map it in tested Kotlin | Licence, fetch date and coordinates stay verifiable against OSM |
| 15:35 | **Drop the QR wristband**; the finder answers the guardian's alert instead | No wristband hardware or printing, and the flow works without it — same privacy, one less dependency |
| 15:45 | Ship protocol cards with gaps rather than invent the missing four | A wrong protocol is more dangerous than an absent one |

Full logs: `docs/DECISIONS.md` and `docs/lanes/*.md`.

## How to run

1. Install the APK from Releases, or build it: `./gradlew assembleDebug` (JDK 17+, Android SDK 37).
2. Allow **Nearby devices**, **Location** and **Microphone** when asked. Proxima requests no camera and no
   contacts, and has no account, login or server.
3. **Put the phone in airplane mode** — that is the demo. Keep Bluetooth, Wi-Fi and Location switched on; they
   carry the mesh without any network.
4. Two or more phones show relaying; three or more show multi-hop. A tablet (≥ 600dp) opens the Command Center.

Nothing leaves the device except signed incident packets passed to nearby phones, and a child's location only
ever travels encrypted to the guardian's key.

## Privacy

- No names, no photos and no profiles. Lost-person alerts carry a minimal description only.
- A child's location travels **sealed to the guardian's public key**; relays forward it and cannot read it.
- Crowd density is reported as **anonymous ~25 m grid cells**, never as individual positions.
- No account, no server, no analytics. The app declares no camera and no contacts permission.

## Surprise card

<!-- see docs/SURPRISE_CARD.md, once drawn -->

## Team

See `docs/TEAM_ROLES.md`.
