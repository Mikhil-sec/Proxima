# Triage accuracy eval

Scores on-device LLM triage against the keyword rules on fixed emergency reports (`cases.jsonl`),
using the **shipped** prompt, parser and safety net. Results: [`REPORT.md`](REPORT.md).

```bash
# 1. Export the exact prompts the app sends
./gradlew :ai:testDebugUnitTest --tests '*TriageEvalTest*' -PproximaEval=export
# 2. Run the model on the laptop (pip install litert-lm-api==0.19.2, ideally in a venv)
python -I ai/eval/run_llm.py --model <path>/gemma-4-E2B-it.litertlm --name gemma
# 3. Score through TriagePrompt.parse → sanitize → TriageMerge and write REPORT.md
./gradlew :ai:testDebugUnitTest --tests '*TriageEvalTest*' -PproximaEval=score:gemma
```

- `cases.jsonl`: one report per line, with the accepted `types`, `severities` and `subtypes`. Labels were written before any model run.
- `out/` holds the exported prompts and raw model replies. `out/cache/` is gitignored.
- Laptop latency is not phone latency. Measure the phone with the `LlmProbeActivity` (see `docs/lanes/AI.md`).
