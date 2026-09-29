# The installation's console: the host built against the package, run by Node with no build tools.
#
#   docker build -t ankka-console:latest console
#
# The build context is console/, the workspace; the browser suite is not in the image.

FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
COPY package/package.json package/
COPY host/package.json host/
COPY e2e/package.json e2e/
RUN npm ci --no-audit --no-fund
COPY package package
COPY host host
RUN npm run build -w package && npm run build -w host \
 && npm prune --omit=dev --no-audit --no-fund

FROM node:24-bookworm-slim
LABEL org.opencontainers.image.source="https://github.com/thinkmorestupidless/ankka"
LABEL org.opencontainers.image.description="The ankka console: a web console for a deployed installation."
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules node_modules
COPY --from=build /app/package/package.json package/
COPY --from=build /app/package/dist package/dist
COPY --from=build /app/host/package.json /app/host/server.ts host/
COPY --from=build /app/host/build host/build
USER 1000
WORKDIR /app/host
EXPOSE 9000 7627
CMD ["node", "server.ts"]
