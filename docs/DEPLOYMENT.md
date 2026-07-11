# Deployment

`svea export --model runs/svea-large --target gpu cpu android --out dist/`

## GPU (servers)

CTranslate2 float16, served with faster-whisper:

```python
from faster_whisper import WhisperModel
model = WhisperModel("dist/ct2-gpu-float16", device="cuda", compute_type="float16")
segments, info = model.transcribe("audio.wav", language="sv")
```

kb-whisper-large in CT2 fp16 runs ~8–12× realtime on an A10G.

## CPU (servers/desktop)

CTranslate2 int8 — use the **small** fine-tune for realtime CPU:

```python
model = WhisperModel("dist/ct2-cpu-int8", device="cpu", compute_type="int8",
                     cpu_threads=8)
```

svea-small int8 ≈ 1–2× realtime on 8 modern cores; svea-tiny is ~5× realtime.

## Android

whisper.cpp with the quantized GGUF of the **tiny** (or small, for high-end
devices) fine-tune. `q5_0`-quantized tiny is ~32 MB and transcribes realtime
on mid-range phones. Integration guide + Kotlin example: [`android/`](../android/README.md).
