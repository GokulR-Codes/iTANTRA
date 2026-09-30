# training/

Fine-tuning, distillation, and format-conversion scripts (e.g. HF checkpoint
-> ONNX -> quantized .ort/.tflite) for whichever STT/TTS/LID models get
adopted per a Model Scorecard (see tools/model_scorecard/). **Empty in
Gate 1** — no model has been selected yet (§18's candidate matrices are
still open), so there is nothing to fine-tune or convert.

## Layout (once populated)
- `scripts/` — one script per pipeline stage (data prep, train/fine-tune,
  export, quantize, verify-parity)
- `configs/` — one YAML/JSON config per run, named and dated so a training
  run is reproducible from this directory alone

Every script that touches an actual training or conversion run must write
its provenance (base checkpoint, dataset version, commit hash, date) into a
run manifest under `datasets/manifests/` or `docs/BENCHMARKS.md` — a model
artifact with no recorded provenance cannot be adopted (§18, §40).
