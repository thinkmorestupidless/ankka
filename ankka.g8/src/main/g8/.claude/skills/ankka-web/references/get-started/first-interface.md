# Your first interface

> Start a user interface from the platform's template, run it on your machine beside a service, and deploy it to a local platform as a web-hosted service with its backend mounted under its own address.

Source: https://docs.ankka.cloud/get-started/first-interface/
This tutorial starts a user interface from the platform's template, runs it on your machine beside a
backend service, and deploys it to a local platform. You need Node.js 24, the `ankka` CLI, and for the
last part a local platform ([Deploy to a local platform](deploy-locally.md) installs one).

## Start from the template

`ankka init --language web` writes a project: a React app built by Vite, a small server that serves it,
and a descriptor that makes it a web-hosted service with the service `backend` mounted at `/api`.

```bash
ankka init shop-web --language web
cd shop-web
npm install
npm run typecheck
npm test
```

```text
ℹ tests 8
ℹ pass 8
ℹ fail 0
```

The project's `service.json` says how the platform runs it:

```json
{
  "name": "shop-web",
  "service": {
    "image": "shop-web:latest",
    "hosting": "web",
    "mounts": [{ "path": "/api", "service": "backend" }]
  }
}
```

## Run it beside a service

Run any ankka service on your machine and name it `backend` for the interface, then run the interface
behind the same proxy a cluster runs:

```bash
ankka local web --service backend=http://127.0.0.1:9000 -- npm run dev
```

```text
mount  /api → backend
serving shop-web at http://127.0.0.1:3000
PORT=…
ANKKA_SERVICES_URL=http://127.0.0.1:…
```

Open <http://localhost:3000>. The page shows two things: what `backend` answered a request under the
mount, `/api/`, and what it answered the server's own call at the calling address, through `/summary`.
A request under `/api` never reaches your server: the proxy passes it to `backend` with `/api` removed.

## Deploy it

Build the image, load it into the local platform's cluster, and apply the descriptor:

```bash
npm run build
docker build -t shop-web:latest .
kind load docker-image shop-web:latest --name ankka
ankka services apply -f service.json
ankka services get shop-web
```

Among the lines it prints:

```text
status      Ready
hosting     web
database    none
callers     the internet
mounts      /api  → backend    (no service)
```

The mount says `no service` until a service named `backend` is deployed in the project; a request under
it is answered `503` until then. Expose the interface and open it:

```bash
ankka services expose shop-web
```

The hostname `services expose` prints serves the interface. Deploy your backend as `backend`, or change
the mount in `service.json` to the name of a service you have, and apply it again: a mount changes
without a new image.
