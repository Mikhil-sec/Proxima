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

     *** CHECK BEFORE SUBMITTING (was a blocker until 18:10 Fri) ***
     The signing/sealing claims are now TRUE in the build with simulation OFF: TinkTrustEngine (Ed25519 + HPKE,
     Keystore-backed) is wired in TrustModule. The 21-test security contract passes on the JVM AND on a Galaxy
     S24 Ultra under the real Android Keystore (18:36 Fri, `:trust:connectedDebugAndroidTest`). Remaining before
     submitting: one two-phone found-notice with simulation OFF (end-to-end over the mesh, asked on #19).
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

**The radar, honestly: what works on which phone** (measured 13 Nov, simulation off, real mesh)
| Source | Phones | What we measured |
|---|---|---|
| **UWB** (centimetre ranging) | Samsung S23+, S24 Ultra, S26+ (UWB flagships) | Distance ±0.1 m at 2 m, ±0.3 m at 5 m. Over the real mesh (S23+ → S26+) the first fix arrives 0.5 s after "find", then ~3 per second, no pairing screen. **Direction arrow only on the S23+**: the S24 Ultra reports no angle-of-arrival, and the S23+'s angle is approximate (a target 45° to the side reads ~30–40°), so we smooth it and hide the arrow below 0.3 m. |
| **GPS + compass** | Every phone | Position every 1 s; compass corrected to true north (Mauritius declination ≈ −18°). Single-phone check: a target 100 m north read 100.5 m at bearing 0°. Poor indoors, so the stage demo uses UWB. |
| **Signal strength** ("warmer / colder") | — | **Not available on real phones**: Google Nearby Connections does not expose RSSI, so this fallback only runs in simulation. Without UWB or GPS the radar shows "searching". |

The same radar also points to the nearest help point in guided response (GPS + compass; a help point is just a
target that never moves).

**The mesh, honestly: what we measured on phones** (13 Nov, simulation off, airplane mode with Bluetooth + Wi-Fi on)

Every phone runs Google Nearby Connections (`P2P_CLUSTER`), advertising and discovering at the same time. A packet is
dropped if its id was seen before (Room table, so this survives a restart). Otherwise it is shown and forwarded to every
*other* neighbour, with `ttl − 1` and the relay appended to its path, so it never travels back the way it came.
| What | Phones | What we measured |
|---|---|---|
| **Link** | S26+ ↔ S23+ | First connection ~6 s after launch; packets both ways in ~0.2 s |
| **Multi-hop** | S26+ → Honor X8b → S23+ | Chain forced with a neighbour allow-list (so it also works with all phones in one room). Relayed packets arrive with `hops=1` in **0.09–0.17 s**. This was a 3-phone chain with **one relay**. Hop budgets 3 / 5 / 7 / 10 by severity are unit-tested but **not measured beyond one relay**. |
| **Screen off** | Honor X8b as relay | Kept relaying with its screen off (tested ~80 s, Doze), thanks to a foreground service |
| **Recovery** | 2–3 phones | Radio off for 15 s: link back in **31 s**. Peer app restarted: reconnected under its new id in **7 s**. |
| **Store-and-forward** | 2 phones | Live alerts are kept 30 min and re-sent, most severe first, to any phone that connects later. **Confirmed after an app restart.** |
| **Dedup** | (unit tests) | A seen packet id is dropped, including after a restart. Unit-tested; we did not count duplicates separately on phones. |
| **Acoustic fallback** (off by default) | S26+ → S23+ | With no radio link at all, a 16-byte SOS is sent as **near-ultrasound (18.0 / 18.7 kHz)**, inaudible to most adults, and lasts 3.7 s. It decoded **2/2 at table distance**, S26+ → S23+. **The reverse direction failed (0/2):** the S23+ speaker is too weak that high. It arrives unsigned, so it is shown as *unverified*, and it is re-sent over the radio mesh once a link returns. |

**Not measured:** range outdoors, chains longer than 3 phones, and behaviour with dozens of phones. Nearby caps
connections per phone, so a large crowd forms a partial mesh, and relaying is what covers the gaps.

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
Google Tink (Ed25519 signing, HPKE sealing) · on-device LLM: **Gemma 4 E2B on Google LiteRT-LM** (model file
sideloaded, GPU with CPU fallback; Gemini Nano through ML Kit where Android AICore offers it) ·
Android SpeechRecognizer / TextToSpeech (offline recognizer)

**How triage falls back.** Every SOS goes through three tiers and never waits more than 6 s:
(1) the phone's own LLM, if loaded → (2) a nearby phone's LLM over the mesh (the request carries only the words
and the pressed button, never a location; the answer is signature-checked and re-validated) →
(3) `RuleTriageEngine` keywords, which work on every phone with no model. Whatever a model says, its output must
match a fixed schema or it is discarded; keyword rules can **raise** severity but never lower it (under-triage is
the dangerous error); and the protocol card is always chosen locally from the cited set. Gemini Nano was our
first choice, but Android AICore would not provide it on any team phone (download cancelled on the Galaxy S26+,
unavailable on the S24 Ultra), so on Fri 18:48 we switched to Gemma 4 E2B.

**Measured on a Galaxy S24 Ultra (GPU, model on the phone, no network calls, Fri 13 Nov):** Gemma 4 E2B loads in 8.4 s at app
start, answers in ~1.5 s per report (max 3.2 s, inside the 6 s budget), uses ~2.1 GB of app memory, and returned
valid JSON for 44/44 test reports. Accuracy is measured on fixed emergency reports, scored through the shipped
parser and safety net ([`ai/eval/`](ai/eval/README.md)). **English + French, 37 reports:** the shipped path
(Gemma + keyword safety net) under-triages **1**, against **9** for keywords alone. That includes a fix made after
the first runs: the model picks the right kind of emergency but under-rates severity, so severity can no longer
fall below what the rules set for that subtype (unconscious, crush and fire are always critical; so is a lost
child). On 10 reports written before any fix, the first run under-triaged **5**; the fix was designed from those
failures, so a fresh test set written by a teammate is the honest final check. **Kreol** is best-effort and not
tuned (no speech recognizer exists for it); it is scored separately, and there the model alone is weak and the
keyword safety net carries it.
<!-- AI: replace the last sentence with the fresh-set result (ai/eval/REPORT.md) once it is run. -->

Six Gradle modules, one per developer (`core`, `app`, `mesh`, `ai`, `locate`, `trust`), wired by manual DI
through one factory per lane, so five people worked in parallel without merge conflicts.
AGP 9.5.1 · Kotlin 2.4.21 · Gradle 9.9.1 · minSdk 26 · compileSdk 37.

## AI tools used (disclosure)

| Tool | What we used it for |
|---|---|
| Claude Code (Anthropic) | Project scaffolding, shared interfaces, docs; each lane lists further use below |
| Claude Code — TRUST lane | Wrote the OSM tag-mapping and ranking code, the protocol-selection logic and all 22 unit tests; drove the Overpass fetch. The IFRC protocol steps were **transcribed by hand from the published PDF**, deliberately not generated — and no medical step was written by a model. |
| Claude Code — LOCATE lane | Wrote the radar engine (GPS, compass heading, UWB session over the mesh), the arrow filter and all LOCATE unit tests, plus the adb device-test tools. Every claim in the radar table above comes from logs on real phones, not from the model; the member placed the phones and judged the arrow direction. |
| Claude Code — MESH lane | Wrote the Nearby mesh engine (relay, dedup, reconnect backoff, store-and-forward, beacons, allow-list), the acoustic modem and all 34 MESH unit tests (including synthetic-audio tests for noise, echo and clock drift), plus the adb device-test script. The member ran every phone test, chose to install test builds side by side so teammates' phones kept their data, and moved the acoustic tones to near-ultrasound after the audible version disturbed other teams. Every number in the mesh table comes from phone logs. |
| Claude Code — AI lane | Wrote the triage engine (Gemini Nano and Gemma/LiteRT-LM backends, prompt, strict output validator, rules safety net), the mesh offload, offline voice in/out, the hands-free guide, the crowd-crush risk model, their unit tests and the accuracy-eval harness. The member chose the runtime and the pivot to the guide and crowd warning, ran every phone test and spoke every voice command. The eval's expected answers were fixed before any model was run. No medical text is generated: the model only picks a type and subtype, and steps come from TRUST's cited cards. |
<!-- UI: add your row -->

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
| 16:50 | **Reject a blockchain** for the trust layer; keep Ed25519 signatures + HPKE sealing | A ledger is public (a child's location on it inverts our privacy rule) and needs consensus, i.e. connectivity, the one thing a crowd does not have |
| 18:05 | A node id is **derived from its key** and `verify()` rejects any packet whose id does not match | A valid signature alone lets an attacker claim someone else's id; binding the two makes ids unforgeable. The same 21-test security spec passes on the JVM and on two real phones |

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
