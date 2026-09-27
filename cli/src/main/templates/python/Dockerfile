# Your process and the SDK, nothing else: the platform runs the sidecar beside it in the same pod.
FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir . && rm -rf src
# The port the sidecar dials, on loopback inside the pod.
ENV ANKKA_PROCESS_PORT=9010
CMD ["python", "-m", "{{module}}.main"]
