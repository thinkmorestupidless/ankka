---
name: ankka-web
description: Build and deploy a user interface for ankka services as a web-hosted service — ankka init --language web, ankka local web, the web hosting descriptor with its mounts, callers and processPort, the PORT and ANKKA_SERVICES_URL the process is given, the headers on every request, calling services by name, and what a mounted service must admit. Use when the task is an interface, a web app, a frontend, a single-page app, a backend-for-frontend, a mount, or a program that only serves HTTP beside ankka services.
---

# Building and deploying a user interface on ankka

A user interface is deployed as a web-hosted service: any program that serves HTTP, run beside the
platform's proxy. The proxy admits the internet and the services the descriptor names, tells the process
who sent each request and where it was sent, passes requests under a mount to a backend service, and
sends the process's calls to services as the web-hosted service.

## Rules

1. **The image only serves HTTP on `PORT`.** It needs no certificate and no ankka library. Never read
   a caller from the request's own headers: `X-Ankka-Caller` is the proxy's, set from the connection's
   certificate, and any copy the request carried is removed.
2. **Call services at `ANKKA_SERVICES_URL`, by name.** `$ANKKA_SERVICES_URL/<service>/<path>` in this
   project, `/<service>.<project>/<path>` in another. Never call a service's in-cluster address directly:
   the process holds no certificate.
3. **A mount is the internet's.** A request under a mount reaches the mounted service as `Gateway`, so
   that service must admit `Callers.internet`. Only a call the process makes arrives as the web-hosted
   service. A service that must know which person is asking checks a token itself.
4. **Mounted services are not exposed.** The browser reaches them at the interface's address. Expose
   only the web-hosted service.
5. **Name built files by their content and serve the index page uncached.** A rollout keeps only the
   descriptor's image, and any instance may answer, so a page from an old build must not ask a new
   instance for a file it no longer has.
6. **Run it locally with `ankka local web -- <command>`.** The same proxy, the same mounts, services
   found as the local console finds them or named with `--service name=url`.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Get started

- `references/get-started/first-interface.md` — Start a user interface from the platform's template, run it on your machine beside a service, and deploy it to a local platform as a web-hosted service with its backend mounted under its own address.

### Build

- `references/build/http-endpoints.md` — Expose a service over HTTP — routes, typed path parameters and bodies, responses, errors, query parameters and headers, access control and server-sent events — in Scala, Python or TypeScript.

### Run and deploy

- `references/deploy/expose.md` — Make a deployed service reachable from outside the cluster at its platform-derived HTTPS hostname, understand why the hostname has the shape it does, and remove the route again.
- `references/deploy/web-hosting.md` — Deploy any program that serves HTTP as a web-hosted service beside your ankka services — mounts that put backends under the interface's address, calls made as the interface, who is admitted, and what a rollout means for a browser.

### Reference

- `references/reference/service-descriptor.md` — Every field of the JSON service descriptor that `ankka services apply` takes, with its type, default and validation rules, and the environment variables the platform reserves.
- `references/reference/web-hosting.md` — Exactly what the platform's proxy gives a web-hosted service's process and asks of it — the environment, the headers on every request, the calling address, mounts, the proxy's own answers, readiness and stopping.
