# STATUS: global dashboard (UI lane / Member 1 maintains; others post on issue #1)

## NEXT_SESSION_HANDOFF
- Skeleton is in place: 6 modules, contract in `core/`, fakes wired, app shows the wiring check in SIM mode.
- Next: all members onboard (`docs/ONBOARDING.md`) → M0 by 14:30 → device checks by 15:00.

## Lane board
| Lane | Owner | State | Real or fake in app | Blocker |
|---|---|---|---|---|
| UI | Member 1 | not started | wiring check only | — |
| MESH | ? | not started | FakeMeshEngine | — |
| AI | ? | not started | RuleTriageEngine (real rules) + FakeVoiceIo | LLM runtime check |
| LOCATE | ? | not started | FakeLocateEngine | UWB check |
| TRUST | ? | not started | FakeTrustEngine + empty dataset | Overpass API was overloaded at 13:40; retry script |

## Milestones
- [ ] M0 Onboarded (14:30) · [ ] Device checks (15:00) · [ ] M1 Engines real (18:00)
- [ ] M2 End-to-end, sim OFF (23:00) · [ ] M3 Full story (04:00) · [ ] FREEZE (08:00) · [ ] Submitted (12:00)

## Submission pack
- [ ] APK · [ ] README complete · [ ] Decision log 5–10 · [ ] Slides ≤6 · [ ] Video ~2 min · [ ] Surprise card shown
