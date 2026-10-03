---
name: ankka-web
description: Build and deploy a user interface for ankka services as a web-hosted service — ankka init --language web, ankka local web, the web hosting descriptor with its mounts, callers and processPort, the PORT and ANKKA_SERVICES_URL the process is given, the headers on every request, calling services by name, and what a mounted service must admit. Use when the task is an interface, a web app, a frontend, a single-page app, a backend-for-frontend, a mount, or a program that only serves HTTP beside ankka services.
pages:
  - get-started/first-interface.md
  - deploy/web-hosting.md
  - reference/web-hosting.md
  - reference/service-descriptor.md
  - deploy/expose.md
  - build/http-endpoints.md
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
