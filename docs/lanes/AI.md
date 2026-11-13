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
- [ ] Tiered engine: Tier 1 with model loaded = `ON_DEVICE_LLM`; otherwise, if a peer has `llmReady` = `OFFLOAD_TO_PEER`
      (TRIAGE_REQUEST ttl 2, wait ≤5 s for TRIAGE_RESULT); otherwise `RULES_ONLY`.
- [ ] `TriageServer` on Tier 1: answers TRIAGE_REQUESTs from mesh.inbound.
- [~] Kreol keywords (10 `RuleTriageEngineTest` cases pin current EN/FR/Kreol behaviour; native-speaker check still TODO): a Mauritian teammate verifies and extends the list in `RuleTriageEngine`. Add unit tests (`./gradlew :core:test`).
**P2**
- [ ] Kreol/French voice input; hazard photo classification (multimodal model) if time allows.
- [ ] **10:00 Sat: compile all decision logs into a shortlist** for the README.

## Accuracy rule
The AI never generates medical instructions. It outputs type, severity, subtype, summary, landmark and count only.
Steps come from TRUST's cited protocol cards.

## How my part works (5 lines for teammates; fill before 10:00 Sat)

## NEXT_SESSION_HANDOFF
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
- Then: Tier 2 offload + `TriageServer` (P1).
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

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
| 15:20 Fri | Provisional runtime: **Gemini Nano via ML Kit Prompt API** (pending latency test) | Supported on S26+ (AICore prod build); no model file to sideload, no licence click-through, OS manages memory; fully offline once downloaded | MediaPipe `tasks-genai` (no longer in MediaPipe's current package list → legacy); LiteRT-LM + Gemma kept as fallback behind `LlmBackend` |
| 15:30 Fri | `LlmBackend` interface between engine and runtime | Runtime decision isn't final; swapping Nano↔Gemma must not touch triage logic or tests | Calling ML Kit directly from the engine |
| 15:32 Fri | Keyword rules can **raise** LLM severity (same type), never lower it | Under-triage is the dangerous error; "not breathing" must stay CRITICAL even if the model says MEDIUM | Trusting the LLM alone; averaging |
| 15:33 Fri | Zero-shot prompt with explicit schema + strict validator (not few-shot); invalid type/severity → rules | Shorter prompt = lower latency on device; validator makes bad output harmless | Few-shot examples (revisit if on-device accuracy is poor) |
| 15:53 Fri | Voice: on-device SpeechRecognizer first, language chain en-MU→en-GB→en-US; Kreol (mfe)→fr-FR | No network at the venue; no speech model exists for Mauritian locales; Kreol is French-based so French STT is the closest | Online recognizer (fails offline); English-only |
| 15:53 Fri | Real VoiceIo even in SIM mode (fake only on emulators without a recognizer) | Voice must be exercised on real phones before the demo; sim flag is about mesh/locate data, not the mic | Fake voice whenever sim=true |
| 15:34 Fri | App never auto-downloads Gemini Nano | No surprise mobile-data use for festival-goers; demo phones get it pre-downloaded on Wi-Fi | Auto-download on first launch |
