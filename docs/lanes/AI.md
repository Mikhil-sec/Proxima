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
- [x] **Device check** (19:27): Gemini Nano unusable (S26+ download cancelled, S24U unavailable) → **Gemma 4 E2B on LiteRT-LM**, measured on S24U: ~8.5 s load, ~1.5 s/report (~2.5 s with full app), ~2.1 GB PSS. Model sideloaded, never committed.
- [x] `VoiceIo` (`AndroidVoiceIo`, device-tested offline on S24U 18:25): `SpeechRecognizer` (prefer offline; check the offline English pack is installed), RMS → `VoiceEvent.Level`
      for the waveform; `TextToSpeech` for reading protocol steps aloud.
**P1 (M2 23:00)**
- [x] `LlmTriageEngine` (device-tested with Gemma on S24U, 56/56 valid JSON): prompt → strict JSON matching `TriageResult` → parse with `ProximaJson` → validate enums;
      6 s timeout → fall back to `RuleTriageEngine`. Fill `subtype` from the codes in `RuleTriageEngine` so protocols match.
- [x] (17:14, 11 JVM tests; not device-tested) Tiered engine: Tier 1 with model loaded = `ON_DEVICE_LLM`; otherwise, if a peer has `llmReady` = `OFFLOAD_TO_PEER`
      (TRIAGE_REQUEST ttl 2, wait ≤5 s for TRIAGE_RESULT); otherwise `RULES_ONLY`.
- [x] (17:14) `TriageServer` on Tier 1: answers TRIAGE_REQUESTs from mesh.inbound.
- [~] Kreol: **parked (pivot 18:04)**. Best-effort keywords only; scored separately in the eval as "not tuned". Native-speaker check still TODO if time.
**Pivot (#30, 17:40 Fri)**
- [x] B hands-free guided first aid: device-tested on S24U.
- [~] D crowd-crush early warning: code + tests, UI wired by Mikhil (#46), contract in core (#49); needs multi-phone test.
- [x] On-device LLM: Gemma 4 E2B (`LiteRtLmBackend`, shared engine, streaming + cancel) on main (#67). Eval harness `ai/eval/` + severity floor.
**P2**
- [ ] Kreol/French voice input; hazard photo classification (multimodal model) if time allows.
- [x] **Key decisions (#90):** final 10 rows in README `## Key decisions` (PR #95, 06:05 Sat). AI slide sent to Yorvan on #92.
- [x] **#45:** proof-read posted 20:55 Fri (no contradictions; 4 fix requests to TRUST). Timer issue fixed in the guide (PR #76).

## Accuracy rule
The AI never generates medical instructions. It outputs type, severity, subtype, summary, landmark and count only.
Steps come from TRUST's cited protocol cards.

## How my part works (5 lines for teammates; fill before 10:00 Sat)
1. **Triage:** every SOS goes through `TieredTriageEngine`: local on-device LLM (Gemma 4 E2B, sideloaded) → a nearby Tier 1 phone's LLM over the mesh (offload) → keyword rules (`RuleTriageEngine`, EN/FR/Kreol best-effort). It never fails and never waits more than 6 s.
2. **Safety:** the model only classifies (type, severity, subtype, summary, landmark, count). Output is validated, protocol cards are always picked locally from TRUST's cited cards, and keyword rules can raise severity but never lower it, and severity never drops below the floor for its subtype (`ai/eval/` measures this). A wrong quick button can't hide a critical description (#16).
3. **Voice:** offline-first speech in/out (`AndroidVoiceIo`): on-device recognizer, language fallback en-MU→en-GB→en-US (Kreol→French), takes audio focus so instructions are heard over music.
4. **Hands-free first aid (B):** reads a vetted card step by step and follows "next / repeat / stop". "He's breathing" switches CPR → recovery position; it picks cards, never writes steps.
5. **Crowd-crush early warning (D):** anonymous 25 m density cells + nearby crowd reports + fainting clusters → WATCH/WARNING/DANGER. Only the Command Center raises one HAZARD/CROWD_CRUSH alert per area per 10 min.

## NEXT_SESSION_HANDOFF
_Saved 06:15 Sat by Seeya's session. `lane/ai` = `origin/main` (`2a88fcd`) + this saving-protocol commit (lane file only).
**All AI lane code and README work is on main.** Feature freeze 08:00 Sat · README final 10:30 · submit 12:30. The lead has installed the latest build on every demo
phone and finished device testing (#88/#90): **do not touch any phone unless Seeya asks.**_

**Done and on main**
- Triage: `TieredTriageEngine` (Gemma on device → nearby AI phone over the mesh → `RuleTriageEngine`, max 6 s), strict parser,
  severity floor per subtype, rules raise but never lower. Seizure + live wire = CRITICAL (hop budget 10) (PR #71).
- Gemma 4 E2B via LiteRT-LM, loaded once at app start from `/sdcard/Android/data/mu.proxima.app/files/models/`. Phone test
  (#75 T4): bleeding report sorted in 1.4 s on the S24 Ultra; review screen = Command Center ticket ("On-device AI").
- Success is now logged (PR #81): `triage: ON_DEVICE_LLM TYPE/SUBTYPE/SEVERITY in N ms` (+ `(before rules: …)`), labels only.
- Hands-free guide: timed steps add "Say next to hear the next step now." so "call EMS" isn't held behind a 20-min timer (PR #76).
- Voice, crowd-crush warning (D), offload, eval harness `ai/eval/` (honest number: `REPORT_v3_fresh.md`).
- **#45** proof-read posted on the issue. **#90** done: README `## Key decisions` = final 10 rows with alternatives, AI
  disclosure row updated (PR #95), AI slide posted to Yorvan on #92.

**Open / risks (none blocking the AI lane)**
- **#45 card fixes not applied yet** by TRUST (`protocols.json` unchanged since c149e59): page numbers, spine caution before
  "roll onto side", ABC check on assess-person, 5 wording nits. TRUST's file and call; a change after the 08:00 freeze needs the
  lead's OK, and the phones need a rebuild to pick it up. Nudge Diya on #45 only if Seeya wants.
- Phones: S24 Ultra = Gemma (verified 23:23 Fri). S23+ = storage full (1.2 GB free) → rules + offload. S26 has the model; lead
  says testing is done. Honor = rules only. Crowd crush (D) never tested on several phones.
- Accuracy is modest (fresh set: shipped path under-rated 4 of 7 before fixes). Don't claim a new number; don't tune on `fs*`/`ho*`.
- Honest notes for judges (from #90): A56 didn't offload in T1 (rules sorted it); rules parsed "gate, not breathing" as the
  landmark "Gate not breathing". No code changes after the 08:00 freeze.
- Old AI issues still open on GitHub (#9, #10, #16, #30, #35): all implemented/answered earlier; leave closing to their authors.

**Exact next steps (in order)**
1. Check the bus: `gh issue view 1 --comments | tail -n 30`, `gh issue list --label lane:ai --state open`, and #90 / #92 / #45
   for replies (Yorvan may ask about the slide; Diya may answer #45).
2. 10:30 Sat: read the final README once (Key decisions, AI disclosure row, AI accuracy paragraph) and fix only AI-lane errors,
   docs-only PR, wait for CI on the head sha, merge.
3. Be ready to answer judges' AI questions (README "Measured" section + the honest notes above). 12:30 the lead submits.

**Device notes**
- Demo phones are signed with **Mikhil's laptop debug key**; an install from this laptop is refused (only an uninstall would get
  round it, which wipes the teammate's data and model). Never install on them; ask Seeya before any uninstall.
- Read-only checks are fine when asked: `adb logcat -d -s Proxima:* | grep -E "llm:|triage:"`, `dumpsys package mu.proxima.app`,
  `ls` of the models folder. Check `topResumedActivity` before any key press or screenshot; restart the app only with Seeya's OK.
- Gemma file: `C:/Users/THIS-PC/Downloads/proxima-models/gemma-4-E2B-it.litertlm` (2,588,147,712 bytes); push ~80 s over USB.
- Git Bash: prefix adb with `MSYS_NO_PATHCONV=1`. Mauritius time = UTC + 4. Never read `build/` (use Gradle `-i` output for test failures).

**Files touched this session:** `ai/.../guide/HandsFreeGuide.kt` (+ test), `ai/.../triage/LlmTriageEngine.kt` (+ test),
`README.md` (Key decisions, AI disclosure row), `docs/lanes/AI.md`.

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
| 18:46 Fri | PR #41 merged to main (offload, B, D, voice fix); #35 + #1 notified. Gemma 4 E2B model downloaded (2,588,147,712 bytes, size matches HF). |
| 18:48 Fri | `LiteRtLmBackend` (Gemma via LiteRT-LM, shared engine) + `FirstReadyBackend` (Gemma → Nano) wired in AiModule; GPU native libs declared; 7 new JVM tests green. |
| 18:55 Fri | Fix: timed-out Gemma call no longer blocks the next SOS (streaming + cancelProcess, +1 test). Pinged Mikhil on #30. |
| 19:00 Fri | Triage accuracy eval (`ai/eval/`, 32 cases, rules vs model vs shipped). Laptop run blocked by Windows Application Control (WinError 4551) → added on-phone eval mode to the probe. |
| 19:10 Fri | #30 approved by all lanes → PR #49 merged: guide + crowd contract in `core/`, deprecated aliases in `ai/`. README tech stack + AI disclosure row written (#23), no LLM results claimed yet. |
| 20:10 Fri | Lead merged all lanes (PR #67 took lane/ai at a1923f3); pulled main fb20894, build+tests green (#68); posted Gemma push steps. Aliases removed + "1 phone" fix on lane/ai. |
| 20:23 Fri | Fresh teammate set (12) run once on Diya's S24U with the team build (no install, eval folder removed after): EN+FR shipped under-triage 4/7, Kreol 1/5. |
| 20:35 Fri | Seizure/electrical → CRITICAL, "fils", "old lady", lost/found floor; README reports fresh set pre-fix; REPORT_v3_fresh.md frozen. |
| 20:40 Fri | Saving protocol: handoff rewritten; #23 answered (AI README already on main); memory note on teammate-phone safety. |
| 20:45 Fri | PR #71 merged (main reopened): Gemma backend, eval, seizure/live wire CRITICAL on main. |
| 20:55 Fri | #45 proof-read posted: 9 cards vs IFRC 2020 PDF, no contradictions; 5 pages, 1 order fix, ABC check, 5 wording nits to TRUST. |
| 21:00 Fri | Guide: timed steps now say "Say next to hear the next step now." (+1 test). |
| 23:25 Fri | S24U (Diya): Gemma loaded 4.6 s after app restart, seizure SOS → Critical/Seizure on screen. S23+ (Yorvan): new build, storage full (1.2 GB) → rules + offload. S26 (Chanum): model pushed, waiting for lead's build. |
| 23:40 Fri | Triage success now logged: `triage: ON_DEVICE_LLM TYPE/SUBTYPE/SEVERITY in N ms` (+ `before rules:` when raised). Labels only, no report text. +2 tests. |
| 06:15 Sat | #90 done (10 key decisions + disclosure row, PR #95; AI slide to Yorvan on #92). Saving protocol: handoff rewritten, shortlist draft removed (final list is in README). |

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
| 18:44 Fri | Merge lane/ai → main now (PR #41), before Gemma work | #35 (Honor voice) was blocked on it; B/D/offload need integration time before the 08:00 freeze; a Gemma change shouldn't hold back tested work | Waiting to merge everything together with Gemma |
| 18:48 Fri | **On-device LLM = Gemma 4 E2B via LiteRT-LM** (`gemma-4-E2B-it.litertlm`, Apache 2.0, sideloaded), Gemini Nano kept as second choice via `FirstReadyBackend` | Nano unusable on all team phones; Gemma is not gated, runs on any Android GPU/CPU, Google reports 0.3 s TTFT / ~52 tok/s GPU on S26 Ultra | Qwen3-1.7B (fallback if Gemma fails on device); rules + offload only; the `-gpu.litertlm` file (it is the Web build) |
| 18:48 Fri | One LLM engine per process, loaded once in the background at startup, shared by triage + guide behind a mutex; `isReady` false while loading | 2.6 GB can't be loaded twice; load takes up to ~10 s and must never block an SOS (rules answer meanwhile) | Lazy load on first SOS (first report would hit the timeout); one engine per feature |
| 18:55 Fri | Gemma generation streams and is cancelled on timeout (`sendMessageAsync` + `cancelProcess`) | Blocking `sendMessage` kept the shared engine busy after the 6 s timeout, so the next SOS or guide command would queue behind it | Blocking call + separate engine per feature (2× RAM) |
| 19:00 Fri | Accuracy eval scores the **shipped** path (real prompt, parser, sanitize, rules safety net, 6 s budget) with labels fixed before any run | Judges and we need a number that reflects what the app does, not the raw model; fixing labels first prevents tuning to results | Scoring raw model output; eyeballing a few examples |
| 19:00 Fri | Do not bypass Windows Application Control to run the eval on the laptop; run it on the phone instead | Security policy on the machine; the phone run also gives real latency/RAM | Disabling Smart App Control (irreversible without reset) |
| 19:05 Fri | Contract move to `core/` keeps deprecated typealiases at the old `ai/` names | UI had already merged code importing the `ai/` names (#46); aliases avoid breaking `app/` (UI-owned) near the freeze | Moving and breaking imports in the same hour |
| 20:20 Fri | Fresh test set written by a teammate (labels verbatim, committed before the run, run once) is the headline accuracy number | Every earlier set was written by the same person who tuned the code; only an independent set is honest | Reporting the tuned 1-of-44 figure |
| 20:30 Fri | **Seizure and live wire raised HIGH → CRITICAL** (rules + severity floor), adopting the teammate's ratings (Seeya) | Under-triage is the dangerous error; both justify the widest alert (hop budget 10 vs 7) | Keeping HIGH and reporting them as policy disagreements |
| 20:30 Fri | Fix the two real fresh-set misses, but report the fresh number as measured **before** the fixes (Seeya) | Fixing after seeing a test set inflates it; judges get the unbiased number plus what we changed | Re-scoring after fixes; not fixing |
| 15:34 Fri | App never auto-downloads Gemini Nano | No surprise mobile-data use for festival-goers; demo phones get it pre-downloaded on Wi-Fi | Auto-download on first launch |
| 20:55 Fri | Timed steps held back "call EMS" (burns/fracture 20 min, heat 15, seizure 5, CPR defib 2): fix in the **guide** with a spoken next-step cue, not by reordering cards (Seeya) | One change covers all 5 cards, stays in AI lane, cards keep IFRC order; cue is navigation only so no AI-authored advice | TRUST reorders cards; reading the next step automatically after a timed step (breaks one-action-per-step) |
| 06:05 Sat | README Key decisions = 10 rows across all lanes, each with alternatives rejected (#90) | Mix of product scope, safety, privacy, honesty and the surprise card; the deliverable requires times + alternatives | Kept from draft but cut: feature cut (13:10), modules (13:35), raw Overpass (14:43), node id from key (18:05) |
