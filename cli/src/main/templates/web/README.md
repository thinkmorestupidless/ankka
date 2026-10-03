# {{name}}

A user interface for [ankka](https://docs.ankka.cloud/) services, deployed as a **web-hosted service**:
a React app built by Vite, and a small server on `node:http` that serves it and answers one route of
its own. The platform runs its proxy beside your process in the same pod. Requests under the
descriptor's mounts go to the mounted services; everything else comes to your server, which calls
services by name at the address the platform gives it.

Needs: Node.js 24, and the `ankka` CLI.

## How it fits together

- `service.json` mounts the service `backend` at `/api`: a browser's request for `/api/carts/c1` is
  passed to `backend` as `/carts/c1`, at this interface's own address. Change `backend` to the name of
  your service, or add a mount per service.
- `server/server.ts` listens on `PORT`. `/summary` calls `backend` at `ANKKA_SERVICES_URL` and answers
  what it got; the service sees this interface as its caller, so its access rule can admit it and
  nothing else.
- `src/` is the app. It reads `/api/` (the mount) and `/summary` (the server) and shows both.

## Test

```bash
npm install              # writes package-lock.json: commit it, CI installs from it with `npm ci`
npm run typecheck
npm test
```

## Run locally

Start `backend` on this machine (any ankka service run locally announces itself to the local console),
then run the interface behind the same proxy a cluster runs:

```bash
ankka local web -- npm run dev
```

It listens on port 3000 and starts `npm run dev` with `PORT` and `ANKKA_SERVICES_URL` set. Open
<http://localhost:3000>. A service that is not running under the local console's eye can be named:

```bash
ankka local web --service backend=http://127.0.0.1:9000 -- npm run dev
```

## Work with a coding agent

This project carries ankka's documentation for the version it was made with, as Agent Skills in
`.claude/skills/`, and a `.mcp.json` that starts `ankka mcp`. Claude Code reads both with no
configuration; it asks once before starting the project's MCP server. The `ankka` CLI must be on your
`PATH`.

## Build the image

```bash
npm install && docker build -t {{name}}:latest .
```

The image holds only your server and the built app.

## Deploy to an ankka platform

```bash
kind load docker-image {{name}}:latest --name ankka   # a local kind cluster; push to a registry otherwise
ankka services apply -f service.json
ankka services get {{name}}                           # Ready; its mounts, and what is behind each
ankka services expose {{name}}
```

The mounted services need not be exposed: the browser reaches them at this interface's address.

## Deploy from GitHub

`.github/workflows/ci.yml` type-checks, tests and builds on every push. `.github/workflows/deploy.yml`
builds the image, pushes it to this repository's GitHub Container Registry and deploys, on a version
tag or by hand, once the repository has the secrets `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT` and,
for a platform whose certificate is not publicly trusted, `ANKKA_CA`. Until then it declines to run,
so the first push is green.
