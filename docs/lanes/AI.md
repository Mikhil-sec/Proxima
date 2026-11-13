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
**Pivot (#30, 17:40 Fri)**
- [x] B hands-free guided first aid: device-tested on S24U.
- [~] D crowd-crush early warning: code + tests; needs UI wiring + multi-phone test.
- [ ] On-device LLM: Gemma 4 E2B (Nano unusable on team phones).
**P2**
- [ ] Kreol/French voice input; hazard photo classification (multimodal model) if time allows.
- [ ] **10:00 Sat: compile all decision logs into a shortlist** for the README.

## Accuracy rule
The AI never generates medical instructions. It outputs type, severity, subtype, summary, landmark and count only.
Steps come from TRUST's cited protocol cards.

## How my part works (5 lines for teammates; fill before 10:00 Sat)
1. **Triage:** every SOS goes through `TieredTriageEngine`: local on-device LLM → a nearby Tier 1 phone's LLM over the mesh (offload) → keyword rules (`RuleTriageEngine`, EN/FR/Kreol best-effort). It never fails and never waits more than 6 s.
2. **Safety:** the model only classifies (type, severity, subtype, summary, landmark, count). Output is validated, protocol cards are always picked locally from TRUST's cited cards, and keyword rules can raise severity but never lower it. A wrong quick button can't hide a critical description (#16).
3. **Voice:** offline-first speech in/out (`AndroidVoiceIo`): on-device recognizer, language fallback en-MU→en-GB→en-US (Kreol→French), takes audio focus so instructions are heard over music.
4. **Hands-free first aid (B):** reads a vetted card step by step and follows "next / repeat / stop". "He's breathing" switches CPR → recovery position; it picks cards, never writes steps.
5. **Crowd-crush early warning (D):** anonymous 25 m density cells + nearby crowd reports + fainting clusters → WATCH/WARNING/DANGER. Only the Command Center raises one HAZARD/CROWD_CRUSH alert per area per 10 min.

## NEXT_SESSION_HANDOFF
_Saved 18:40 Fri by Seeya's session. Branch `lane/ai`, pushed, clean. Last AI merge to main: PR #21 at 16:14._

**Done and working**
- Triage: `LlmTriageEngine` (backend-agnostic `LlmBackend`), `TieredTriageEngine` + `TriageServer` (Tier 2 offload over the mesh, LLM-ready announcements on CONTROL/"ai"), shared `TriagePrompt.sanitize` + `TriageMerge` safety net. JVM-tested only.
- Voice (`ai/.../voice/`): **device-tested offline on Diya's S24 Ultra.** Language fallback, offline pack request (`triggerModelDownload`), audio focus (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`), #9 (12 Hz levels, hold-to-talk Final).
- **B hands-free guide** (`ai/.../guide/`, `AiModule.createGuide`): **device-tested on the S24U**: next / repeat / "he's breathing" → recovery / "he stop breathing" → CPR / stop.
- **D crowd-crush warning** (`ai/.../crowd/`, `AiModule.createCrowdRisk`): 15 JVM tests, not device-tested (needs several phones outdoors + the tablet).
- Bus: #16, #9 done; #10 👍; #35 answered (fix is b5bc9d0, on lane/ai only). Tests: **72 ai + 13 core green**.

**Broken / not done / blocked**
- **No on-device LLM works yet.** Gemini Nano: S26+ (Chanum's, Exynos) download cancelled by Samsung AICore; S24U (Diya's) UNAVAILABLE. So triage = rules in practice, and offload has no helper.
- **Not on main yet:** offload, B, D, voice fixes (#35 needs them). Merge needs Seeya's OK.
- **#30 waiting for Mikhil's 👍** (Diya 👍, Chanum 👍; #28 cell locations are on main via PR #32). After 👍: move `GuideContract.kt` + `CrowdContract.kt` shapes to `core/` in a small dedicated PR. UI must wire `createGuide` / `createCrowdRisk` in ProximaGraph.
- **#10 not merged:** `CrowdRiskMonitor` raises via `pipeline.report(raw)`. When #10 lands, pass our own TriageResult (TODO in AiModule).
- **#23 README AI section** (due 10:30 Sat) not written. **10:00 Sat:** AI compiles the team decision-log shortlist.

**Exact next steps (in order)**
1. Ask Seeya: merge `lane/ai` → main now? (`git fetch && git merge origin/main`, `./gradlew assembleDebug test`, PR, wait for CI `assemble`, `gh pr merge --merge`). Then tell Mikhil on #35 that the fix is live.
2. LLM plan B = **Gemma 4 E2B** (`litert-community/gemma-4-E2B-it-litert-lm`, `gemma-4-E2B-it.litertlm`, ~2.6 GB, Apache 2.0, ~52 tok/s GPU on S26 Ultra per Google). Seeya downloads it. `adb push` to `/sdcard/Android/data/mu.proxima.app/files/models/`, run the probe (`--es backend gpu`) and record load / latency / RAM / JSON-valid, then add `LiteRtLmBackend : LlmBackend` (load once at app start) and swap it into `AiModule` + `createGuide`. Fallback candidate: Qwen3-1.7B (Apache 2.0, disable thinking mode).
3. After Mikhil's 👍 on #30: core PR for the B/D shapes.
4. README AI section (#23) + decision shortlist (10:00 Sat).

**Device notes**
- Probe: `adb shell am start -n mu.proxima.app/mu.proxima.ai.debug.LlmProbeActivity` + `--ez nanoDownload true` | `--ez voice true` | `--ez guide true --es card cpr-adult` | `--es backend cpu|gpu`. Logs: `adb logcat -d -s Proxima:*`.
- Other members' phones carry their own debug key: installing ours needs `adb uninstall mu.proxima.app` → **always ask the owner first** (done today for Chanum's S26+ and Diya's S24U, both notified on #3 / #6).
- Demo-phone checklist: offline speech pack (en-GB; fr-FR for French), volume up, mic allowed, model file sideloaded (once Gemma is chosen).

**Files touched today:** `ai/build.gradle.kts`, `ai/src/main/AndroidManifest.xml`, `ai/src/main/.../{AiModule.kt, triage/*, offload/*, voice/*, guide/*, crowd/*}`, `ai/src/debug/*`, `ai/src/test/*`, `core/.../pipeline/RuleTriageEngine.kt` (+ test), `gradle/libs.versions.toml` (+mlkit-genai-prompt), `docs/TEAM_ROLES.md`, `docs/lanes/AI.md`.

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
| 18:33 Fri | D crowd-crush early warning built (CrowdRiskModel + CrowdRiskMonitor, 15 tests, 72 ai tests green). |
| 18:40 Fri | Saving protocol: handoff rewritten; #35 answered (fix on lane/ai). |

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
| 18:33 Fri | D: density alone never exceeds WATCH; WARNING requires a human crowd report; only the Command Center raises alerts | Phone counts are app users only (lower bound) and a packed stage front is normal; one raiser avoids alert storms; merger dedups the rest | Pure density thresholds; every phone raising alerts |
| 18:33 Fri | D excludes its own alerts by incident id (not just by text) | An LLM re-triaging the alert can rewrite its summary → self-escalation loop | Prefix-only exclusion |
| 15:34 Fri | App never auto-downloads Gemini Nano | No surprise mobile-data use for festival-goers; demo phones get it pre-downloaded on Wi-Fi | Auto-download on first launch |
