# The developer's container: only their code and the SDK. The sidecar is the platform's.
# The service runs from source under Node's own type stripping, so there is no build step here:
# `npm run proto` generates the protocol stubs, and `node --conditions=ankka-source` points `ankka`
# at this checkout's sources (a project that installed the package from npm needs neither).
FROM node:24-slim
WORKDIR /app
COPY package.json package-lock.json tsconfig.json tsconfig.build.json buf.gen.yaml .npmrc ./
COPY proto ./proto
COPY scripts ./scripts
RUN npm ci
COPY src ./src
RUN npm run proto
COPY examples ./examples
ENV ANKKA_PROCESS_PORT=9010
CMD ["node", "--conditions=ankka-source", "examples/shopping-cart/main.ts"]
