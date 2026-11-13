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
- [~] **10:00 Sat: decision shortlist**: draft done (section below, 10 + 5 bench); team picks at 10:00, then into README with Diya.
- [ ] **#45 (TRUST→AI): proof-read the 9 protocol cards against the IFRC 2020 PDF, by 08:00 Sat.** Must be Seeya (second member). Claude can prepare a side-by-side.

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
_Saved 20:40 Fri by Seeya's session. Branch `lane/ai` pushed and clean at the saving-protocol commit, **5 code/doc commits ahead
of main** (ee0e6aa, b0aba06, d743ee9, f3f2c9f, 91c6fbc) + this save. Main = `fb20894`, **frozen by the lead for the M2 run**
(#53 / #68): no PRs into main until he reopens it._

**Done and working (on main via PR #67 unless noted)**
- Triage: `TieredTriageEngine` (local LLM → peer offload → `RuleTriageEngine`, never more than 6 s), `LlmTriageEngine` + `TriagePrompt`
  (strict parser, sanitize, subtype-in-type recovery, lost-child button override, **severity floor per subtype**) + `TriageMerge`
  (rules raise severity, never lower it).
- **Gemma 4 E2B** via `LiteRtLmBackend` (`ai/.../triage/LiteRtLmBackend.kt`): loads once at app start from
  `/sdcard/Android/data/mu.proxima.app/files/models/*.litertlm`, GPU → CPU fallback, shared by triage + guide behind a mutex,
  streaming + `cancelProcess` on timeout. `FirstReadyBackend` = Gemma, else Gemini Nano. Device-tested on Diya's S24U.
  No model file → rules only, nothing breaks.
- Voice (offline, device-tested), **B** hands-free guide (device-tested), **D** crowd-crush warning (JVM-tested; UI wired in #46;
  contract in core via #49).
- **Eval** `ai/eval/` (README there): `cases.jsonl` = 56 (32 original, 12 `ho*` held-out, 12 `fs*` fresh teammate set);
  `TriageEvalTest` (`-PproximaEval=export` | `-PproximaEval=score:gemma@<device>`); on-phone runner =
  `LlmProbeActivity --ez eval true`. Reports: `REPORT_v1_baseline.md`, `REPORT_v2.md`, **`REPORT_v3_fresh.md` (frozen honest
  number)**, `REPORT.md` (latest generated).
- README (#23): tech stack, fallback paragraph, measured phone numbers, AI disclosure row (on main). Honest accuracy paragraph
  with the fresh set reported pre-fix (on `lane/ai`).
- Only on `lane/ai` (needs a PR when main reopens): deprecated aliases removed + "1 phone" wording (#68 asks), fresh set +
  results, **seizure & live wire → CRITICAL**, "fils" / "old lady", lost/found severity floor, README accuracy update, lane logs.
  Full build + all module tests green.

**Broken / not done / risks**
- Main frozen → the commits above are not on main yet. When reopened: `git fetch && git merge origin/main`,
  `./gradlew assembleDebug test`, `gh pr create`, wait for CI `assemble` on the **current head sha**
  (`gh run list --branch lane/ai`; `gh pr checks --watch` can exit early on an older run), then `gh pr merge --merge`.
  **The PR body must warn MESH + UI: seizure and live-wire alerts are now CRITICAL → hop budget 10 instead of 7.**
- Fresh-set result is weak (English + French: shipped path under-triaged 4 of 7, before fixes). Do **not** tune on `fs*`
  or `ho*` cases again, and do not claim a new accuracy number without a new independent set.
- D (crowd crush) never tested on several phones. Honor X8b never tested with Gemma (leave it on rules).
- #10 (`report(raw, triaged)`) still open: once merged, `CrowdRiskMonitor` should pass its own TriageResult (TODO in `AiModule`).

**Exact next steps (in order)**
1. **#45, by 08:00 Sat:** help Seeya proof-read `trust/src/main/assets/data/protocols.json` (9 cards) against the IFRC 2020 PDF.
   Prepare a side-by-side (each card's steps vs the text of its cited page); Seeya judges; post "checked, OK" or
   card id + step + problem on #45. Do not edit TRUST's file.
2. When the lead reopens main (watch #53 / #1): PR the `lane/ai` commits as above, then comment on #4 and one line on #1.
3. Team crowd-crush (D) multi-phone test; on Tier 1 demo phones check `adb logcat -d -s Proxima:* | grep -E "llm:|triage:"`
   shows `loaded in` and `ON_DEVICE_LLM`.
4. 10:00 Sat: team picks from the decision shortlist below → README `## Key decisions` with Diya. 10:30 final README
   check. 12:30 submit.

**Device notes**
- **Demo phones run the lead's build only (#68): never `installDebug` on them.** That debug build already has the probe:
  `adb shell mkdir -p /sdcard/Android/data/mu.proxima.app/files/eval` + `adb shell chmod 777` that folder,
  `adb push ai/eval/out/prompts.jsonl` into it, `adb shell am start -n mu.proxima.app/mu.proxima.ai.debug.LlmProbeActivity --ez eval true --es name gemma`,
  read back with `adb exec-out run-as mu.proxima.app cat <folder>/gemma_raw.jsonl > ai/eval/out/gemma_raw.jsonl`,
  then delete the folder (`run-as mu.proxima.app rm -rf`).
- Never send key events to a teammate's phone without checking `topResumedActivity` first; ask Seeya before any uninstall.
- Gemma file on this laptop: `C:/Users/THIS-PC/Downloads/proxima-models/gemma-4-E2B-it.litertlm` (2,588,147,712 bytes).
  Push steps posted on #68.
- This laptop: Windows Application Control blocks the LiteRT-LM desktop DLL → evals run on phones only.
  Git Bash: prefix adb commands with `MSYS_NO_PATHCONV=1`. Mauritius time = UTC + 4 (Git Bash has no tz data).

**Files touched this session:** `ai/build.gradle.kts`, `ai/src/main/AndroidManifest.xml`, `ai/.../AiModule.kt`,
`ai/.../triage/{LiteRtLmBackend,TriagePrompt,TriageMerge}.kt`, `ai/.../crowd/CrowdRiskModel.kt`, `ai/.../{guide,crowd}/*Contract.kt`
(deleted), `ai/src/debug/.../LlmProbeActivity.kt`, `ai/src/test/...` (backend, prompt, eval, crowd), `ai/eval/*`,
`core/.../pipeline/RuleTriageEngine.kt` (+ test), `core/.../model/Domain.kt` + `engine/Engines.kt` (#49), `docs/ARCHITECTURE.md`,
`README.md`, `docs/lanes/AI.md`.

## Decision shortlist draft (for README `## Key decisions`, team picks at 10:00 Sat)
_Drafted 19:45 Fri from docs/DECISIONS.md, all five lane logs and TRUST's README draft. 10 proposed + 5 on the bench.
TRUST's draft stopped at 18:05; the main change is adding the evening decisions. All times Fri 13 Nov._

| Time | Decision | Why | Source |
|---|---|---|---|
| ~13:05 | Hero user = a parent at Maha Shivaratri (Ganga Talao), not "everyone at events" | A specific Mauritian user with real, measurable network congestion | DECISIONS |
| ~13:10 | Cut the duress PIN, decoy calculator, cyber-warfare branding and LLM chatbot | Too many features, none finished; they did not serve the user | DECISIONS |
| ~13:15 | One `Incident` pipeline for lost / sighting / found / medical / hazard / all-clear | One bus to build, test and explain instead of five subsystems | DECISIONS |
| ~13:25 | The AI **selects** a cited protocol card and never writes medical advice; keyword rules always back it up | No hallucinated instruction can be read aloud, and SOS never depends on a model loading | DECISIONS |
| 15:35 | **Drop the QR wristband**: the finder answers the guardian's alert, location sealed to the guardian | Same privacy with no hardware or printing; one less thing to fail | DECISIONS / UI |
| 15:45 → 18:00 | Ship with **no card** for crowd crush, electrical, branch, flood rather than invent one | IFRC has no crush protocol; a confident wrong card is worse than none | TRUST |
| 16:05 | Tell the whole team (and put it in the README) that simulation-OFF did not yet mean real crypto, instead of fixing it quietly | A privacy claim must be true in the build we submit | TRUST |
| 16:50 | **Reject a blockchain**; keep Ed25519 signatures + HPKE sealing | A public ledger would expose a child's location and needs connectivity, the one thing a crowd doesn't have | TRUST |
| 18:04 | AI pivot: stop chasing Kreol accuracy; build **hands-free CPR guidance** and **crowd-crush early warning** | Kreol can't be made accurate without a model or native reviewer; CPR and crush are where lives are lost at Ganga Talao | AI |
| 18:48 → 19:37 | Gemini Nano unavailable on all our phones → **Gemma 4 E2B on LiteRT-LM**; measured on fixed reports, then added a **severity floor** because the model under-rated severity | Measured, not assumed: S24 Ultra 1.5 s/report; English + French under-triage 9 (rules) → 1 (shipped), with the tuning caveat stated | AI |

**Bench (swap in if the team prefers):**
| Time | Decision | Why | Source |
|---|---|---|---|
| 13:35 | Six Gradle modules, one owner each, fakes behind factories | Five developers in parallel with zero merge conflicts | DECISIONS |
| 15:32 | Keyword rules can **raise** the model's severity but never lower it | Under-triage is the dangerous error | AI |
| 17:15 | Beacons carry only the centre of a 25 m cell; crowd density is anonymous | Density without tracking anyone | MESH |
| 18:50 | Acoustic SOS tones moved to near-ultrasound after the audible version disturbed other teams | Fallback when radio fails, without being a nuisance | MESH |
| 18:39 | README states the RSSI radar fallback is simulation-only | Nearby Connections exposes no RSSI; honest over impressive | LOCATE |

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
