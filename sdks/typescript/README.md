# ankka for TypeScript

The ankka SDK for services written in TypeScript on Node.js. A service is a process that the ankka
runtime hosts as a sidecar: the sidecar owns everything durable and distributed, and the process owns
the decisions. This package speaks the sidecar protocol so your code never does.

Documentation: <https://docs.ankka.cloud/> — start with *Your first service in TypeScript* and the
*TypeScript SDK* reference.

```bash
npm install ankka                  # the SDK
npm install -D testcontainers @testcontainers/postgresql   # only if you use the integration testkit
```

Node 22.22 or later. A service runs from source: `node main.ts`.

## Developing the SDK

```bash
npm ci
npm run proto                      # copy protocol/ in, generate src/_proto, write src/version.ts
npm run typecheck                  # tsc --noEmit over src, test, examples
npm test                           # the fast tests, no Docker
npm run test:slow                  # the Docker-backed tests (needs the ankka-sidecar image)
npm run conformance                # the platform's conformance suite against this SDK's reference service
npm run build                      # emit dist/, what the package ships
```

`proto/` is a verbatim copy of the repository's `protocol/`; CI fails when the two differ, so a
protocol change reaches this SDK deliberately by running `npm run proto`.
