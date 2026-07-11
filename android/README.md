# SveaWhisper on Android

On-device Swedish transcription via [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
and the `svea-whisper-q5_0.bin` GGUF produced by `svea export --target android`.

## Model choice

| Model | GGUF size (q5_0) | Speed on mid-range phone | Use |
|-------|------------------|--------------------------|-----|
| svea-tiny | ~32 MB | realtime+ | default |
| svea-small | ~180 MB | near-realtime on flagships | quality mode |

## Integration (Kotlin + JNI)

whisper.cpp ships an Android example (`examples/whisper.android`) with a ready
JNI wrapper (`LibWhisper`). Steps:

1. Build whisper.cpp for Android (CMake + NDK, arm64-v8a; enable
   `-DGGML_VULKAN=1` for GPU offload on recent devices).
2. Bundle `svea-whisper-q5_0.bin` in `assets/` (or download on first launch —
   32 MB is fine to ship in-app).
3. Transcribe 16 kHz mono PCM:

```kotlin
class SveaTranscriber(context: Context) {
    private val ctx: WhisperContext = WhisperContext.createContextFromAsset(
        context.assets, "models/svea-whisper-q5_0.bin"
    )

    suspend fun transcribe(pcm16k: FloatArray): String =
        // language is baked to "sv" in the fine-tune; whisper.cpp param anyway:
        ctx.transcribeData(pcm16k, language = "sv")

    fun release() = ctx.release()
}
```

4. Feed it from `AudioRecord` (SAMPLE_RATE 16000, CHANNEL_IN_MONO,
   ENCODING_PCM_FLOAT), chunked at utterance boundaries — whisper.cpp's VAD
   (`--vad` / `params.vad`) or a simple energy gate both work.

## Tips

- Use 4–6 threads (`params.n_threads`); more gives diminishing returns on
  big.LITTLE SoCs.
- Keep segments ≤ 30 s; stream longer audio through the sliding-window API.
- For instant-feel UX, run tiny live and re-transcribe the final utterance
  with small in the background.
