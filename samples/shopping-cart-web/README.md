# The shopping cart's interface

The shopping cart sample's user interface, deployed as a **web-hosted service** named `cart-web`. It is
the shape `ankka init --language web` renders, with the backend named `cart`:

- `service.json` mounts the service `cart` at `/api/cart`. The app adds an item and reads the cart under
  that path, at the interface's own address; the cart needs no address of its own and is never exposed.
- `server/server.ts` answers `/summary?cart=<id>` by calling `cart` at `ANKKA_SERVICES_URL` for the
  cart's total. The cart sees `cart-web` as its caller.

## Test

```bash
npm ci
npm run typecheck
npm test
```

## Run beside the cart on this machine

Start the cart from the repository's root, with the Postgres it needs:

```bash
docker compose up -d
sbt shoppingCart/run                 # HTTP on :9000
```

Then run the interface behind the same proxy a cluster runs. The cart announces itself to the local
console under its runtime's name rather than `cart`, so name where it is:

```bash
ankka local web --service cart=http://127.0.0.1:9000 -- npm run dev
```

Open <http://localhost:3000>.

## Deploy

On a local installation (`just up`), the deploy script builds this image and loads it into the cluster.
Deploy the cart first, then the interface, and expose only the interface:

```bash
ankka services apply -f service.json
ankka services get cart-web              # Ready, and its mount of cart: ok
ankka services expose cart-web
```

The cart's endpoint admits every caller, so it would also answer another service; a real
application's backend would admit only the internet and its interface.
