# Quickstart: proving the feature works

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/](./contracts/)

Five tiers, cheapest first. Each tier is a gate for the next; none depends on a browser except
the one that says so.

## Tier 1 — the verifier and the HTTP module (seconds, no Docker)

```bash
sbt 'controlPlane/testOnly *TokenVerifierSuite'
sbt 'http/testOnly *AclSuite'          # Authenticate: 401 with WWW-Authenticate, 403, 503, principal on the thread
```

Expected: every rejection reason in contracts/http-api.md answers as tabled; a token signed with
`none` or HMAC is refused before any key lookup; an unknown `kid` causes exactly one refetch.

## Tier 2 — the authorization matrix (about a minute, Docker for Postgres)

```bash
sbt 'controlPlane/testOnly *ControlPlaneHttpSuite *AuthorizationMatrixSuite *SuspensionSuite'
```

Expected, from the spec's stories:

- P1: every route 401 without a token; the health route still 200 (S1.1, S1.2, S1.7).
- P2: Bob sees nothing of `acme`; direct reads 404; invitation claimed on his next listing and on
  a refused write; removal refused on the next request (S2.1–S2.8).
- P3: every members command; last owner refused; platform admin actions recorded administrative;
  disable stops the running service and not the paused one; writes 409 while disabled; enable
  restores exactly the running one (S3.1–S3.12).
- P5: history names actors; pre-feature events (replayed from a journal fixture) show none.
- The sweep step: an apply whose row is withheld from the view until after the disable is
  suspended by the next sweep.

## Tier 3 — the CLI's login and the real Keycloak (about two minutes)

```bash
sbt 'cli/testOnly *DeviceFlowSuite *CredentialsSuite'
sbt 'controlPlane/testOnly *KeycloakRealmSuite'
```

Expected: `login` completes against the scripted server honouring `interval`, `slow_down` and
expiry; `credentials.json` is `0600` and keyed by URL; `--token` bypasses it. Against the real
image with the shipped `realm.json`: tokens carry `aud: ankka-controlplane`, `email_verified`,
`realm_access.roles`; the verifier accepts them; a service account with a verified email claims
an invitation (P4).

## Tier 4 — the local installation (ten minutes, kind)

```bash
kind create cluster --name ankka --config kustomization/kind.yaml
./kustomization/deploy-local.sh          # or: just up
```

Expected output ends with the smoke test passing on a *client-credentials* login and the lines:

```
ankka config set url https://api.127.0.0.1.sslip.io:8443
ankka config set ca ~/.ankka/local-ca.crt
ankka login   # user dev, password dev
```

Then, by hand (the one browser step):

```bash
ankka login                      # open the printed URL, enter the code, sign in as dev/dev
ankka whoami                     # platform admin: yes
ankka organizations create acme --name "Acme Corp"
ankka organizations members list acme          # dev is owner
ankka projects create checkout --name Checkout -O acme
ankka config set project checkout
echo '{"name":"cart","service":{"image":"sample-shopping-cart:latest"}}' > cart.json
ankka services apply -f cart.json && ankka services list      # Ready 1/1
ankka services history cart                                     # apply, by dev@…
ankka organizations disable acme && ankka services list         # Suspended 0/0
ankka services resume cart                                      # error: organization 'acme' is disabled
ankka organizations enable acme && ankka services list          # Ready 1/1 again
ankka logout && ankka services list                             # error: not logged in … run 'ankka login'
curl -sk -o /dev/null -w '%{http_code}\n' https://api.127.0.0.1.sslip.io:8443/organizations   # 401
```

A second user: create `bob` in Keycloak's console at
`https://auth.127.0.0.1.sslip.io:8443/admin/` (admin/admin), with a verified email; in a second
config (`ANKKA_CONFIG=/tmp/bob.json ankka login`) confirm `organizations list` is empty, invite
`bob`'s email as dev, and confirm the next `organizations list` as bob shows `acme` as member.

## Tier 5 — the cluster suites and the overlays (an hour, `caffeinate`)

```bash
caffeinate -i sbt 'controlPlane/testOnly *EndToEndClusterSuite *RemoteOverlaySuite'
```

Expected: the Keycloak component comes up in k3s (operator, CNPG cluster, instance `Ready`, realm
imported); a login through the gateway with a suite-created client; SC-001 — every route 401
through the real external address; the remote overlay renders with no admin secret and no dev
user, both overlays route `auth.<base>`, and the issuer replacement lands in the control plane's
environment.

## The benchmark (SC-005)

```bash
sbt 'controlPlane/testOnly *VerificationOverheadBenchmark'
```

One real request — HTTP in, entity, journal, reply — with and without `Acl.Authenticate`, same
harness, reported as a ratio. The gate is under 1%; the number from feature 007's identical
harness was 640µs per request, so verification must cost under about 6µs after the first key
fetch, which is the cost of one RSA signature check.

## Reviewer's checklist

- [ ] `grep -rn 'ANKKA_CONTROLPLANE_TOKEN\|auth.token\|ControlPlaneAcl.bearer'` returns only
      `CLAUDE.md`'s history and nothing in code, manifests or README.
- [ ] `git grep -n 'dev-local-token'` returns nothing.
- [ ] The control plane's ClusterRole diff is empty.
- [ ] No `KeycloakRealmImport` is checked in under `kustomization/` — it is generated.
- [ ] `realm.json` has `"users"` absent or empty.
- [ ] The remote overlay's rendered output contains no `Secret` named `ankka-keycloak-admin`.
- [ ] `modules/http`'s dependency list is unchanged; `cli`'s is unchanged; `controlplane` adds
      exactly `nimbus-jose-jwt`.
- [ ] Every new `Option` field on an event has a default and a replay test from a pre-feature
      journal fixture.
- [ ] `services history` on a service created before the feature shows `-` for `BY`.
- [ ] Research "verify at implementation" items 1–7 are each ticked in the task that settles them.
