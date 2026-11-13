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
- [ ] `VoiceIo`: `SpeechRecognizer` (prefer offline; check the offline English pack is installed), RMS → `VoiceEvent.Level`
      for the waveform; `TextToSpeech` for reading protocol steps aloud.
**P1 (M2 23:00)**
- [ ] `LlmTriageEngine`: few-shot prompt → strict JSON matching `TriageResult` → parse with `ProximaJson` → validate enums;
      6 s timeout → fall back to `RuleTriageEngine`. Fill `subtype` from the codes in `RuleTriageEngine` so protocols match.
- [ ] Tiered engine: Tier 1 with model loaded = `ON_DEVICE_LLM`; otherwise, if a peer has `llmReady` = `OFFLOAD_TO_PEER`
      (TRIAGE_REQUEST ttl 2, wait ≤5 s for TRIAGE_RESULT); otherwise `RULES_ONLY`.
- [ ] `TriageServer` on Tier 1: answers TRIAGE_REQUESTs from mesh.inbound.
- [ ] Kreol keywords: a Mauritian teammate verifies and extends the list in `RuleTriageEngine`. Add unit tests (`./gradlew :core:test`).
**P2**
- [ ] Kreol/French voice input; hazard photo classification (multimodal model) if time allows.
- [ ] **10:00 Sat: compile all decision logs into a shortlist** for the README.

## Accuracy rule
The AI never generates medical instructions. It outputs type, severity, subtype, summary, landmark and count only.
Steps come from TRUST's cited protocol cards.

## How my part works (5 lines for teammates; fill before 10:00 Sat)

## NEXT_SESSION_HANDOFF
- Device spike in progress on Galaxy S26+ (SM-S947B, Android 17, 11.4 GB). Probe: `ai/src/debug/.../LlmProbeActivity.kt`
  (debug builds only). Run: `adb shell am start -n mu.proxima.app/mu.proxima.ai.debug.LlmProbeActivity [--ez nanoDownload true] [--es backend cpu|gpu]`,
  read `adb logcat -d -s Proxima:*`.
- Gemini Nano (ML Kit `genai-prompt` 1.0.0-beta5) status = DOWNLOADABLE. Download requested 15:24 but not started:
  phone had no Wi-Fi (mobile data + hotspot only). Next: phone on Wi-Fi + charging, rerun probe with `--ez nanoDownload true`, record latency/JSON validity.
- LiteRT-LM (0.19.1) wired in the probe but untested: no `.litertlm` sideloaded (Gemma only if Nano disappoints).
- Context7 note: MediaPipe's current Android packages no longer list `tasks-genai`; LLM generation now goes through LiteRT-LM. Treat `mediapipe-tasks-genai` as legacy.
- Phone lent to MESH/LOCATE for testing 15:27 Fri.

## Progress log
| Time | What |
|---|---|
| 15:05 Fri | Onboarded on S26+; wiring check runs (TIER1_COMPUTE, uwb=true). |
| 15:18 Fri | LLM probe built and run: Gemini Nano DOWNLOADABLE on S26+, AICore prod build present. |

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
