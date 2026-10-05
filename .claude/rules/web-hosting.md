---
paths:
  - "proxy/**"
  - "proxy-core/**"
  - "samples/shopping-cart-web/**"
  - "features/web-hosting/**"
  - "cli/src/main/templates/web/**"
---

# Web hosting and the proxy

## A service can be any HTTP program beside the platform's proxy

`"hosting": "web"` (feature 021) runs any image that serves HTTP — a user interface, typically — beside
the platform's **proxy**, two containers in one pod. The proxy is new code and not a mode of the sidecar,
because the sidecar cannot start without forming a cluster and opening a database. Its engine is
`proxy-core`, which depends on nothing of ankka's or Pekko's (the JDK's `HttpServer` and `HttpClient`), so
the CLI's native image carries the same engine for `ankka local web` and a mount means one thing on a
laptop and in a cluster. `proxy` adds mutual TLS from `RotatingTls` and the caller from
`Caller.fromCertificate`, and is the image `ankka-proxy`.

The proxy admits the internet, the service itself and the services the descriptor's `callers` names;
tells the process who sent a request (`X-Ankka-Caller`) and where it was sent (`X-Forwarded-*`, derived
from the hostname, never read from the request); passes a request under a **mount** to a service of the
project under a second certificate, `ankka://<project>/<service>/mount`, which a runtime reads as
`Gateway` only within its own project and a runtime from before the feature refuses outright; and serves
the **calling address**, `127.0.0.1:7630`, at which the process calls `/<service>/…` or
`/<service>.<project>/…` as the web-hosted service. A web-hosted pod has no database (`ProvisioningPlan.NotNeeded`),
no cluster certificate, no peers role, a token it does not mount, and a probe policy of its own. The
operator renders the proxy's settings as `ANKKA_PROXY_*` (`Rendering.ProxyEnv`), and
`ProxyEnvironmentSuite` holds them to `ProxySettings`' parser. `docs/reference/web-hosting.md` is the
contract with the process.

## Traps

- **The JDK's HTTP client reads its restricted-header list once, when its classes load.** The proxy
  sets `Host` on every request it passes on, which needs `-Djdk.httpclient.allowRestrictedHeaders=host`;
  a `System.setProperty` after any client has been built is silently too late, and the header is dropped.
  The proxy image and every forked test of `proxy-core`, `proxy` and `cli` pass it as a JVM option, and
  the CLI's `main` sets it before anything else runs. `ProxyEngine.start` refuses to start without it.
