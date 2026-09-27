# Your process and the SDK, nothing else: the platform runs the sidecar beside it in the same pod.
# Node runs the TypeScript sources directly, so there is no build step.
FROM node:24-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
# The port the sidecar dials, on loopback inside the pod.
ENV ANKKA_PROCESS_PORT=9010
CMD ["node", "src/main.ts"]
