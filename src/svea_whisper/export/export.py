"""Export a fine-tuned model for the three deployment targets.

- gpu:     CTranslate2 float16  -> serve with faster-whisper on CUDA
- cpu:     CTranslate2 int8     -> faster-whisper on CPU (fastest option)
- android: whisper.cpp GGUF q5_0 -> whisper.cpp JNI on-device (see android/)
"""

from __future__ import annotations

import logging
import subprocess
import sys
import urllib.request
from pathlib import Path

log = logging.getLogger(__name__)

TARGET_QUANT = {"gpu": "float16", "cpu": "int8"}
GGUF_QUANT = "q5_0"
CONVERT_HF_TO_GGML_URL = (
    "https://raw.githubusercontent.com/ggerganov/whisper.cpp/master/models/convert-h5-to-ggml.py"
)


def export_ct2(model_dir: str | Path, out_dir: str | Path, target: str) -> Path:
    """CTranslate2 conversion for gpu (fp16) or cpu (int8)."""
    quant = TARGET_QUANT[target]
    out = Path(out_dir) / f"ct2-{target}-{quant}"
    cmd = [
        sys.executable, "-m", "ctranslate2.converters.transformers",
        "--model", str(model_dir),
        "--output_dir", str(out),
        "--quantization", quant,
        "--copy_files", "tokenizer.json", "preprocessor_config.json",
        "--force",
    ]
    subprocess.run(cmd, check=True)
    log.info("exported %s -> %s", target, out)
    return out


def export_gguf(model_dir: str | Path, out_dir: str | Path,
                whisper_cpp_dir: str | Path | None = None) -> Path:
    """Convert HF Whisper checkpoint to quantized GGUF for whisper.cpp/Android.

    Requires a whisper.cpp checkout for the `quantize` binary; the HF->ggml
    converter script is fetched from the whisper.cpp repo if not present.
    """
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    converter = out_dir / "convert-h5-to-ggml.py"
    if not converter.exists():
        urllib.request.urlretrieve(CONVERT_HF_TO_GGML_URL, converter)

    # The converter needs the openai/whisper repo's mel filters; it accepts the
    # HF model dir, a path to a whisper checkout (only for assets), and out dir.
    whisper_assets = Path(whisper_cpp_dir or out_dir / "whisper.cpp")
    if not whisper_assets.exists():
        subprocess.run(
            ["git", "clone", "--depth", "1",
             "https://github.com/ggerganov/whisper.cpp", str(whisper_assets)],
            check=True,
        )
    subprocess.run(
        [sys.executable, str(converter), str(model_dir), str(whisper_assets), str(out_dir)],
        check=True,
    )
    f32 = next(out_dir.glob("ggml-model*.bin"))

    quantize_bin = whisper_assets / "build" / "bin" / "quantize"
    if not quantize_bin.exists():
        subprocess.run(["cmake", "-B", str(whisper_assets / "build"), str(whisper_assets)],
                       check=True)
        subprocess.run(["cmake", "--build", str(whisper_assets / "build"),
                        "--target", "quantize", "-j"], check=True)
    quantized = out_dir / f"svea-whisper-{GGUF_QUANT}.bin"
    subprocess.run([str(quantize_bin), str(f32), str(quantized), GGUF_QUANT], check=True)
    log.info("exported android -> %s", quantized)
    return quantized


def export_all(model_dir: str | Path, out_dir: str | Path, targets: list) -> dict:
    results = {}
    for target in targets:
        if target in TARGET_QUANT:
            results[target] = str(export_ct2(model_dir, out_dir, target))
        elif target == "android":
            results[target] = str(export_gguf(model_dir, out_dir))
        else:
            raise ValueError(f"unknown target {target!r} (gpu|cpu|android)")
    return results
