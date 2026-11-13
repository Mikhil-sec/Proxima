# LANE: AI (triage + voice)
Owner: see `docs/TEAM_ROLES.md` (recommended phone: Galaxy S24 Ultra) · Branch `lane/ai` · Issue `[LANE:AI]`

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
- Not started. First step: device check (LLM runtime) on the S24 Ultra.

## Progress log
| Time | What |
|---|---|

## Decision log
| Time | Decision | Why | Alternatives rejected |
|---|---|---|---|
