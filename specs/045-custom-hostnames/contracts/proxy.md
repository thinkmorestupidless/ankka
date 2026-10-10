# Contract: the proxy, and the address a request was sent to

Decisions are in [research.md](../research.md) (R12). `docs/reference/web-hosting.md` is the
contract with the process.

## The rule

| The request's sender | `X-Forwarded-Host` / `Host` the process is told |
|---|---|
| the gateway (certificate URI `ankka://gateway`) | the request's own authority: the hostname the gateway routed, port dropped when it is the public port; `ANKKA_PROXY_PUBLIC_AUTHORITY` only when the request carries no `Host` |
| a mounting proxy (`ankka://<project>/<service>/mount`) | the authority the mounting proxy states, unchanged from today |
| local (`ankka local web`) | unchanged |

`X-Forwarded-Proto` is `https` from the gateway; `X-Forwarded-Port` the public port;
`X-Forwarded-For` kept from the internet; `Forwarded`, `X-Ankka-*` and every forwarded header the
request itself carried are dropped before the proxy sets its own. A request that *says* it was
sent to another address (forged `X-Forwarded-*`, `Forwarded`) is still told the hostname it
arrived at.

## Why the gateway's `Host` is the address

The gateway routes a hostname only to the service whose route names it: the derived hostname or a
custom hostname the resource carries. Envoy chooses the listener by SNI and the virtual host by
`Host`, and a `Host` no route of this service names is a 404 at the gateway. So a request that
reaches the proxy from the gateway carries, as `Host`, a hostname this service holds, and nothing
the proxy could be told would say more. The operator gives the proxy no list, so no pod rolls when
a hostname is added or removed.

## What changes

- `TlsTransport`: `Sender.Internet(stated)` for the gateway's certificate carries the request's
  authority rather than `None`.
- `Headers.inbound`: unchanged in shape; the one `HeadersSuite` assertion that a `Host` from the
  gateway is ignored becomes: it is the address, and a forged `X-Forwarded-Host` still is not.
- `ProxySettings`: unchanged. `ANKKA_PROXY_PUBLIC_AUTHORITY` stays the fallback and the local
  address.

## Mounts

A request under a mount at a custom hostname reaches the mounted service's proxy with the custom
hostname stated by the mounting proxy, as today with the derived one.
