# Team roles & lanes

Each member owns one lane: one Gradle module and one lane file. Lanes only touch each other through
`core/` interfaces, so you can all work in parallel without merge conflicts.

**Fill in the table** (each member edits only their own row, in their first PR):

| Lane | Member | GitHub @ | Phone | Why this phone |
|---|---|---|---|---|
| **UI** + Command Center | Member 1 | | Honor X8b + Tab S6 Lite | Tablet = Command Center; Honor = Tier 2 scout UX |
| **AI** (triage, voice) | | | Galaxy S24 Ultra (recommended) | Strongest on-device LLM (12 GB) |
| **LOCATE** (radar, UWB, map) | | | Galaxy S26+ (recommended) | UWB + newest sensors |
| **MESH** (Nearby, relay) | | | Galaxy S23+ (recommended) | Tier 1 relay; UWB partner for LOCATE tests |
| **TRUST** (crypto, QR, dataset) | Member 5 — Diya | @Diya-Ramkalawon | Android (model TBD) | Tier 2: proves crypto + QR run on mid-range |

Phones are shared for tests: LOCATE needs a second UWB phone (S23+ or S24 Ultra), and MESH needs 3+ phones for hops.

## How to tell your Claude its lane
In a fresh session after cloning, say:
> "I am Member N, my lane is <LANE>. Run the onboarding checklist in docs/ONBOARDING.md."

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
