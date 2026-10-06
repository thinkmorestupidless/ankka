# Contract: the console

All in `console/package`. No route is added to the control plane for the console, and the console
calls the two new routes as the member.

## The client and its schemas

`src/client/schemas.ts`

- `historyEntrySchema` gains `image`, `digest` and `rolledBackTo`, each optional.
- `rollbackRequestSchema`: `{ generation?: number }`.
- `rolledBackSchema`: `{ rolledBackTo: number, status: serviceStatusSchema }`.
- Both new schemas are named in the schema table `test/fixtures.test.ts` reads, against
  `fixtures/control-plane/RollbackRequest*.json` and `RolledBack*.json`, which
  `ControlPlaneFixturesSuite` writes.

`src/client/control-plane.ts`

- `rollback(projectId, name, generation): Promise<RolledBack>`. The console always names a
  generation.
- `descriptor(projectId, name, generation): Promise<ServiceDescriptor>`.

## The service page's history

Columns: What, Generation, Image, Digest, Who, When, and the rollback control.

- **Image** and **Digest** (first twelve characters, the whole digest as the cell's title) on an
  entry that has them; a dash otherwise.
- **What** for a rollback reads `Rolled back to generation N`.
- **The rollback control** is on a row exactly when the entry has a digest and that digest is not
  the newest digest in the history. So it is absent from a restart's row, from every row whose
  descriptor the service already has, and from a row recorded before digests were kept. That
  last absence is the one case where the console offers less than the control plane allows: such
  a generation can still be rolled back to with the CLI.

The control is a `<details>`: its summary is `Roll back`, and its body says
`Apply generation N's descriptor again (image <image>) as a new generation.` beside a submit
button, `Roll back to generation N`. That is the confirmation; it works with scripts off.

The form posts `intent=rollback` and `generation=N` to the page's action, which calls
`client.rollback` and redirects to the service page. A refusal is shown where the page shows
every refused operation, in the control plane's words.

## Reading a past descriptor

The digest in each row is a link to the apply page with `?name=<service>&generation=N`, which opens
with that generation's descriptor in the text area (from `client.descriptor`) and a notice that applying
it is a new generation. That is how a member reads a past descriptor in the console, compares it, and
applies it changed or unchanged; it is also what exercises the descriptor route, which the console's
parity check requires of every route.

## Hosts

`service.rollback` is added to the operations in `src/extensions/types.ts`. A host that hides it
gets the history with no rollback control, and its `HostActions` for that operation are rendered
beside where the control would be, as for the other operations.

## The fake control plane

`src/testing/fake-control-plane.ts` keeps each applied descriptor by generation, writes `image`,
`digest` and `rolledBackTo` into its history, and serves both routes with the control plane's
refusals and status codes (contracts/control-plane.md). Its digest need only be equal for equal
descriptors; it does not have to equal the control plane's.

## Tests

- `test/client.test.ts`: both client methods, and a refusal surfacing as the client's error.
- `test/fixtures.test.ts`: the schemas against the fixtures.
- `e2e/tests/services.spec.ts`: a member rolls a service back and the page shows the rollback
  first in its history; the control is offered only where it can be made; a refused rollback
  shows the control plane's refusal. Run against the fake and, with `just test-console-compose`,
  against a running control plane.

A test that submits the rollback waits for the page it lands on before reading the history, and
reads the history as the service's own page shows it, which is an entity read and not a listing.
