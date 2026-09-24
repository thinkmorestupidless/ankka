# The developer's container: only their code and the SDK. The sidecar is the platform's.
# Plain pip, so the build needs nothing beyond Docker Hub's python image.
FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
COPY proto ./proto
RUN pip install --no-cache-dir . && rm -rf src proto
COPY examples ./examples
ENV ANKKA_PROCESS_PORT=9010
CMD ["python", "-m", "examples.shopping_cart.main"]
