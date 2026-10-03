# The stand-in process the web hosting cluster features deploy (feature 021). Built by the suite
# under BuildInfo.imageTag, three ways: BUILD=1, BUILD=2, and LISTEN=none for a process that never
# listens. Nothing of the platform's is in it.
FROM node:24-bookworm-slim
ARG BUILD=1
ARG LISTEN=port
ENV BUILD=${BUILD} LISTEN=${LISTEN}
WORKDIR /app
COPY server.mjs .
USER 1000
CMD ["node", "server.mjs"]
