# Jarvis sandbox runner. Extend this image with whatever toolchains your
# agents should be able to use (node, gradle, gcc, ...) — the base ships
# python3 only.
FROM python:3.12-slim

RUN useradd --create-home --shell /bin/bash sandbox
COPY server.py /app/server.py

USER sandbox
ENV SANDBOX_PORT=8700
EXPOSE 8700

CMD ["python3", "/app/server.py"]
