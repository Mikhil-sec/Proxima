# LANE: UI + Command Center
Owner: **Mikhil** (Member 1, lead) · Phones: Honor X8b (Tier 2) + Galaxy Tab S6 Lite (Command Center) · Branch `lane/ui` · Issue `[LANE:UI]`

## Mission
Make Proxima usable by a panicking parent without help, and make the Command Center the wow moment on stage.

## You own
`app/` (all screens, theme, `ProximaGraph`, `AndroidDeviceCapabilityProvider`), `docs/STATUS.md`, this file.
You consume everything through `graph.pipeline`, `graph.mesh`, `graph.locate`, `graph.voice`, `graph.helpPoints`, `graph.protocols`.

## Design system: "Field Terminal" (anti-slop)
- Colours: background `#05070A` (OLED black), surface `#0F141C` (gunmetal), verified/OK `#00FF9D` (phosphor green),
  unverified/warning `#FFB800` (amber), SOS/critical `#FF2A55` (crimson). Text `#E6EDF3`, dim text `#7D8590`.
- Type: **JetBrains Mono** for numbers, coordinates, dBm, hops, timestamps. **Space Grotesk** for headers.
  Both are OFL: put the TTFs in `app/src/main/res/font/`.
- Custom `Canvas`: radar sweep, pulsing packet arcs along `HopEvent`s, voice waveform from `VoiceEvent.Level`, heatmap.
- Haptics on peer discovered, packet hop, and radar proximity changes (faster ticks when closer).
- No default Material cards, no purple, no `AlertDialog`. Use bottom sheets and HUD overlays. No lorem ipsum, no empty boxes.
- **Two registers.** Civilian screens (SOS, guardian, finder) are calm and huge: one action per screen, plain words,
  48dp+ targets. Command Center is dense tactical telemetry.
- Always show a **SIM** badge when `graph.simulation` is true. Show `triagedBy` and verified (green) vs unverified (amber) on incidents.

## Design plan & quality bar (every screen must pass before it is "done")

### Tokens (code: `app/.../ui/theme/Theme.kt`, nothing else picks colours or sizes)
| Token | Value | Only used for |
|---|---|---|
| Black | `#05070A` | Every background. True OLED black, not "tinted near-black". |
| Surface / SurfaceHi | `#0F141C` / `#161D28` | Sheets, pressed states, the few raised panels |
| Line | `#232C3A` | 1dp structure: map grid, dividers that separate *different* things |
| Green `#00FF9D` | phosphor | **Meaning only:** verified signature, mesh link alive, "you are connected". Never decoration. |
| Amber `#FFB800` | | Unverified relay, SIM badge, warnings, MEDIUM |
| Crimson `#FF2A55` | | SOS and CRITICAL only. If crimson is on screen, something is on fire. |
| Text / Dim | `#E6EDF3` / `#7D8590` | Body / secondary (Dim on Black = 5.4:1, on Surface = 4.9:1, both pass AA) |

- **Type scale** (Space Grotesk, ratio ~1.25): 44 / 32 / 26 / 22 / 18 / 16 / 14sp. Body 18sp on civilian screens.
  JetBrains Mono **only for machine data** (coordinates, dBm, hop counts, node ids, timers), never for labels or sentences.
- **Spacing**: 4dp grid, screen gutters 20dp (phone) / 24dp (tablet). Section gaps 32dp, related items 12dp.
- **Shape**: hierarchy-driven, not one radius everywhere. SOS = circle, primary actions = 20dp radius,
  list rows = no container (a severity rail + whitespace), sheets = 28dp top corners.
- **Motion**: one ambient motion per screen at most (Home: SOS breathing ring; Command Center: hop pulses; Radar: sweep).
  Everything else moves only in response to touch. All of it respects the system animator scale (reduced motion).

### Two registers (the core design decision)
- **Civilian (phone: Home, Report, Guardian, Finder, Guided response):** calm, plain, huge. Reads like a
  well-made public-safety sign, not a hacker toy. Sentence case, no mono, no grid, no telemetry. One action per screen.
  The one bold thing: **the crimson SOS ring**.
- **Command Center (tablet):** the "Field Terminal" register. Dense mono telemetry, tactical grid, live hop pulses.
  The one bold thing: **the live mesh map**. The terminal look lives here and on the radar, nowhere else.

### Anti-slop checklist (reject the screen if any box fails)
- [ ] No default Material: no `Card`, no `AlertDialog`, no `Scaffold` FAB, no default purple/teal, no `TopAppBar` with a back arrow + title.
- [ ] No identical rounded cards in a stack. Lists are rows with a severity rail; grouping is done with space, not boxes.
- [ ] No ALL-CAPS tracked-out eyebrow labels above headings. Caps are allowed only for the 3-letter SIM/UWB/GPS badges.
- [ ] No "A · B · C" metadata strings. Telemetry is laid out in aligned columns or as separate, labelled values.
- [ ] No "→" glued to button text, no emoji as icons, no gradients as decoration, no drop shadows on black.
- [ ] No single-word colour accent in a headline. Colour on text means state (verified, critical), nothing else.
- [ ] No lorem ipsum, no "Lorem/John Doe/Test", no empty grey boxes. Every empty state says what to do next.
- [ ] No spinner on a blank screen. Show what is known now (cached incidents, last fix), and mark it as stale.
- [ ] No fake numbers in the final demo. Anything simulated carries the amber SIM badge.
- [ ] Every structural device (rail, divider, number, badge) encodes information. If it doesn't, delete it.
- [ ] Remove one accessory before shipping: after the screen works, take the screenshot and cut one decorative element.

### Mobile UI guidelines (Android, outdoor festival, panicking user)
- [ ] **Touch targets:** 48dp minimum everywhere; civilian primary actions ≥ 72dp tall, SOS ≥ 200dp. 8dp+ between targets.
- [ ] **Thumb zone:** primary action in the bottom third on phone. Nothing critical in the top corners.
- [ ] **One action per civilian screen**; the secondary option is visibly quieter (text button, not a twin button).
- [ ] **Edge-to-edge:** content padded with `WindowInsets.safeDrawing`; nothing under the status bar, nav bar or cutout.
- [ ] **Back is predictable:** system back always goes one step up; destructive or sending steps can always be cancelled.
- [ ] **Font scale:** layout survives 130% system font (Honor default is often large). Use `sp`, no fixed-height text boxes, no clipping.
- [ ] **Contrast:** text ≥ 4.5:1, large text ≥ 3:1. Black text on crimson/amber/green fills (white on crimson fails at small sizes).
- [ ] **Never colour alone:** verified/unverified/critical also carry a word or shape (✓ / ! / ring).
- [ ] **TalkBack:** every icon/Canvas has a `contentDescription` or semantics; SOS announces "Hold to call for help".
- [ ] **Sunlight & night:** OLED black + saturated accents; no thin 1dp text-critical strokes; no Dim text for anything urgent.
- [ ] **Haptics** confirm state, not decoration: SOS armed, alert sent, peer found, packet hop (tablet off), radar proximity.
- [ ] **Offline-first copy:** never say "No internet". Say how many phones are around and whether the alert left this phone.
- [ ] **Language:** short, plain words a stressed parent understands; strings ready for FR / Kreol (no text baked into images).
- [ ] **Orientation:** phone portrait-locked; tablet any orientation, Command Center designed for landscape.
- [ ] **Performance:** Canvas screens hold 60fps on the Honor X8b (Tier 2); no allocations inside `onDraw` loops.

### Copy rules
- Active verbs that say exactly what happens: "Send alert", "Cancel", "I found someone", "Show me the way".
  The same word all the way through: the button "Send alert" leads to "Alert sent".
- Errors say what happened and what to do, in the app's voice, without apologising ("Microphone is off. Type what's happening instead.").
- No names, no photos, no personal details in any example text (privacy rule, CLAUDE.md §4).

### Definition of done per screen
1. Builds, runs on the Honor X8b (and the tablet for Command Center).
2. Screenshot taken via `adb exec-out screencap -p`, reviewed against both checklists above, one accessory removed.
3. Re-checked at 130% font scale and with TalkBack labels.
4. Logged in the Progress log with the time.

## Tasks
**P0 (M1, 18:00)**
- [ ] Theme (colours, fonts, shapes) + permission/boot screen (request `graph.requiredPermissions`, tier badge, SIM badge, hidden SIM toggle)
- [ ] Home (phone): giant hold-to-talk SOS, quick buttons Medical / Hazard / Lost person, "I found someone", "My wristbands"
- [ ] Report flow: waveform → transcript → AI result card (type, severity, by) → auto-send after 3 s unless cancelled
- [ ] Incident feed (from `pipeline.incidents`): severity colour, hops, reportCount, verified state
**P1 (M2/M3)**
- [ ] Guided response sheet: protocol steps, big text, read aloud (`voice.speak`), step timer; nearest help point card with arrow + distance
- [ ] Guardian: mint wristband (`trust.createWristband()`, QR from TRUST) → full-screen FOUND alert → Radar screen
- [ ] Radar screen: sweep, target arrow (`RadarFix.relativeBearingDeg`), distance, source label UWB/GPS/RSSI, proximity haptics
- [ ] Finder: scanner (TRUST component) → "Guardian notified. Stay here." + `pipeline.shareLocationWithGuardian(tag)`
- [ ] **Command Center (tablet)**: tactical grid map (help points + incident heatmap via `GeoMath.cellKey`), live mesh topology with animated hops, merged incident list, stats
**P2**
- [ ] Mesh "demo controls" panel (force multi-hop), French strings, onboarding tips

## Interfaces: what you need from others (open `request` issues early)
- TRUST: `wristbandQrBitmap(tag)` and a `WristbandScanner(onTag)` composable.
- MESH: demo control to force multi-hop.
- AI: `VoiceEvent.Level` at ~12 Hz for the waveform.

## How my part works (5 lines for teammates; fill before 10:00 Sat)

## NEXT_SESSION_HANDOFF
- Built (compiles, on `lane/ui`): theme/fonts, Home (SOS hold-to-talk ring, quick report row, alerts strip), Report flow
  (listen → preview → 3 s auto-send → sent), Feed + incident sheet, Command Center map/panel, Boot (permissions),
  hidden Dev sheet (long-press wordmark: sim toggle + restart, switch phone/CC view), Wristbands (mint/list), Finder frame.
- Waiting on: TRUST #7 (QR bitmap + scanner), MESH #8 (force multi-hop), AI #9 (Level 12 Hz), interface-change #10.
- Next: screenshot review on the Honor against the checklists, then tablet Command Center, then P1 guardian FOUND alert + radar.

## Progress log
| Time | What |
|---|---|
| 14:40 Fri | Onboarded: build OK, `lane/ui` pushed, M0 wiring check runs on Honor X8b (sim=true, 6 peers) |
| 14:45 Fri | Design plan + anti-slop checklist + mobile UI guidelines written (this file) |
| 14:55 Fri | First full UI pass compiles: Home/SOS, Report, Feed, Command Center, Boot, Dev sheet, Wristbands, Finder. Requests #7 TRUST, #8 MESH, #9 AI, interface-change #10 |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 14:45 Fri | Two visual registers: calm civilian phone screens, terminal look only on Command Center/radar | A panicking parent needs a public-safety sign, not a hacker toy; the terminal look is the stage wow on the tablet | One terminal style everywhere (reads as generic AI "hacker" UI, hurts usability) |
| 14:45 Fri | Green means meaning only (verified / connected); neutral buttons are white, SOS crimson | Colour has to carry state reliably under stress | Green as the brand/primary colour |
| 14:48 Fri | Hand-drawn Canvas glyphs, no icon library or emoji | material-icons is not a dependency; one stroke grammar looks crafted | material-icons-extended (+MBs, stock look), emoji |
| 14:50 Fri | Plain state back stack instead of navigation-compose | ~7 screens; type-safe routes need the serialization plugin in `:app` | navigation-compose with string routes / serializable routes |
| 14:52 Fri | Tier 1 = RAM ≥ 7 GB **and** flagship SoC (SM8xxx / Exynos 2xxx / Tensor) or UWB | Honor X8b reports 7.5 GB RAM but has SM6225, would have tried to run the LLM | RAM-only threshold; raising RAM to 10 GB (would demote the S23+) |
| 14:55 Fri | Show the AI result for 3 s, auto-send unless cancelled; proposed `report(raw, triaged)` (#10) | User can catch a wrong triage; what you see is what is sent | Send instantly (no human check), require a confirm tap (slower in panic) |
