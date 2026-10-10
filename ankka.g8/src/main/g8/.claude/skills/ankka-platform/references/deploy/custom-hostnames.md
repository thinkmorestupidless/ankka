# Serve a service at your own domain

> Add a custom hostname such as app.example.com to an exposed service, prove the project controls the name with a TXT record, point the name at the installation, read where each hostname stands, and remove one again.

Source: https://docs.ankka.cloud/deploy/custom-hostnames/
An exposed service answers at the hostname the platform derives, `<service>-<project>.<base domain>`. A
**custom hostname** is a name under a domain you own, such as `app.example.com`, that the same service
answers at as well. The platform obtains a certificate for it and serves it exactly as it serves the
derived hostname. It manages no DNS: you create two records at your DNS provider, and the platform reads
one of them once.

```bash
ankka services expose cart
ankka services hostnames add cart app.example.com
```

The first `add` is refused until the name carries the project's proof record, and the refusal says the
record to create. Once that record exists, the hostname is recorded:

```text
added 'app.example.com' to service 'cart'
create CNAME app.example.com → cart-checkout.example.net
keep the proof record: it is not read again, but it is how the claim was made
```

Create that second record too. Within a few minutes the service answers at `https://app.example.com` with
a certificate a browser trusts, and it still answers at its derived hostname.

## The two records

**The proof record says the project may use the name.** It is a `TXT` record beside the name:

```text
_ankka.app.example.com.  TXT  "ankka-project=checkout"
```

The value is the project's id, the project the service is in. Nothing secret is needed: only whoever
controls the domain's DNS can create the record, and that is the whole proof. One value serves every
hostname the project brings; `ankka services get` shows it as `proof record`. The control plane reads the
record once, when the hostname is added, and never again. Removing it later changes nothing.

The record sits at `_ankka.<hostname>` rather than at the name itself because the name is usually a
`CNAME`, and a name that is a `CNAME` can hold no other record.

**The record to create points the name at the installation.** For a name with three labels or more it
is a `CNAME` to the service's derived hostname. An apex, a name of two labels such as `example.com`,
cannot be a `CNAME`; it is an `A` record to the gateway's address, when the installation has published one
(`ANKKA_GATEWAY_ADDRESS`). When it has not, `services get` says so instead of giving a record.

## Where a hostname stands

```bash
ankka services get cart
```

```text
hostname          https://cart-checkout.example.net
custom hostnames  app.example.com  serving
                    create CNAME app.example.com → cart-checkout.example.net
                  shop.example.com  pending: waiting for the certificate: Waiting for HTTP-01 challenge propagation: failed to perform self check GET request 'http://shop.example.com/.well-known/acme-challenge/…': … no such host
                    create CNAME shop.example.com → cart-checkout.example.net
proof record      TXT _ankka.<hostname> "ankka-project=checkout"
```

Each hostname is in one of three states:

| State | Means |
|---|---|
| `pending` | Not served yet. The reason is the certificate authority's or the gateway's: a name that does not resolve to the installation yet, a certificate being issued, the gateway attaching the name. A hostname whose record you never create stays pending; nothing times out. |
| `serving` | Answering with its own certificate. A renewal the authority refused is shown beside it, and it keeps serving until the certificate expires. |
| `rejected` | The gateway or the platform will not serve it, with the reason. |

The platform never decides for itself whether a name resolves. What it shows for a pending hostname is
what the certificate authority said when it tried to reach the name. An authority that validated a name
for this installation recently may issue for it again without checking, so a hostname removed and added
back can be served before its record points here again. The listing's `HOSTNAME` column
shows the custom hostnames after the derived one, with a `!` after any that is not serving.

## The rules

- **The service must be exposed.** A custom hostname is part of exposure. Unexposing the service stops
  every hostname it has and keeps them recorded; exposing it again serves them again without adding
  them again.
- **One service holds a name.** Adding a name another service of the installation holds is refused,
  naming the holder.
- **The installation's own names are not yours.** The base domain and every name under it are refused:
  they are the platform's, covered by its wildcard certificate and derived for services.
- **A name alone.** No scheme, path, port or wildcard. The name is lowercased.
- **Five per service.** A sixth is refused, naming the limit. Removing one makes room.
- **A descriptor says nothing about hostnames.** Applying a descriptor never adds or removes one.

## Remove a hostname

```bash
ankka services hostnames remove cart app.example.com
```

Nothing answers at the name within seconds. The derived hostname and the service's instances are as they
were: nothing restarts. The certificate is discarded and any service may claim the name. Deleting the
service frees its hostnames the same way.

A platform administrator can remove a hostname from any service, for a domain that changed hands after
its project proved it. The service's history records that as `hostname taken away`, distinct from a
member's `hostname removed`.

## On a local platform

The local installation signs a custom hostname's certificate with its own authority, which needs no
challenge, so a name is served as soon as it is added. The proof record is still required, so this
needs a domain you control: create `_ankka.app.<your domain> TXT "ankka-project=<project>"` at your
provider, and point the name at your machine with a hosts entry:

```text
127.0.0.1  app.<your domain>
```

Then add the hostname and reach it on the local HTTPS port, verifying against the local authority:

```bash
ankka services hostnames add cart app.<your domain>
curl --cacert ~/.ankka/local-ca.crt https://app.<your domain>:8443/carts/c1
```

## What the platform does not do

It writes no DNS record and runs no DNS zone. It does not accept a certificate you supply. It gives no
custom hostname to the control plane, the console, the identity provider or the object store. It does not
share one hostname between two services by path. See [Limitations](../reference/limitations.md).
