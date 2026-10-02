# Contract: what the proxy gives a process, and asks of it

This is the developer-facing contract of web hosting. It is small, it has no version, and it grows
only by addition: a new header or variable never changes the meaning of an old one. It becomes
`docs/reference/web-hosting.md`.

## What the image must do

Serve HTTP/1.1 on the port named by `PORT`. Nothing else. It needs no certificate, no library of
ankka's and no knowledge of the platform. It may listen on every network address: the network admits no
connection to that port from outside the pod.

## Environment

| Variable | Value |
|---|---|
| `PORT` | the port to listen on: the descriptor's `processPort`, or 8080 |
| `ANKKA_SERVICES_URL` | where to call other services: `http://127.0.0.1:7630` in a cluster |

and every variable the descriptor declares.

## Every request the process receives

The proxy passes the method, the path, the query, the body and the headers as they arrived, with
these headers set by the proxy and any copy the request carried removed first:

| Header | Value |
|---|---|
| `X-Ankka-Caller` | `internet`, `service <project>/<service>`, or `local` on a developer's machine |
| `X-Forwarded-Proto` | `https` from the internet or a service; `http` locally |
| `X-Forwarded-Host` | the authority the request was addressed to, with its port when it is not the scheme's own. Under another web-hosted service's mount, the authority that service's proxy stated |
| `X-Forwarded-Port` | that port |
| `Host` | the same authority |

Removed from every request, whoever sent it: any header whose name starts `X-Ankka-`, and
`Forwarded`. `X-Forwarded-For` is passed on from the gateway and removed from a service's request;
only its last entry was written by the gateway, and the ones before it are whatever the client
sent.

When the installation has no base domain, the three `X-Forwarded-` headers above are left out and
`Host` is passed as it arrived.

A request body is passed on as it arrives. A response is delivered as the process writes it; the
proxy holds none of it back, so a stream of events reaches the browser event by event.

The proxy does not upgrade a connection. A request asking for one is passed on without the
`Upgrade` and `Connection` headers, and what the process answers is returned.

## Requests the process never sees

- One from a caller the descriptor does not admit: the proxy answers 403. The internet and the
  service itself are always admitted.
- One whose path is a mount's, or under it: the proxy passes it to the mounted service.

## Calling another service

```text
GET  $ANKKA_SERVICES_URL/cart/carts/c1            # the service cart, in this project
POST $ANKKA_SERVICES_URL/invoices.billing/issue   # the service invoices, in the project billing
```

The first path segment names the service; the rest, with the query, is the path the service
receives. The method, the headers and the body are sent as given, except that headers starting
`X-Ankka-` and the hop-by-hop headers are removed and `Host` is the service's. The answer is
returned as the service gave it, whatever its status. There are no retries and no redirects are
followed.

The service sees the caller `Service(<this project>, <this service>)`.

## A request under a mount

For a descriptor with `{ "path": "/api/cart", "service": "cart" }`:

```text
GET /api/cart/carts/c1?x=1   →   cart receives   GET /carts/c1?x=1
GET /api/cart                →   cart receives   GET /
GET /api/cartoons            →   the process     (a mount matches whole segments)
```

The headers and body are passed on as for a call, with the `X-Forwarded-*` headers above set. The
service sees the caller `Gateway`: the internet, whoever sent the request to the proxy. It serves
the request only if its access rule admits the internet. A service in another project refuses it.

## What the proxy answers by itself

Each carries `X-Ankka-Answered-By: proxy` and the body `{"error": "<reason>"}`.

| Status | Reason |
|---|---|
| 400 | a call names no service |
| 403 | the caller is not admitted |
| 502 | the process or a service closed the connection before answering; a service's certificate is not the service asked for |
| 503 | the process is not listening; a service cannot be found |
| 504 | the process, or a service, did not begin to answer within 60 seconds |

An answer without that header came from the process or from a service.

## Readiness

The proxy answers the platform's probe. The pod is ready while a connection to `PORT` succeeds,
and not otherwise. No route of the process's is called.

## Stopping

`SIGTERM` reaches both containers five seconds after the platform starts removing the pod from
its address. The proxy stops accepting, lets requests in flight finish for up to ten seconds, and
exits. A process should do the same.

## On a developer's machine

`ankka local web` gives the same contract with four differences: there is no TLS,
`X-Ankka-Caller` is always `local`, `ANKKA_SERVICES_URL` names a port chosen when it starts, and
when it runs the process `PORT` is a free port it chose, not the descriptor's.
