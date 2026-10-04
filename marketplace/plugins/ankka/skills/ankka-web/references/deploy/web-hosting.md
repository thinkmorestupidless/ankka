# Deploy a user interface

> Deploy any program that serves HTTP as a web-hosted service beside your ankka services — mounts that put backends under the interface's address, calls made as the interface, who is admitted, and what a rollout means for a browser.

Source: https://docs.ankka.cloud/deploy/web-hosting/
A user interface is deployed as a **web-hosted service**: `"hosting": "web"` in its descriptor, and an
image that is any program serving HTTP. The platform runs its **proxy** beside that program, the
**process**, in the same pod. The proxy accepts every request from outside, tells the process who sent
it and where it was sent, passes requests under a **mount** to a backend service, and sends the
process's calls to services as the web-hosted service. The process needs no certificate and no library
of ankka's. [The web hosting reference](../reference/web-hosting.md) states every header, variable and
answer exactly.

## When to use web hosting

Use web hosting for a program whose job is to serve a browser: a single-page app and the server that
serves it, a server-rendered site, a backend-for-frontend. Write a service with components when the
program holds state, handles commands or runs workflows: a web-hosted service has no database, no
journal and no components, and nothing it holds survives its instances.

## The descriptor

A web-hosted service's descriptor names its image and its hosting, and usually its mounts:

```json
{
  "name": "shop-web",
  "service": {
    "image": "registry.example.com/acme/shop-web:1.0.0",
    "hosting": "web",
    "processPort": 3000,
    "mounts": [
      { "path": "/api/cart", "service": "cart" },
      { "path": "/api/orders", "service": "orders" }
    ],
    "callers": ["orders"]
  }
}
```

`processPort` is the port the process listens on, told to it as `PORT`; it defaults to `8080`. A
web-hosted service is given no database and declares none. [The descriptor
reference](../reference/service-descriptor.md#web-hosting) lists every rule and refusal.

## Who may send a request

The internet is always admitted, and so is the web-hosted service itself. Any other service is admitted
only when `callers` names it:

| Entry | Admits |
|---|---|
| `"orders"` | the service `orders` of this project |
| `"billing/invoices"` | the service `invoices` of the project `billing` |
| `"*"` | every service of this project |

A service that is not admitted is answered `403` by the proxy, and the process never sees the request.
The process is told who sent each request in `X-Ankka-Caller`: `internet`, or `service
<project>/<service>`. Nothing a request says about itself can change that, because the proxy reads the
caller from the connection's certificate.

## Mounts put backends under the interface's address

A mount passes every request under a path to a service of the same project, with the path removed:
with `/api/cart` mounted to `cart`, a browser's `GET /api/cart/carts/c1` reaches `cart` as `GET
/carts/c1`. The browser talks to one address, so the interface needs no cross-origin requests, and the
mounted service needs no address of its own: it is never exposed.

A request under a mount is the internet's. The mounted service is told the caller `Gateway`, so it
serves the request only if its access rule admits the internet:

```scala
val acl: Acl = Acl.allowCallers(Callers.internet, Callers.service("shop-web"))
```

Only a call the process makes is told to come from the web-hosted service. A backend that admits only
`Callers.service("shop-web")` refuses every request under a mount and answers every call the process
makes. Choose by what the route is for: a mount for what the browser fetches directly, and a call from
the process for what only the server may do. A service that must know which person is asking has to
check that itself, from a token the request carries, whichever way the request arrives.

A mount's identity is honoured only within its project: a service of another project refuses a request
under a mount, and so does a service whose runtime predates web hosting. A web-hosted service mounted
under a sibling is reachable from the internet through that sibling, at the sibling's address, and is
told the address the browser used.

`ankka services get` lists each mount, and beside one with nothing usable behind it says why: `no
service`, `serves no HTTP` or `paused`. A request under a mount whose service cannot be reached is answered `503` by the proxy.

## Calling services from the process

The process calls a service by name at the calling address it is given as `ANKKA_SERVICES_URL`. The
first path segment names the service, as `<service>` in this project or `<service>.<project>` in
another; the rest is the path the service receives. This is the shopping cart's interface reading a
cart's total:

```ts
/** A cart's total, read the way any service is called: by name, at the calling address. */
async function summary(servicesUrl: string, cart: string, res: http.ServerResponse): Promise<void> {
  try {
    const answer = await fetch(`${servicesUrl}/cart/carts/${encodeURIComponent(cart)}/total`);
    send(res, 200, "application/json", JSON.stringify({ status: answer.status, body: await answer.text() }));
  } catch (error) {
    send(res, 502, "application/json", JSON.stringify({ error: `the cart did not answer: ${String(error)}` }));
  }
}
```

The service sees the web-hosted service as its caller. The answer comes back as the service gave it,
whatever its status, and the proxy answers by itself only when it could not send the call: `503` for a
service that cannot be found, `502` for a closed connection, `504` for no answer within 60 seconds.

## Sessions and cookies

A web-hosted service is served at its own hostname, `<service>-<project>.<base domain>`, and its
mounts are under that hostname too, so a cookie the process sets is sent with requests to its mounts.
Keep a session's secrets in a variable taken from a secret:

```json
{
  "name": "shop-web",
  "service": {
    "image": "registry.example.com/acme/shop-web:1.0.0",
    "hosting": "web",
    "env": [{ "name": "SESSION_KEY", "secretKeyRef": { "name": "shop-web-secrets", "key": "session" } }]
  }
}
```

A descriptor may not take a variable from a Secret the platform issues, such as a service's
certificate.

## What a browser sees during a rollout

A rollout replaces instances one at a time and refuses no request, but a browser can load a page from
the old build and then ask for one of its files from the new one. The platform keeps only the image the
descriptor names, and a request may reach any instance. An interface avoids broken pages by naming its
built files by their content, as Vite does under `assets/`, and serving the index page with
`Cache-Control: no-cache`; a page from the old build still finds its files on an old instance until the
rollout ends, and the next load gets the new build.

## Logs

`ankka services logs` reads what the process printed. `--platform` reads what the proxy printed: one line
per answer of its own, and none per request it passed on.

```bash
ankka services logs shop-web
ankka services logs shop-web --platform
```

## Run it on your machine first

`ankka local web` runs the same proxy on your machine, so mounts answer at their paths and the process
calls services by name:

```bash
ankka local web -- npm run dev
```

[Your first interface](../get-started/first-interface.md) goes from nothing written to a deployed
interface.
