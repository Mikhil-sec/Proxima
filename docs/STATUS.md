# STATUS: global dashboard (UI lane / Member 1 maintains; others post on issue #1)

## NEXT_SESSION_HANDOFF (02:45 Sat)
- **M2 passed on real phones, simulation OFF** (#75): SOS → Command Center, forced 2-hop relay, lost & found with
  sealed location + UWB/GPS radar, Gemma triage (sent = shown), friend pairing. Bystanders relayed sealed updates
  but could not read them.
- **All 6 devices run the final build `1698627`** (simulation OFF, Gemma on S24 Ultra + S26+, S24U ↔ A56 paired).
  Before the demo: charge them, open Proxima on all of them ~1 minute early so the mesh links up.
- **Surprise card "Scale up"** answered in code (#83, `mesh/.../SurgeControl.kt`), README section + slide text in
  `docs/SURPRISE_CARD.md`. Visible live as the "Relay" line on the Command Center.
- **Only submission work is left.** Main is open for small fixes only; nothing new.
- Demo chain (forced relay): set it over adb right after the apps start, all at once (see #75 T2), never phone by
  phone in the dev sheet (that can deadlock, #3).

## Lane board
| Lane | Owner | State | Real or fake in app | Open |
|---|---|---|---|---|
| UI | Mikhil | All screens, Command Center (density, crowd risk, verified posts, surge line), My people, radar; M2 fixes merged | real | — |
| MESH | Chanum | Nearby mesh, multi-hop, store-and-forward, acoustic fallback (off), verified roles + friend tags in beacons, surge control | NearbyMeshEngine | demo-chain deadlock (#3); "first copy wins" + hop budget (SURPRISE_CARD.md) |
| AI | Seeya | Gemma 4 E2B on device (1.4 s triage), offload, rules fallback, offline voice, hands-free guide, crowd-crush warning | real | A56 did not offload in T1; rules landmark "Gate not breathing" |
| LOCATE | Yorvan | UWB (distance + direction on S23+) → GPS + compass radar, help-point targets | AndroidLocateEngine | "walk this way" to help points (#40) not wired |
| TRUST | Diya | Tink (Ed25519 + HPKE), per-session identity, verified roles, friend find, 524 OSM help points, 9 IFRC cards | TinkTrustEngine | "Vet Clinic" listed as a health centre (#6) |

## Milestones
- [x] M0 Onboarded · [x] Device checks (UWB, Nearby) · [x] M1 Engines real
- [x] M2 End-to-end, sim OFF (passed 02:20 Sat) · [x] M3 Full story (lost & found, Gemma, friends, surprise card)
- [ ] FREEZE (08:00) · [ ] Submitted (12:30)

## Submission pack
- [x] APK built (`./gradlew :app:assembleRelease`, signed with the debug key; a fresh install starts in simulation mode, README says how to switch)
- [ ] README complete: architecture diagram ✅, surprise card ✅, how to run ✅; **TRUST: remove the assembly-status comment block**
- [ ] Decision log 5–10 (AI lane trims the draft shortlist in README, ~10:00)
- [ ] Slides ≤ 6 (surprise-card slide text ready in `docs/SURPRISE_CARD.md`)
- [ ] Video ~2 min (shot list in `docs/lanes/MESH.md`)
- [x] Surprise card shown (README + Command Center "Relay" line)
