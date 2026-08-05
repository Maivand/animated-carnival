# Kokoro TTS service (CPU). First run downloads the model from Hugging Face.
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends espeak-ng \
    && rm -rf /var/lib/apt/lists/*

# CPU-only torch, then Kokoro.
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir "kokoro>=0.9.4" soundfile numpy

COPY server.py /app/server.py

ENV KOKORO_PORT=8710 KOKORO_VOICE=bm_george KOKORO_LANG=b
EXPOSE 8710
CMD ["python3", "/app/server.py"]
