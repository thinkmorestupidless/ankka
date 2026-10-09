# Contract: the broker exposed to machines

Held by `features/cross-project/machine-topics.feature`.

## The component `kustomization/components/broker-external`

| Object | What it does |
|---|---|
| patch on `Kafka ankka` | adds the listener below |
| `Certificate ankka-broker-external` (in `ankka-broker`) | `*.<base>` from `issuerRef: ClusterIssuer PUBLIC_ISSUER`, secret `ankka-broker-external-tls` |
| patch on `Gateway ankka` | listener `broker`: `protocol: TLS`, port 9094, `hostname: "*.<base>"`, `tls.mode: Passthrough`, `allowedRoutes.namespaces.selector: kubernetes.io/metadata.name=ankka-broker`, `kinds: [TLSRoute]` |
| patch on `EnvoyProxy ankka` | the proxy Service gains `tls-9094` → NodePort 30094 (local) / 9094 (LoadBalancer) |
| patch on the operator and the control plane | `ANKKA_BROKER_EXTERNAL_BOOTSTRAP=broker.<base>:9094` |

```yaml
- name: external
  port: 9094
  type: tlsroute
  tls: true
  authentication:
    type: oauth
    jwksEndpointUri: https://ankka-controlplane.ankka-controlplane.svc:7629/.well-known/jwks.json
    validIssuerUri: https://api.<base>          # the control plane's public address; the port when it is not 443
    userNameClaim: broker_user
    checkAudience: true
    clientId: ankka
    maxSecondsWithoutReauthentication: 900
    tlsTrustedCertificates:
      - secretName: ankka-broker-tls             # the service authority's ca.crt, already in the namespace
        certificate: ca.crt
  configuration:
    bootstrap:
      host: broker.<base>
    hostTemplate: broker-{nodeId}.<base>
    advertisedPortTemplate: "9094"
    parentRefs:
      - kind: Gateway
        group: gateway.networking.k8s.io
        name: ankka
        namespace: ankka-gateway
    brokerCertChainAndKey:
      secretName: ankka-broker-external-tls
      certificate: tls.crt
      key: tls.key
  networkPolicyPeers:
    - namespaceSelector:
        matchLabels: {kubernetes.io/metadata.name: envoy-gateway-system}
      podSelector:
        matchLabels:
          gateway.envoyproxy.io/owning-gateway-name: ankka
          gateway.envoyproxy.io/owning-gateway-namespace: ankka-gateway
```

The same patch sets, under the `Kafka`'s `config`, the connection caps FR-025 asks for, from the
installation's `ankka-platform` ConfigMap (`brokerMaxConnectionsPerIp`, default 64, and
`brokerExternalConnectionRate`, default 20 a second), rendered by the overlay's replacements:

```yaml
config:
  max.connections.per.ip: 64                                   # per broker, every listener
  listener.name.external.max.connection.creation.rate: 20      # the external listener alone
```

`BASE_DOMAIN` and `PUBLIC_ISSUER` are replaced by the overlay (`ankka-ca` locally,
`letsencrypt-production` in the cloud). The cloud overlay lists the component; the local one does
not, and the docs say how to add it (kind needs `30094 → 9094`, in `kind.yaml` for new clusters).

## What Strimzi makes

A `Service` and a `TLSRoute` for the bootstrap (`broker.<base>`) and one pair per broker node
(`broker-<n>.<base>`), all in `ankka-broker`, attached to the Gateway's `broker` listener. Envoy
routes each connection by its SNI and never sees inside it. A resized node pool renders its own
routes.

## A partner's client (any Apache Kafka client ≥ 3.1)

```properties
bootstrap.servers=broker.<base>:9094
security.protocol=SASL_SSL
ssl.truststore.certificates=<the installation's public CA, or nothing for a public issuer>
sasl.mechanism=OAUTHBEARER
sasl.login.callback.handler.class=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler
sasl.oauthbearer.token.endpoint.url=https://api.<base>/oauth/token
sasl.oauthbearer.client.credentials.client.id=machine:affiliates/network
sasl.oauthbearer.client.credentials.client.secret=<shown once at registration>
group.id=ankka.machine.affiliates.network.attribution
auto.offset.reset=earliest
```

(Older clients: `sasl.jaas.config=… OAuthBearerLoginModule required clientId="…" clientSecret="…";`.)
Topics are addressed by their full name, `spinvibe.affiliates.attribution`; CloudEvents attributes
are in the record headers (`ce-*`).

## What a machine may do

Exactly its accepted grants' topics; its own group prefix; nothing else. Within its byte rates.
A connection with no token, another issuer's token, or an expired one is refused at
authentication. A token must be renewed within fifteen minutes or the broker ends the connection;
a client that can fetch one carries on without reconnecting. A deleted machine gets no token, so it
is off the broker within fifteen minutes; a revoked grant is refused on the next request after the
ACL changes.

## Installation checks

- `kubectl get tlsroute -n ankka-broker` lists `ankka-kafka-bootstrap` and one route per node, each
  `Accepted` by the `ankka` Gateway.
- `openssl s_client -connect broker.<base>:9094 -servername broker.<base>` shows the public issuer's
  chain.
- From a pod outside `envoy-gateway-system`: a connection to the `external` listener's Service is
  refused by network policy (SC-009).
- `services get` and the machine listing name `broker.<base>:9094`; without the component, a
  machine's topic grant is listed as `broker not exposed`.
