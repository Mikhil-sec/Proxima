# STATUS: global dashboard (UI lane / Member 1 maintains; others post on issue #1)

## NEXT_SESSION_HANDOFF (17:15 Fri)
- Everyone onboarded. Lost & found no longer uses a wristband (#14, PR #15 waiting 👍 MESH + TRUST).
- UI design system is now Material You via `DESIGN_RULES.md` (`./gradlew :app:designCheck` gate). Old look: tag `ui-v1-field-terminal`.
- **Demo risk:** with simulation OFF, TRUST crypto is still the fake (no real sealing) until TinkTrustEngine lands (~23:00).
- Next milestone M2 (23:00): SOS on phone A → relay B → tablet, simulation OFF.

## Lane board
| Lane | Owner | State | Real or fake in app | Blocker |
|---|---|---|---|---|
| UI | Mikhil | All screens rebuilt (Material You), lost & found + radar, dispatch board; #28 wired. Device review of new look pending | real UI | PR #15 needs 👍 (#14) |
| MESH | Chanum | Nearby mesh real; multi-hop on 3 phones (~0.1 s/hop); demo allow-list; beacons with coarse cell | NearbyMeshEngine | stable nodeId (#19, TRUST) |
| AI | Seeya | LLM triage engine (rules until Gemini Nano on a phone), offline voice, #16 + #9 fixed, on main | rules + real voice | on-device LLM availability |
| LOCATE | Yorvan | UWB distance ✅ S23+/S24U, direction S23+ only; GPS+compass engine built | AndroidLocateEngine | UWB CONTROL packets (#24, MESH) |
| TRUST | Diya | 524 OSM help points + 9 IFRC protocol cards on main, 22 tests | FakeTrustEngine (crypto fake) | Tink engine in progress |

## Milestones
- [x] M0 Onboarded · [x] Device checks (UWB, Nearby) · [ ] M1 Engines real (18:00)
- [ ] M2 End-to-end, sim OFF (23:00) · [ ] M3 Full story (04:00) · [ ] FREEZE (08:00) · [ ] Submitted (12:00)

## Open decisions for the lead
- #20 crowd crush has no cited card → recommend option A (hazard alert, no steps; casualties use cited CPR cards).

## Submission pack
- [ ] APK · [ ] README complete (sections due 10:30 Sat, #23) · [ ] Decision log 5–10 · [ ] Slides ≤6 · [ ] Video ~2 min · [ ] Surprise card shown
