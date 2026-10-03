# Your program and nothing of the platform's: the platform runs its proxy beside it in the same pod,
# and tells it the port to listen on (PORT) and where to call services (ANKKA_SERVICES_URL).
FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build && npm prune --omit=dev

FROM node:24-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=build /app/server ./server
USER 1000
CMD ["node", "server/server.ts"]
