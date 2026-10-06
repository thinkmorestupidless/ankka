# Contract: what the platform around a service sees

## The gateway

Nothing the operator renders changes. An exposed service's socket routes are reached at its
hostname, over the HTTP rule of its one `HTTPRoute`. The handler's caller is the gateway.

Held by a spike before the cluster cases are written (`-Dankka.spikes=on`,
`*GatewaySocketSpike`): frames both ways for a minute, then idle past five minutes, then a frame.
If, and only if, the spike shows the route's own timeout ending an upgraded connection, the HTTP
rule gains `timeouts.request: "0s"`; that changes every exposed service's route and rolls no pod,
and `RenderingGoldenSuite` is updated with it.

## Stopping

On SIGTERM, after the `preStop` sleep: the HTTP server unbinds, every open socket is closed
"going away" (1001), up to two seconds are given to the close handshakes, and the server
terminates as before. A process behind a sidecar is sent `closed` with the reason `going away`.

## Web hosting

A socket route is not reached under a mount. `proxy-core` is unchanged. The limitation is
documented with the way round it: the browser opens the socket at the service's own hostname.

## Variables

`ANKKA_SOCKET_MAX_FRAME_SIZE`, `ANKKA_SOCKET_UNREAD_FRAMES`, `ANKKA_SOCKET_KEEP_ALIVE`. A
descriptor may give them. For a process-hosted service the operator puts them on the platform's
container only; a module that asks for one is told it is not set.

## Listings

| Where | What a socket route looks like |
|---|---|
| the local console's service document | `{"method":"SOCKET","path":"/notices/stream","streaming":true,"endpoint":"endpoint:/notices"}` |
| the local console's page | listed beside the endpoint's routes, marked as a socket route, with no form to call it; the console's invoke routes refuse it as they refuse a gRPC method |
| `ankka services topology` | the endpoint's handler `SOCKET /notices/stream` |
| the console's topology page | the same handler name, from the same wire type |
| `ankka services get` | nothing: it lists no routes |

## Traces and observed calls

One span per socket, component `http`, handler `SOCKET <template>`, recorded at the close with
the socket's whole duration, `ok` or `failed`. A call the handler makes is a child of it and is
counted as an observed call from the endpoint's `SOCKET <prefix><template>` at once, whether or
not the socket has closed.
