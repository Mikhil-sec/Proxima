# LANE: UI + Command Center
Owner: **Member 1** · Phones: Honor X8b (Tier 2) + Galaxy Tab S6 Lite (Command Center) · Branch `lane/ui` · Issue `[LANE:UI]`

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
- Not started. First step: theme + boot screen on the Honor, then the Command Center skeleton on the tablet.

## Progress log
| Time | What |
|---|---|

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
