# Team roles & lanes

Each member owns one lane: one Gradle module and one lane file. Lanes only touch each other through
`core/` interfaces, so you can all work in parallel without merge conflicts.

Each member fills in their own GitHub @ and actual phone in their first PR.

| Lane | Member | GitHub @ | Phone | Why this phone | Issue |
|---|---|---|---|---|---|
| **UI** + Command Center (+ extra tasks) | **Mikhil** (Member 1, lead) | @Mikhil-sec | Honor X8b + Tab S6 Lite | Tablet = Command Center; Honor = Tier 2 scout UX | #2 |
| **LOCATE** (radar, UWB, position) | **Yorvan** (Member 2) | @yorvan2401 | Galaxy S23+ | UWB + newest sensors | #5 |
| **MESH** (Nearby, relay) | **Chanum** (Member 3) | @deevish1009 | Galaxy S26+ | Tier 1 relay; UWB partner for LOCATE tests | #3 |
| **AI** (triage, voice) | **Seeya** (Member 4) | @seeya0611 | Galaxy S26+ | Tier 1 (≥7 GB RAM) for on-device LLM; shared with LOCATE for UWB | #4 |
| **TRUST** (crypto, QR, dataset) | **Diya** (Member 5) | @diya-r6 | Galaxy S24 Ultra | Tier 1 in practice - see the phone note below | #6 |

Phones are shared for tests: LOCATE needs a second UWB phone (S23+ or S24 Ultra), and MESH needs 3+ phones for hops.

> **Phone note (TRUST, 13 Nov):** Diya tests on a Galaxy S24 Ultra, which is **Tier 1** (`docs/ARCHITECTURE.md` device
> tiers), not the Tier 2 A56 this lane assumed. Three consequences:
> 1. The "crypto + QR run on mid-range" proof point is **not** demonstrated by the TRUST phone. It needs Mikhil's
>    Honor X8b or another Tier 2 device. Raised in #11.
> 2. Diya's S24 Ultra is the same handset Seeya (AI) is down for, so there may be only one between the two lanes -
>    agree who gets it for the LLM device check vs. crypto/QR runs.
> 3. Upside: it is a usable second UWB phone for Yorvan (LOCATE) and a Tier 1 relay for Chanum (MESH).

## How to tell your Claude its lane
In a fresh session after cloning, say:
> "I am Member N, my lane is <LANE>. Run the onboarding checklist in docs/ONBOARDING.md."

e.g. Yorvan: *"I am Yorvan, Member 2, my lane is LOCATE. Run the onboarding checklist in docs/ONBOARDING.md."*

Claude writes this into `CLAUDE.local.md` (gitignored), so every later session knows your lane automatically.

## Who owns which deliverable

| Deliverable (required by the brief) | Owner | When |
|---|---|---|
| Working APK (release, debug-signed) | UI | 11:00 Sat |
| README: problem, user, data sources, stack, AI tools, surprise card, how to run | TRUST compiles; each lane writes its section | 10:30 Sat |
| Decision log (5–10, with times) | Everyone logs; AI compiles the shortlist | 10:00 Sat |
| Pitch slides (max 6) | LOCATE + UI | 10:30 Sat |
| Backup demo video (~2 min, outdoors so GPS works) | MESH films, everyone acts | 09:30 Sat |
| Surprise card adaptation | Whoever's lane it hits most; team decides in 10 min | when drawn |
| Rehearse the demo 3+ times | Everyone | 08:00–11:30 Sat |

**Everyone must be able to explain the whole app** (judges may question any member). Each lane writes a
5-line "how my part works" in its lane file, and everyone reads all five before 10:00 Sat.

## Milestones (Fri 13 → Sat 14 Nov)

| Time | Milestone | Done when |
|---|---|---|
| 14:30 Fri | **M0 Onboarded** | Every member: checklist passed, app runs on own phone in SIM mode, lane branch pushed |
| 15:00 Fri | **Device checks** | AI: LLM runtime picked · LOCATE: UWB direction works y/n · MESH: 2 phones connect via Nearby |
| 18:00 Fri | **M1 Engines real** | Each engine works for real on a phone (not the fake) |
| 23:00 Fri | **M2 End-to-end** | SOS on phone A → relay B → shows on tablet, simulation OFF |
| 04:00 Sat | **M3 Full story** | Lost & found + radar + guided response with dataset + Command Center heatmap |
| **08:00 Sat** | **FEATURE FREEZE** | Only bug fixes, polish, rehearsal after this |
| 12:00 Sat | **Submit** | Google Form filled (30-min buffer before 12:30) |

## Bus quick reference
See `CLAUDE.md` §5. Labels: `lane:ui` `lane:mesh` `lane:ai` `lane:locate` `lane:trust` `request`
`interface-change` `blocker` `decision` `status` `surprise-card`.
