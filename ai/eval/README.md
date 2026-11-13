# Triage accuracy eval

Scores on-device LLM triage against the keyword rules on fixed emergency reports (`cases.jsonl`),
using the **shipped** prompt, parser and safety net. Results: [`REPORT.md`](REPORT.md).

```bash
# 1. Export the exact prompts the app sends
./gradlew :ai:testDebugUnitTest --tests '*TriageEvalTest*' -PproximaEval=export
# 2a. On a phone (recommended: real latency + RAM, through the shipped LiteRtLmBackend)
adb push ai/eval/out/prompts.jsonl /sdcard/Android/data/mu.proxima.app/files/eval/prompts.jsonl
adb shell am start -n mu.proxima.app/mu.proxima.ai.debug.LlmProbeActivity --ez eval true --es name gemma
adb logcat -d -s Proxima:* | grep -E "EVAL|llm:|MEM"     # wait for "EVAL done"
adb pull /sdcard/Android/data/mu.proxima.app/files/eval/gemma_raw.jsonl ai/eval/out/
# 2b. Or on a desktop (pip install litert-lm-api==0.19.2; blocked by Application Control on our Windows laptop)
python -I ai/eval/run_llm.py --model <path>/gemma-4-E2B-it.litertlm --name gemma
# 3. Score through TriagePrompt.parse → sanitize → TriageMerge and write REPORT.md
./gradlew :ai:testDebugUnitTest --tests '*TriageEvalTest*' "-PproximaEval=score:gemma@Galaxy S26+ GPU"
```

- `cases.jsonl`: one report per line, with the accepted `types`, `severities` and `subtypes`. Labels were written before any model run.
- `out/` holds the exported prompts and raw model replies. `out/cache/` is gitignored.
- The scorer applies the app's 6 s triage budget: a valid but late reply counts as a rules answer on the shipped path.
