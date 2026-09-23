# The developer's container: only their code and the SDK. The sidecar is the platform's.
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
COPY proto ./proto
COPY examples ./examples
RUN uv sync --no-dev --no-editable --extra testkit 2>/dev/null || uv sync --no-dev
ENV PATH="/app/.venv/bin:$PATH"
ENV ANKKA_PROCESS_PORT=9010
CMD ["python", "-m", "examples.shopping_cart.main"]
