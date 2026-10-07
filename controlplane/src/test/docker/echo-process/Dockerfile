# The stand-in process the web hosting cluster features deploy (feature 021). Built by the suite
# under BuildInfo.imageTag, three ways: BUILD=1, BUILD=2, and LISTEN=none for a process that never
# listens. Nothing of the platform's is in it.
FROM node:24-bookworm-slim
ARG BUILD=1
ARG LISTEN=port
ENV BUILD=${BUILD} LISTEN=${LISTEN}
# curl, so a scenario can keep and read an object from inside the process's container with the
# variables it was given (feature 034), as a developer's program would with its own S3 client.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server.mjs .
USER 1000
CMD ["node", "server.mjs"]
