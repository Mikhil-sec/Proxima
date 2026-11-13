"""Runs a .litertlm model over the exported triage prompts and saves the raw replies.

Mirrors ai/.../triage/LiteRtLmBackend.kt: system instruction, top_k=1, top_p=1.0, temperature=0,
a fresh conversation per report. Scoring is done in Kotlin (TriageEvalTest) with the shipped parser.

    python -I ai/eval/run_llm.py --model <path to .litertlm> --name gemma [--backend cpu|gpu]
"""
import argparse
import json
import pathlib
import time

import litert_lm as lm

HERE = pathlib.Path(__file__).resolve().parent


def text_of(message) -> str:
    data = message.to_json() if hasattr(message, "to_json") else message
    if isinstance(data, str):
        data = json.loads(data)
    content = data.get("content", "")
    if isinstance(content, str):
        return content
    return "".join(part.get("text", "") for part in content if isinstance(part, dict))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--name", default="gemma")
    ap.add_argument("--backend", choices=["cpu", "gpu"], default="cpu")
    args = ap.parse_args()

    prompts = [json.loads(line) for line in (HERE / "out" / "prompts.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    backend = lm.Backend.GPU() if args.backend == "gpu" else lm.Backend.CPU()
    cache = HERE / "out" / "cache"
    cache.mkdir(parents=True, exist_ok=True)

    t0 = time.perf_counter()
    engine = lm.Engine(args.model, backend=backend, cache_dir=str(cache))
    print(f"loaded in {(time.perf_counter() - t0) * 1000:.0f} ms on {args.backend}", flush=True)

    sampler = lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0)
    out_path = HERE / "out" / f"{args.name}_raw.jsonl"
    with out_path.open("w", encoding="utf-8") as out:
        for p in prompts:
            row = {"id": p["id"]}
            t = time.perf_counter()
            try:
                conv = engine.create_conversation(system_message=p["system"], sampler_config=sampler)
                row["raw"] = text_of(conv.send_message(p["user"]))
            except Exception as e:  # keep going: a failed case scores as invalid
                row["error"] = f"{type(e).__name__}: {e}"
            row["ms"] = round((time.perf_counter() - t) * 1000)
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            out.flush()
            print(f'{p["id"]:6} {row["ms"]:6} ms  {(row.get("raw") or row.get("error", ""))[:110]!r}', flush=True)


if __name__ == "__main__":
    main()
