# Contract: `GET /services/{projectId}/{name}/topology` and `ankka services topology`

## Route (control plane, `ServiceEndpoint`)

Authorization is identical to `GET /services/{projectId}/{name}/logs`:
- `authz.project(principal, projectId, write = false)`, so a non-member gets **404**, never 403;
- `ServiceEntity.get`, so an unknown service gets **404**;
- a deploy token, being an ordinary member, may read it.

Behaviour:
1. List the service's pods in `<prefix>-<projectId>` by `app.kubernetes.io/name=<name>`, the same
   query `PodLogs` makes.
2. For each pod, concurrently, `GET https://<podIP>:7628/observability/topology` presenting the
   control plane's service certificate. Allow 2 s per instance.
3. Merge (data-model `ServiceTopology`) and answer **200**, including when every instance failed:
   per-instance failures are data, not an error.
4. No running pod (for example, paused): **404** `service '<name>' has no running instance; it may be
   paused`, the same as logs.

Response (`ServiceTopology`, `controlplane-api` `descriptors.scala`):

```json
{
  "service": "cart",
  "running": 2, "contributing": 1, "partial": true,
  "instances": [
    { "pod": "cart-7d9f-abc", "status": "ok", "runtime": "0.10.0",
      "readAt": "2026-10-01T10:00:02Z" },
    { "pod": "cart-7d9f-def", "status": "unsupported",
      "problem": "this instance's runtime (0.9.2) serves no topology" }
  ],
  "window":   { "seconds": 600, "since": "…", "calls": 14 },
  "nodes":    [ … ],
  "declared": [ … ],
  "calls":    [ … ],
  "differences": [
    { "node": "carts-by-customer", "presentOn": ["cart-7d9f-abc"] }
  ]
}
```

`nodes`, `declared` and `calls` have the shape given in `topology.md`, except that `histogram` is
dropped after merging. `status` takes one of four values:

| status | meaning |
|---|---|
| `ok` | the instance answered |
| `unreachable` | it did not answer in time |
| `unsupported` | the connection was refused |
| `failed` | it answered with an error, or with something that is not a topology |

Required alongside the route:
- **Docs.** A hand-written section in `docs/reference/control-plane-api.md`, and its generated block
  refreshed by `ControlPlaneRoutesReferenceSuite`.
- **Fixtures.** Full and minimal fixtures for `ServiceTopology` and `InstanceTopology` from
  `ControlPlaneFixturesSuite`, and zod schemas registered in `schemasByType`.
- **Fake control plane.** The route in `fake-control-plane.ts`, and a Playwright test, because
  `console/e2e/parity.ts` fails on a route that no test visits.

## CLI

```text
ankka services topology <name> -p <project> [--json]
```

- `--json` prints the response as it is.
- Without it, the CLI prints nodes grouped by layer, then the declared edges, then the observed calls
  with their counts. It closes with a line stating the window and that calls are observed, not
  complete. A partial result names the instances that did not contribute, and the command still
  exits 0.
- A non-member, an unknown service, or a service with no running instance exits 1 with the control
  plane's message, as `services logs` does.
- `CliReferenceSuite` regenerates `docs/reference/cli.md`.
