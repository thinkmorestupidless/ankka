# Cross-project access

> Grants — how a project opens one route, gRPC method or topic to a service of another project or a registered machine, made and revoked as data by an owner; machines, their tokens, and the broker exposed to them.

Source: https://docs.ankka.cloud/platform/cross-project-access/
A project is a boundary. A service of one project cannot call another project's routes, and cannot read
or publish to another project's topics, unless the other project grants it. A **grant** names exactly
one grantee and one thing it opens:

| Target | Opens | Enforced by |
|---|---|---|
| `route <service> <METHOD> <path template>` | one HTTP route of one service | the service's runtime, against the caller's certificate |
| `method <service> <Service/Method>` | one gRPC method of one service | the service's runtime, against the caller's certificate |
| `topic <name> consume` | reading one of the project's topics | the installation's broker |
| `topic <name> produce` | publishing to one of the project's topics | the installation's broker |

The grantee is a service of another project, written `service:<project>/<service>`, or a registered
machine, written `machine:<organization>/<name>`, as *Machines* describes. A service of the
granting project needs no grant: its project's own rules already reach it.

## Two decisions, made apart

Whether a route may ever be opened to another project is the route's author's decision, made in reviewed
code. A route whose ACL names `Callers.granted` may be granted; any other route is opened by no grant, and
a grant on it is listed as `route not grantable`. See
[HTTP endpoints](../build/http-endpoints.md#let-another-project-call-a-route) and
[gRPC endpoints](../build/grpc-endpoints.md#access-control).

Who may call it today is a grant, which an owner of the project's organization makes and ends as data.
Neither service is redeployed, and neither restarts. A topic needs no such opt-in in code: the project
that declares a topic decides who else may use it.

## Making a grant

An owner of the granting project's organization makes a grant through the CLI or the control plane:

```bash
ankka projects grants make service:payments/merchant route wallet POST '/v1/wallets/{player}/{currency}/deposits' -p spinvibe
ankka projects grants make service:payments/merchant method wallet WalletService/Deposit -p spinvibe
ankka projects grants make service:affiliates-hub/attribution topic casino.players consume -p spinvibe
```

A route is named by its method and its path template as the endpoint declares it, prefix included. The
parameters' names do not matter: `/v1/wallets/{p}/{c}/deposits` names the same route. A topic must be one
the project declares. A member who is not an owner can list grants and cannot make or end one, and a
deploy token can do neither.

A grant to a service of a project in the same organization is in effect at once. A grant to a service of
another organization is `pending` until an owner of that organization accepts it, and opens nothing until
then.

## How long a grant takes

A grant made or ended reaches the cluster in seconds, and each running instance of the service reads it
within two minutes, with no restart. A revoked grant refuses its caller's next request in that time, and
closes the sockets and server-sent event streams it admitted: a socket is closed with code 1008.

A topic grant is one entry on the grantee's credential on the broker, added when the grant takes effect
and removed when it ends. Until then the broker refuses the grantee's subscription or publication, and the
grantee's runtime retries it.

## The lifecycle

| State | Means | Reached by |
|---|---|---|
| `pending` | offered to another organization's service, opening nothing | making a grant across organizations |
| `accepted` | in effect | making a grant in one organization, or accepting a pending one |
| `declined` | refused by the grantee's organization | an owner of the grantee's organization |
| `withdrawn` | taken back before it was accepted | an owner of the granting organization |
| `revoked` | ended by the granting project | an owner of the granting organization |
| `relinquished` | given up by the grantee | an owner of the grantee's organization |
| `lapsed` | its grantee's project was deleted | the platform |

Every state but `pending` and `accepted` is final. A grant that ended is kept, with who ended it and when,
and a new grant to the same grantee on the same target is a new grant.

## Seeing what is granted

```bash
ankka projects grants list -p spinvibe        # what the project has granted, and whether each is in effect
ankka projects grants received -p payments    # what the project's services hold or are offered
ankka services get wallet -p spinvibe         # "grants  mounted" once the service reads its grants
```

A listing's `EFFECT` column is `in effect` for an accepted grant that opens what it names, else why not:
the state for one that is not accepted, `route not grantable` for a route whose ACL does not name granted
callers, `route not seen` for a route the running service does not have, and `rollout needed` for a
service deployed before it read grants, which reads them once it is next rolled out.

`ankka services get` on a service that reads or publishes to another project's topics lists each with the
right it needs and whether the other project grants it. See
[Reading another project's topic](../build/topics.md#reading-another-projects-topic).

## Machines

A system outside the installation, such as a partner's platform, is a **registered machine**: an
owner registers it on an organization, and it is a grantee as a service is, written
`machine:<organization>/<name>`.

```bash
ankka organizations machines register affiliates network
ankka organizations machines list affiliates
ankka organizations machines byte-rates affiliates network --produce 1MiB --consume 4MiB --request-percentage 50
ankka organizations machines delete affiliates network
```

Registering answers the machine's client id, `machine:affiliates/network`, and its client secret, shown
then and never again: the control plane keeps only its digest. A machine holds no grant until a project
grants it one. A deleted machine's secret is refused from then on, and the name may be registered again
as a new machine, with a new secret and no grant.

### Its token

A machine takes a token from the control plane by OAuth 2.0 client credentials, at `POST /oauth/token`
on the control plane's address, with its client id and secret in the form or as HTTP Basic:

```bash
curl -s https://api.example.com/oauth/token \
  -d grant_type=client_credentials \
  --data-urlencode client_id=machine:affiliates/network \
  --data-urlencode client_secret="\$SECRET"
```

The token lives fifteen minutes, names the machine, and carries no grant: what it may do is decided where
it is presented. A client id is answered at most twelve times a minute by each control plane instance;
the next is `429` with `Retry-After`. The control plane signs tokens with keys it keeps in a Secret of its
own namespace, publishes them at `/.well-known/jwks.json`, and inside the cluster on its `keys` port,
7629, which asks for no client certificate. It replaces the signing key every thirty days and keeps the
previous one long enough for every token it signed to expire.

### Calling a route

A machine calls a granted route through the gateway with `Authorization: Bearer <token>`. The service
verifies the token against the control plane's keys and reads the caller as
`Caller.Machine(organization, name)`, so `Callers.granted` admits it on the routes its grants name. A
request with no token, an expired one or another issuer's is the internet, as it always was. At a route
that authenticates with `Oidc.authenticate()`, a machine is also a principal whose subject is
`machine:<organization>/<name>`. Between services the certificate is the caller, and a token changes
nothing.

### A machine on the broker

An installation that lists the `broker-external` component exposes its broker to machines at
`broker.<base domain>:9094`, through the gateway. A machine reads and publishes the topics a project
grants it with any Apache Kafka client from 3.1 on, configured as:

```properties
bootstrap.servers=broker.example.com:9094
security.protocol=SASL_SSL
sasl.mechanism=OAUTHBEARER
sasl.login.callback.handler.class=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler
sasl.oauthbearer.token.endpoint.url=https://api.example.com/oauth/token
sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required clientId="machine:affiliates/network" clientSecret="<the secret>";
group.id=ankka.machine.affiliates.network.attribution
auto.offset.reset=earliest
```

A topic is named in full, `spinvibe.affiliates.attribution`, and a message's attributes are its `ce-*`
headers. A machine reads under groups named `ankka.machine.<organization>.<name>.` and no other, and no
faster than its byte rates: the installation's defaults unless an owner sets them, never above the
installation's ceiling. The broker refuses a topic no accepted grant names, at the next request after a
grant is revoked. A deleted machine is given no new token, and its connection is ended when its token
must be renewed, within fifteen minutes. An installation without the component lists a machine's topic
grant as `broker not exposed`.

The component patches the broker, the gateway and the control plane, and takes four settings from the
installation's `ankka-platform` ConfigMap: `brokerExternalBootstrap`, `publicIssuer` (the ClusterIssuer
of the listener's certificate), `brokerMaxConnectionsPerIp` (connections each client address may hold to
each broker, 64 by default) and `brokerExternalConnectionRate` (new connections a second on the external
listener, 20 by default). A local platform does not list it; kind publishes port 9094 only on a cluster
created with the `30094 → 9094` mapping in `kustomization/kind.yaml`.

## What a granted caller is

A granted call is delivered at least once, as every call between services is: a caller that sends a
command twice has it handled twice, so a command another project may send must be safe to apply twice.
The callee reads the caller as `Caller.Service(project, name)`, established from the caller's certificate.
A refusal says nothing of which grants exist, and is recorded as refused, never as failed.
