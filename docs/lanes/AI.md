# LANE: AI (triage + voice)
Owner: **Seeya** (Member 4) · recommended phone: Galaxy S24 Ultra · Branch `lane/ai` · Issue `[LANE:AI]`

## Mission
Turn panicked speech into a correct, tiny, structured incident on the phone itself, with a fallback that never fails.
The AI is a pipeline stage, not a chatbot.

## You own
`ai/`, `core/.../pipeline/RuleTriageEngine.kt` (keyword fallback), this file.
Implement `TriageEngine` + `VoiceIo` and return them from `AiModule`. The `TRIAGE_REQUEST/RESULT` payload shapes are private to you.

## Tasks
**P0 (device check by 15:00, M1 18:00)**
- [ ] **Device check** on S24 Ultra / S26+ / S23+: which on-device LLM runs? Options: LiteRT-LM (`litertlm-android`,
      Gemma 3 1B or Gemma 3n E2B), MediaPipe LLM Inference (`tasks-genai`), or ML Kit GenAI / Gemini Nano if supported.
      Use **Context7**. Measure load time, latency for one triage prompt, and RAM. Pick ONE and log the decision.
      Models are sideloaded: `adb push <model> /sdcard/Android/data/mu.proxima.app/files/models/`. Never commit them.
- [~] `VoiceIo` (`AndroidVoiceIo` + 8 JVM tests done 15:53; on-device test pending phone): `SpeechRecognizer` (prefer offline; check the offline English pack is installed), RMS → `VoiceEvent.Level`
      for the waveform; `TextToSpeech` for reading protocol steps aloud.
**P1 (M2 23:00)**
- [~] `LlmTriageEngine` (code + 17 JVM tests done 15:37; on-device validation pending Nano download): few-shot prompt → strict JSON matching `TriageResult` → parse with `ProximaJson` → validate enums;
      6 s timeout → fall back to `RuleTriageEngine`. Fill `subtype` from the codes in `RuleTriageEngine` so protocols match.
- [x] (17:14, 11 JVM tests; not device-tested) Tiered engine: Tier 1 with model loaded = `ON_DEVICE_LLM`; otherwise, if a peer has `llmReady` = `OFFLOAD_TO_PEER`
      (TRIAGE_REQUEST ttl 2, wait ≤5 s for TRIAGE_RESULT); otherwise `RULES_ONLY`.
- [x] (17:14) `TriageServer` on Tier 1: answers TRIAGE_REQUESTs from mesh.inbound.
- [~] Kreol keywords (10 `RuleTriageEngineTest` cases pin current EN/FR/Kreol behaviour; native-speaker check still TODO): a Mauritian teammate verifies and extends the list in `RuleTriageEngine`. Add unit tests (`./gradlew :core:test`).
**P2**
- [ ] Kreol/French voice input; hazard photo classification (multimodal model) if time allows.
- [ ] **10:00 Sat: compile all decision logs into a shortlist** for the README.

## Accuracy rule
The AI never generates medical instructions. It outputs type, severity, subtype, summary, landmark and count only.
Steps come from TRUST's cited protocol cards.

## How my part works (5 lines for teammates; fill before 10:00 Sat)

## NEXT_SESSION_HANDOFF
- **18:04 Fri: Gemini Nano does NOT download on the S26+ (SM-S947B, Exynos/SLSI AICore)**: `DOWNLOAD_ERROR ... Feature SAPI Open Prompt
  Stable Fast Mode with SLSI ... Task was cancelled`, twice, on validated Wi-Fi. Not fixable in our code. Next: try Nano on Diya's
  S24 Ultra (Snapdragon AICore); if that fails too → Gemma 3 1B `.litertlm` via LiteRT-LM (`LlmBackend` swap, probe supports it).
- **B hands-free guide: DEVICE-TESTED 18:25 on Diya's S24 Ultra, offline (on-device en-GB):** next / repeat / "he's breathing" (CPR→recovery) /
  "he stop breathing" (recovery→CPR) / stop all correct; background chatter caused no false commands.
  **Demo-phone checklist:** offline speech pack installed (the app requests en-GB once; add fr-FR for French), media volume up,
  mic allowed. Guide pauses other audio while it talks/listens (audio focus).
- B code: `ai/.../guide/` (`HandsFreeGuide`, `GuideCommands`,
  `GuideIntentClassifier`, temp `GuideContract.kt` until #30 lands in core). `AiModule.createGuide(voice, protocols, caps, scope)`.
  Device test: allow mic in Proxima, then `adb shell am start -n mu.proxima.app/mu.proxima.ai.debug.LlmProbeActivity --ez guide true [--es card cpr-adult]`.
- **D crowd-crush warning:** not started (design in #30).
- The S26+ used today was **Chanum's** phone: it now has the AI-lane build (my debug key). He must `adb uninstall mu.proxima.app` before installing his.
- **Works (JVM-tested):** `ai/.../triage/` — `LlmBackend` interface, `GeminiNanoBackend` (ML Kit Prompt API),
  `TriagePrompt` (prompt + strict validator), `LlmTriageEngine` (6 s budget, rules fallback, severity safety net).
  `AiModule.createTriage` returns it on TIER1_COMPUTE, `RuleTriageEngine` otherwise. Capability = RULES_ONLY until Nano is on the phone
  (re-checked every 60 s), so behaviour today is unchanged: rules.
- Tests: `./gradlew :ai:testDebugUnitTest` (17) and `./gradlew :core:test` (10 RuleTriageEngine cases). All green.
- **Not verified on device yet:** Gemini Nano status = DOWNLOADABLE on S26+; download waits for Wi-Fi (phone was on mobile data + hotspot).
- **Exact next step:** phone on real Wi-Fi + USB → `adb shell am start -n mu.proxima.app/mu.proxima.ai.debug.LlmProbeActivity --ez nanoDownload true`
  → `adb logcat -d -s Proxima:*` → record load/latency/RAM/JSON-valid per case → finalise the runtime decision below.
  If Nano is weak/slow (>6 s): sideload Gemma 3 1B `.litertlm` and add a `LiteRtLmBackend` (probe already supports it).
- **VoiceIo (code done, not device-tested):** `ai/.../voice/AndroidVoiceIo.kt` + `VoiceSupport.kt`. AiModule returns it whenever the phone has a
  recognizer (FakeVoiceIo only on emulators without one). Adds RECORD_AUDIO + `<queries>` in `ai/src/main/AndroidManifest.xml`.
  Device test: allow mic in the app, then `adb shell am start -n mu.proxima.app/mu.proxima.ai.debug.LlmProbeActivity --ez voice true [--es lang en-MU|fr-MU|mfe]`.
  Check: on-device recognizer used? which language picked? Partial/Final arrive? speak works offline (airplane mode)?
- Bus requests done on lane/ai (not yet on main): #16 (critical words beat a wrong quick button, rules + LLM path),
  #9 (Level ~12 Hz, stopListening → Final with what was heard, ≤2 s). #10 approved (👍), UI owns that PR.
- **Tier 2 offload (code done, JVM-tested, not device-tested):** `ai/.../offload/`. Every phone gets `TieredTriageEngine`
  (local LLM → peer → rules). Tier 1 also runs `TriageServer`: announces readiness on CONTROL/"ai" every 20 s (ttl 2) and
  answers fresh signed TRIAGE_REQUESTs. Request = text + button only (no location). Peer answers go through
  `TriagePrompt.sanitize` (no protocolId, valid subtype, #16 rule) + `TriageMerge` safety net, marked OFFLOADED_LLM.
  No `core/` change: MESH relays every packet kind generically. MESH's `Beacon.llmReady` is still hard-coded false (unused by us).
  Device test needs 2 phones + Nano on the Tier 1 one: Tier 2 SOS → logcat `offload: answered` on Tier 1.
- Files touched: `ai/build.gradle.kts`, `ai/src/main/.../AiModule.kt`, `ai/src/main/.../triage/*`, `ai/src/debug/*`, `ai/src/test/*`,
  `core/src/test/.../RuleTriageEngineTest.kt`, `gradle/libs.versions.toml` (+mlkit-genai-prompt),
  `ai/src/main/AndroidManifest.xml`, `ai/src/main/.../voice/*`, `ai/src/test/.../voice/*`.
- Phone lent to MESH/LOCATE 15:27 Fri.

## Progress log
| Time | What |
|---|---|
| 15:05 Fri | Onboarded on S26+; wiring check runs (TIER1_COMPUTE, uwb=true). |
| 15:18 Fri | LLM probe built and run: Gemini Nano DOWNLOADABLE on S26+, AICore prod build present. |
| 15:37 Fri | LlmTriageEngine + GeminiNanoBackend + TriagePrompt wired into AiModule (Tier 1); 17 ai tests + 10 RuleTriageEngine tests green. |
| 15:53 Fri | AndroidVoiceIo (offline-first STT + TTS) wired into AiModule; probe gains `--ez voice true`; 25 ai tests green. |
| 15:56 Fri | Bus: #16 + #9 implemented with tests (42 green), #10 approved. |
| 17:14 Fri | Tier 2 offload: TieredTriageEngine + TriageServer + LLM-ready announcements; shared sanitize/merge; 40 ai tests green. |
| 18:04 Fri | Nano download fails on S26+ (Samsung SLSI AICore); B hands-free guide built (16 tests, 56 ai tests green); #30 opened. |
| 18:25 Fri | B device-tested on S24U (all 5 commands). Found+fixed on device: language fallback stuck on en-MU, no offline speech pack (now requested), TTS silent under music (audio focus), "he stop breathing" ended the guide. Nano UNAVAILABLE on S24U too. |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 15:20 Fri | Provisional runtime: **Gemini Nano via ML Kit Prompt API** (pending latency test) | Supported on S26+ (AICore prod build); no model file to sideload, no licence click-through, OS manages memory; fully offline once downloaded | MediaPipe `tasks-genai` (no longer in MediaPipe's current package list → legacy); LiteRT-LM + Gemma kept as fallback behind `LlmBackend` |
| 15:30 Fri | `LlmBackend` interface between engine and runtime | Runtime decision isn't final; swapping Nano↔Gemma must not touch triage logic or tests | Calling ML Kit directly from the engine |
| 15:32 Fri | Keyword rules can **raise** LLM severity (same type), never lower it | Under-triage is the dangerous error; "not breathing" must stay CRITICAL even if the model says MEDIUM | Trusting the LLM alone; averaging |
| 15:33 Fri | Zero-shot prompt with explicit schema + strict validator (not few-shot); invalid type/severity → rules | Shorter prompt = lower latency on device; validator makes bad output harmless | Few-shot examples (revisit if on-device accuracy is poor) |
| 15:53 Fri | Voice: on-device SpeechRecognizer first, language chain en-MU→en-GB→en-US; Kreol (mfe)→fr-FR | No network at the venue; no speech model exists for Mauritian locales; Kreol is French-based so French STT is the closest | Online recognizer (fails offline); English-only |
| 15:53 Fri | Real VoiceIo even in SIM mode (fake only on emulators without a recognizer) | Voice must be exercised on real phones before the demo; sim flag is about mesh/locate data, not the mic | Fake voice whenever sim=true |
| 15:56 Fri | #16: quick-button type kept unless the words match a CRITICAL rule of another type; LLM path: model CRITICAL of another type beats the button, and rules CRITICAL overrides an under-rating model | Panicking users tap the wrong button; hop budget depends on severity; under-triage is the dangerous error | Always trusting the button (old behaviour); words always winning (would ignore deliberate button choice for non-critical cases) |
| 15:56 Fri | #9: hold-to-talk release always ends with Final(best text heard) within 2 s; levels throttled to ~12 Hz | A released SOS button must never lose what the user said; UI ring animates at ~12 Hz | Waiting indefinitely for the recognizer; emitting raw onRmsChanged rate |
| 17:14 Fri | Helpers advertise LLM readiness via signed CONTROL/"ai" packets every 20 s, not `Beacon.llmReady` | Works today with zero contract change; MESH beacons hard-code llmReady=false and wiring it needs an interface change + MESH work | Interface change on MeshEngine; asking every Tier 1 peer blindly (5 s wasted when none is ready) |
| 17:14 Fri | Peer triage results are untrusted: signature checked, re-sanitised, protocolId dropped, rules safety net applied | A relay or malicious node could forge a low-severity answer or point at a wrong protocol card | Trusting a signed answer as-is |
| 17:14 Fri | Offload request carries only text + pressed button (no location); stale (>15 s) requests ignored | Requests are readable by relays; store-and-forward replays old packets to new neighbours | Sending the full RawReport; sealing to a helper key (helpers don't advertise keys) |
| 18:04 Fri | Pivot: drop Kreol accuracy; build B hands-free guided first aid + D crowd-crush early warning (#30) | Kreol can't be made accurate without a model or native reviewer; B and D deepen AI where the hero scenarios are (CPR, Ganga Talao crush) | Description matching (A, kept as backlog); photo classification (CAMERA dropped) |
| 18:04 Fri | Guide: navigation words match whole words only; silence never ends the guide; LLM only maps unclear speech to a closed command list | "stop" fired on "stopped breathing" in tests; CPR involves minutes of silence; AI must never author steps | Prefix matching; timeout after N silent sessions; LLM free-form guidance |
| 18:25 Fri | Voice requests the offline speech pack once (triggerModelDownload) and walks en-MU→en-GB→en-US on the standard recognizer | Demo phones had no offline pack; venue is offline; en-MU is not a recognizer locale | Asking users to install packs manually; English-only |
| 18:25 Fri | Guide/voice hold transient audio focus with USAGE_ASSISTANCE_NAVIGATION_GUIDANCE | On device the instructions were inaudible under music and the mic transcribed it | Ducking only; relying on media volume |
| 18:25 Fri | Breathing status is checked before "stop"; "stop breathing" = not breathing | Recognizer wrote "stopped" as "stop" and the guide ended mid-CPR | Whole-word stop alone |
| 18:25 Fri | Gemini Nano not usable on team phones (S26+ SLSI: download cancelled; S24U: UNAVAILABLE) | AICore eligibility is outside our control | Waiting on AICore updates |
| 15:34 Fri | App never auto-downloads Gemini Nano | No surprise mobile-data use for festival-goers; demo phones get it pre-downloaded on Wi-Fi | Auto-download on first launch |
