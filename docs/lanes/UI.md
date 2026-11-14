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

### Tokens and anchor: see `DESIGN_RULES.md` (repo root) — single source of truth since 16:20 Fri
Material You anchor (ui-android skill), lagoon-teal seed `#006A6A`, light + dark following the system, Roboto Flex,
M3 shapes/spacing/motion, semantic critical/urgent/caution/safe roles. `./gradlew :app:designCheck` must report 0.
The previous "Field Terminal" look (OLED black, phosphor green, Space Grotesk + JetBrains Mono) is at tag `ui-v1-field-terminal`.

### Two registers (still the core decision, now on Material)
- **Civilian phone screens:** one primary action, `LargeButton` (56 dp), plain sentence-case copy, the SOS disc in the
  critical role as the only loud element.
- **Command Center (tablet):** dense dispatch board — tickets with priority labels, letter pins shared with the site plan.

### Anti-slop checklist (reject the screen if any box fails)
- [ ] Material components configured by the theme (no default purple); one card variant (OutlinedCard); no AlertDialog for emergency flows; no FAB.
- [ ] No identical rounded cards in a stack. Lists are rows with a severity rail; grouping is done with space, not boxes.
- [ ] No ALL-CAPS tracked-out eyebrow labels above headings.
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

## NEXT_SESSION_HANDOFF (06:35 Sat)
**State:** code done and frozen in spirit. Main = docs-only changes since the devices' build `1698627`, so **no reinstall needed**.
All 6 devices run it (simulation OFF, Gemma on S24 Ultra + S26+, S24U ↔ A56 paired). M2 passed on phones (#75).
**Presentation format (lead, 06:10):** show → tell → touch. 90 s lost-child sketch with real phones (UWB radar), then
6 slides, then judges hold the tablet and one presses SOS. Script: `docs/DEMO.md`. Cast: Yorvan parent (S23+),
Diya child, Chanum finder (S26+), Mikhil narrator + tablet, Seeya slides clicker + hands a judge a phone.
**Pitch deck (draft done, private to Mikhil):** https://claude.ai/artifact/Vz4botchqdZgrkZ6fLzuVk (Slides artifact,
slug `eab77efc-5d27-4e63-9be6-1f62434b62eb`). 6 slides: problem (8.5 km) · one app, three moments · no network ·
proof on real phones · wild card Scale up · privacy + limits. App palette, Fraunces + Public Sans, real screenshots,
speaker notes = spoken script. **Open:** slide 2's middle picture is a placeholder for a real radar screenshot
(capture at the dry run with `tools/devices/t.sh <serial> shot <name>`); the member wants to suggest improvements.
**Team tasks (assigned issues):** Diya #89 role phone "Medic tent 2" + README cleanup · Seeya #90 decisions 5–10 ·
Chanum #91 video · Yorvan #92 slides owner (format on #92). Overall plan #88.
**Our next steps:** 1) slide improvements with the member 2) dry run at 07:00 (tablet, A56, S24U on cables) +
radar screenshot 3) check Diya's role phone on the tablet log (`verified MEDIC "Medic tent 2"`) 4) after the dry run:
GitHub Release `v1.0` with the release APK (ask the member before publishing) 5) final README check, submit by 12:30.
**Device tools:** `tools/devices/` (prep.sh, chain.sh, t.sh + README): install, relay chain, tap-by-text.

## Progress log
| Time | What |
|---|---|
| 06:35 Sat | Saving protocol: device scripts moved into `tools/devices/`; handoff for the slide session |
| 06:30 Sat | Pitch deck draft (6 slides, Slides artifact), researched hackathon/anti-slop styles; DEMO.md rewritten as sketch → slides → hands-on; per-member task issues #89–#92 |
| 02:45 Sat | All 6 devices updated in place to `1698627`; architecture diagram + "fresh install = simulation" note in README; release APK built; STATUS refreshed |
| 02:20 Sat | **M2 passed on real phones** (T1 SOS, T2 2-hop, T3 lost & found + privacy, T4 Gemma, T6 friend pairing); fixed radar no-GPS hint + "heard just now" (#85) |
| 02:15 Sat | Surprise card "Scale up": surge control (#83) + 1,400-phone model, README section, slide text |
| 00:50 Sat | Lead override session: `report(raw, triaged)` (#10, core test), verified roles shown in Nearest help + Command Center + role pass wired, **My people** (friend find pairing + radar, shared RadarView), tablet boot copy, 8 GB tier fix. Build, all tests, lint, designCheck 0. **Not yet seen on a device** (tablet locked) |
| 23:30 Fri | All 6 devices reinstalled on `0f7dcf8` (sim OFF, Gemma on S24U + S26P); M2 logic test delegated to Seeya + Yorvan on #75 |
| 19:15 Fri | Tab S6 Lite (landscape) on main build: Command Center queue + plan readable; crowd-risk areas + reasons draw; #20 no-card line confirmed; hands-free guide UI works (buttons; no speech pack yet); rotation keeps state. To fix: boot screen says "phone" and stretches full width on tablet |
| 19:05 Fri | Merged PR #15 (lost & found contract), PR #46 (lane/ui to main), PR #48 (trackFinder fix from LOCATE); #30 wired (guide + crowd risk); posted #53 team sync (19:45 cutoff, one build, M2 sim OFF) |
| 18:05 Fri | Command Center crowd density from beacons (25 m cells, shade + count); fixed cell projection drift (hatching was offset). Built; tablet review pending (Tab S6 Lite not on the cable) |
| 17:58 Fri | #8 done: dev sheet "Demo chain" - switch + tap-to-allow phones (MeshDemoControls.allowedNeighbours); verified untick/re-tick on the Honor |
| 17:55 Fri | Honor light/dark audit: fixed back stack lost on dark-mode/keyboard config change (configChanges), safe green on buttons, missing SIM badges, keyboard hiding "Check alert", "1 hops", stray ListItem boxes, radar labels unreadable in light, truncated home row, dead chip. designCheck 0, lint clean |
| 14:40 Fri | Onboarded: build OK, `lane/ui` pushed, M0 wiring check runs on Honor X8b (sim=true, 6 peers) |
| 14:45 Fri | Design plan + anti-slop checklist + mobile UI guidelines written (this file) |
| 14:55 Fri | First full UI pass compiles: Home/SOS, Report, Feed, Command Center, Boot, Dev sheet, Wristbands, Finder. Requests #7 TRUST, #8 MESH, #9 AI, interface-change #10 |
| 15:05 Fri | On-device review (Honor): SOS hold → voice ring → review → auto-send → "Alert sent, passed to 3 phones" works. Fixed: SOS label, truncated links, "Lost child" titles, duplicate kicker, Sending label |
| 15:30 Fri | SOS arms only after a steady 0.6 s hold (arming arc); verified a swipe + tap no longer fires it |
| 17:10 Fri | #28: beacons carry coarse location + llmReady (ProximaGraph → MeshBeaconInfo); merged main + lane/mesh a548ed6 |
| 16:45 Fri | ui-android overhaul phase 2: every screen rebuilt on Material You tokens + Material Symbols; designCheck 304 → 0 violations; lint clean; core tests green. Device check pending (cable lent to MESH) |
| 16:20 Fri | ui-android skill audited (MIT, markdown + templates only) and installed; phase 1 tokens approved (58/58 contrast pairs AA) |
| 16:00 Fri | Command Center rebuilt as a dispatch board (tickets, someone's going / cleared, site plan with letter pins) |
| 15:47 Fri | Command Center reviewed on the Tab S6 Lite (portrait + landscape). Fixed: map legend, 4-column stats grid, hop log uses map names |
| 15:50 Fri | Nearest-help component on real OSM data: km distances, turning arrow only within 2 km, amber note when help is far (#11); "read steps aloud" when a protocol card exists |
| 15:35 Fri | Wristband dropped (#14, PR #15). New: Finder picks the lost alert ("I'm with them") → sealed location; guardian banner, full-screen Found alert, Radar (sweep, blip, haptic ticks). Verified on Honor in SIM |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 06:10 Sat | Presentation = show → tell → touch: a 90 s lost-child sketch, then 6 slides, then judges hold the tablet and send an SOS | A story is remembered and proves it works live; hands-on proves it isn't staged; jargon-led demos bore judges | Feature-by-feature demo (jargon); slides only (looks like vapourware) |
| 06:10 Sat | The sketch uses lost & found, not friend find | Lost & found passed on phones with an exact UWB arrow indoors; the friend radar needs GPS and stays on "step outside" indoors | Friend-distress radar (would fail on stage) |
| 06:30 Sat | Deck look: the app's own palette, documentary type, only real screenshots and measured numbers, a different layout per slide | Research: repeated layouts, icon grids, stock imagery and generic copy are the AI-slop tells; real proof wins hackathons | A template/AI deck tool; purple gradients and icon cards |
| 00:30 Sat | Friend pairing = pick the phone whose row shows the same six digits, then "The numbers match" on both | No camera (dropped in #18) and node ids change per session; one number both people can read aloud identifies the phone and authenticates the keys in one step | Typing node ids; QR; pairing with no confirmation (anyone in range could add you) |
| 00:30 Sat | Friend invites travel as CONTROL packets on channel "friend", sealed to the peer | No new packet kind (one pipeline rule); CONTROL is live-only, so a stale invite is never replayed from store-and-forward | New PacketKind (contract change at 00:30); SEALED (pipeline would try to parse it as a FoundNotice) |
| 00:35 Sat | Verified posts go first in "Nearest help", fixed OSM points after | A tent 80 m away beats a hospital 8.6 km away; the far-help warning is hidden when a live post is in range | A separate "staff" list (two places to look under stress) |
| 17:50 Fri | Activity handles uiMode/keyboard/orientation config changes itself | Honor is on night mode "auto": a report in progress at sunset was thrown away with the back stack; Compose-only activity re-themes in place (Android guidance) | Saveable back stack (Parcelable routes, still loses in-flight capture state) |
| 17:55 Fri | Mic fallback is a calm tonal note showing the engine's own reason | Not an emergency; red is reserved for SOS; the AI lane's reasons already say what to do | Generic crimson error text |
| 17:58 Fri | Demo chain switch seeds the allow-list with the phones linked right now | Nothing drops until you untick one, so a misclick can't break the stage mesh | Start from an empty list (drops every link at once) |
| 18:00 Fri | Density = teal-tinted 25 m cells with a count, under everything else | Density is not severity, so no red/amber; the count makes the shade readable from 2 m; cells, never dots, so no one is placed | Red-amber heatmap (reads as danger); one dot per phone (tracks individuals) |
| 14:45 Fri | Two visual registers: calm civilian phone screens, terminal look only on Command Center/radar | A panicking parent needs a public-safety sign, not a hacker toy; the terminal look is the stage wow on the tablet | One terminal style everywhere (reads as generic AI "hacker" UI, hurts usability) |
| 14:45 Fri | Green means meaning only (verified / connected); neutral buttons are white, SOS crimson | Colour has to carry state reliably under stress | Green as the brand/primary colour |
| 14:48 Fri | Hand-drawn Canvas glyphs, no icon library or emoji | material-icons is not a dependency; one stroke grammar looks crafted | material-icons-extended (+MBs, stock look), emoji |
| 14:50 Fri | Plain state back stack instead of navigation-compose | ~7 screens; type-safe routes need the serialization plugin in `:app` | navigation-compose with string routes / serializable routes |
| 14:52 Fri | Tier 1 = RAM ≥ 7 GB **and** flagship SoC (SM8xxx / Exynos 2xxx / Tensor) or UWB | Honor X8b reports 7.5 GB RAM but has SM6225, would have tried to run the LLM | RAM-only threshold; raising RAM to 10 GB (would demote the S23+) |
| 16:15 Fri | Replace "Field Terminal" with the ui-android skill's Material You anchor, teal seed, light + dark, Roboto Flex | Lead judged the neon-on-black look, glow and Grotesk/Mono pair as AI slop; a native Android safety app reads more trustworthy | Keep dark-only terminal look; Stripe/Notion/Editorial anchors |
| 16:15 Fri | Ban decorative motion: no SOS halo pulse, no radar sweep | They read as "LED" decoration; motion now only answers events (arming arc, hop pulses, haptics) | Keep ambient animations with reduced-motion opt-out |
| 16:20 Fri | designCheck Gradle task instead of detekt | detekt 1.23 can't parse Kotlin 2.4; regex gate gives the same slop checks in `check` | detekt 2.0 alpha; no automated gate |
| 15:55 Fri | Tablet = dispatch board (queue first, map second) | A steward needs "who to send, where, how long waiting" from 2 m; judges still see live hops | Map-dominant plan; triage-band columns |
| 15:30 Fri | SOS ring needs a 0.6 s steady hold, aborts on movement past touch slop | Brushing/scrolling across the ring triggered it | Instant press; double-tap; confirm dialog |
| 15:35 Fri | Drop QR wristband; finder answers the guardian's lost alert, location sealed to the reporter key | No hardware; same privacy property; one fewer module (camera) | QR wristband + scanner; NFC |
| 15:35 Fri | Radar = terminal register (sweep, rings, mono distance); found alert = civilian register in green | Good news must read instantly; the radar is a tool, can be dense | Map view (needs tiles, offline) |
| 14:55 Fri | Show the AI result for 3 s, auto-send unless cancelled; proposed `report(raw, triaged)` (#10) | User can catch a wrong triage; what you see is what is sent | Send instantly (no human check), require a confirm tap (slower in panic) |
