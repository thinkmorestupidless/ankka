# Web hosting reference

> Exactly what the platform's proxy gives a web-hosted service's process and asks of it — the environment, the headers on every request, the calling address, mounts, the proxy's own answers, readiness and stopping.

Source: https://docs.ankka.cloud/reference/web-hosting/
A web-hosted service is any program that serves HTTP, run beside the platform's **proxy** in the same
pod. This page is the whole of what the proxy gives the program, which the platform calls the
**process**, and what it asks of it. It grows only by addition: a new header or variable never changes
the meaning of an old one.

## What the image must do

The image must serve HTTP/1.1 on the port named by `PORT`, and nothing else. It needs no certificate, no
library of ankka's and no knowledge of the platform. It may listen on every network address: the
network admits no connection to that port from outside the pod.

## Environment

The process is given every variable its descriptor declares, and these two:

| Variable | Value |
|---|---|
| `PORT` | the port to listen on: the descriptor's `processPort`, or `8080` |
| `ANKKA_SERVICES_URL` | where to call other services: `http://127.0.0.1:7630` in a cluster |

A descriptor may not declare either; the platform sets them.

## Every request the process receives

The proxy passes the method, the path, the query, the body and the headers as they arrived, with these
headers set by the proxy, and any copy the request carried removed first:

| Header | Value |
|---|---|
| `X-Ankka-Caller` | `internet`, `service <project>/<service>`, or `local` on a developer's machine |
| `X-Forwarded-Proto` | `https` from the internet or a service; `http` on a developer's machine |
| `X-Forwarded-Host` | the authority the request was addressed to, with its port when it is not the scheme's own. Under another web-hosted service's mount, the authority that service's proxy stated |
| `X-Forwarded-Port` | that port |
| `Host` | the same authority |

The caller is read from the certificate of whoever connected, never from the request, so a request
cannot say that a service sent it. The address is derived from the service's hostname, never from the
request, so a request cannot say it was sent somewhere else. From another service, the address is the
web-hosted service's in-cluster address, `<service>.ankka-<project>.svc.cluster.local:<port>`.

Removed from every request, whoever sent it: any header whose name starts `X-Ankka-`, `Forwarded`, and
the hop-by-hop headers (`Connection`, `Keep-Alive`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`,
`Trailer`, `Transfer-Encoding`, `Upgrade`). `X-Forwarded-For` is passed on from the internet, where its
last entry is the gateway's and the ones before it are whatever the client sent, and removed from a
service's request.

A trace context passes as it arrived, on a request to the process, on a request under a mount and on a
call made at the calling address: `traceparent` and `tracestate` are neither removed nor changed, and
the proxy records no span of its own. A trace crosses a web-hosted service unbroken, and a process that
wants a span in it exports one itself.

When the installation has no base domain, the three `X-Forwarded-` headers are left out of a request
from the internet and `Host` is passed as it arrived.

A request body is passed on as it arrives. A response is delivered as the process writes it, status and
headers as given; the proxy holds none of it back, so a stream of events reaches the browser event by
event.

The proxy does not upgrade a connection. A request asking for one is passed on without the `Upgrade`
and `Connection` headers, and what the process answers is returned. A socket route of a mounted service
is therefore not reached under a mount; a browser opens the socket at the mounted service's own
hostname.

## Requests the process never sees

The process never sees two kinds of request:

- One from a service the descriptor does not admit: the proxy answers `403`. The internet and the
  web-hosted service itself are always admitted.
- One whose path is a mount's, or under it: the proxy passes it to the mounted service.

## Calling another service

The process calls another service at the **calling address**, `ANKKA_SERVICES_URL`. The first path
segment names the service, and the rest, with the query, is the path the service receives:

```text
GET  $ANKKA_SERVICES_URL/cart/carts/c1            # the service cart, in this project
POST $ANKKA_SERVICES_URL/invoices.billing/issue   # the service invoices, in the project billing
```

The method, the headers and the body are sent as the process gave them, except that headers starting
`X-Ankka-` and the hop-by-hop headers are removed and `Host` is the service's. The answer is returned as
the service gave it, whatever its status, a refusal included. No redirect is followed. A call the
service answered is never sent again, and neither is any other call whose connection closed. The one
exception is a `GET` or `HEAD` whose connection closed before any answer began: it is sent once more on
a new connection, because the closed connection may be one the service had already let go idle. The
same holds for a request passed to the process or under a mount.

The service is told the caller `Service(<this project>, <this service>)`, so its access rule can admit
the web-hosted service by name. The proxy also checks that whoever answers holds the identity of the
service asked for, so a call is never sent to another workload. Only the process can use the calling
address: it is bound to the pod's loopback interface.

## A request under a mount

A mount passes every request under its path to a service of the same project, with the path removed.
For a descriptor with `{ "path": "/api/cart", "service": "cart" }`:

```text
GET /api/cart/carts/c1?x=1   →   cart receives   GET /carts/c1?x=1
GET /api/cart                →   cart receives   GET /
GET /api/cartoons            →   the process     (a mount matches whole segments)
```

The headers and body are passed on as for a call, with the `X-Forwarded-*` headers set as the process
would have been given them. The mounted service is told the caller `Gateway`: the internet, whoever sent
the request to the proxy. It serves the request only if its access rule admits the internet. A service
in another project refuses it, and so does a service whose runtime predates web hosting.

## What the proxy answers by itself

Every answer the proxy gives without the process or a service having answered carries
`X-Ankka-Answered-By: proxy` and the body `{"error": "<reason>"}`. An answer without that header came
from the process or from a service.

| Status | When | Reason |
|---|---|---|
| `400` | a call names no service | `a call names a service: <url>/<service>/<path>`, or `'<segment>' does not name a service: …` |
| `403` | the caller is not admitted | `the caller is not admitted: service <project>/<service>` |
| `403` | a request under another project's mount | `a request under a mount of another project` |
| `403` | a certificate that names no caller | `unrecognised caller certificate` |
| `502` | the process or a service closed the connection before answering | `<who> closed the connection before answering` |
| `502` | the service that answered is not the one called | `'<service>' is not the service that answered: …` |
| `503` | the process is not listening | `the process is not listening` |
| `503` | a call names a service that cannot be found | `no service '<service>'`, with `in the project '<project>'` for another project's |
| `503` | a called service is found and not listening | `the service <project>/<service> is not listening` |
| `503` | a mount's service cannot be found or reached | `'<service>' cannot be reached` |
| `504` | the process or a service did not begin to answer in time | `<who> did not answer within 60 seconds` |

The bound of 60 seconds is on the start of an answer. A response that has begun is never cut.

## Readiness

The pod is ready while a connection to `PORT` succeeds, and not otherwise. The proxy answers the
platform's probe on its own port, `7627`, by trying that connection. No route of the process's is
called. When a rollout's deadline passes with the process never listening, the service's status says
`the process is not listening on port <n>` and quotes the kubelet's words.

## Stopping

`SIGTERM` reaches both containers five seconds after the platform starts removing the pod from its
address. The proxy stops accepting, lets requests in flight finish for up to ten seconds, and exits. A
process should do the same.

## Ports in a web-hosted pod

| Port | Who listens | Who reaches it |
|---|---|---|
| the descriptor's `port`, `9000` by default | the proxy, mutual TLS | the gateway, and services of the installation |
| `7627` | the proxy, plain | anyone: it says only whether the pod is ready |
| `processPort`, `8080` by default | the process | the proxy, on the pod's loopback |
| `7630` | the proxy, on loopback | the process |

## On a developer's machine

`ankka local web` gives the same contract with four differences: there is no TLS, `X-Ankka-Caller` is
always `local`, `ANKKA_SERVICES_URL` names a port chosen when it starts, and when it runs the process,
`PORT` is a free port it chose rather than the descriptor's.
