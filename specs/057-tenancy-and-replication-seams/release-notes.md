# Release notes draft: tenancy and replication seams (spec 057)

## Answered requests

- `ankka-vendor/notes/ankka-requests.md` 1, a deploy token scoped to one project: `POST
  /organizations/{id}/tokens` takes `"project"`, `ankka organizations tokens create --project`.
- `ankka-vendor` 2, a platform administrator issuing a token and accepting a grant for an
  organization they are not a member of, recorded as administrative.
- `ankka-vendor` 4, `GET /installation` reporting the broker (`nodes`, `defaultCopies`,
  `minInSync`), the longest topic retention, and erasure (absent until the platform has it).
- `ankka-replication/notes/ankka-requests.md` 1, 2, 3 and 5: the provider is given the replicated
  components; `ReplicationPeer.reported` delivers a handover's report; `AnkkaTestKit.installations(…)
  .using(factory)`; `ANKKA_INSTALLATIONS=a,b,c` names installations without a database.

## Added

- **A deploy token for one project.** A member of that project and of no other, which may also make
  and end the project's own grants. `GET /organizations/{id}/tokens` carries each token's `project`
  and `administrative`; the CLI's list has a `PROJECT` column.
- **A platform administrator acts for an organization.** Token creation and grant acceptance by an
  administrator who is not a member are allowed and marked `administrative` on the token list and on
  every grant act (`GrantAct`, `GrantChangeRecord`).
- **The installation's facts.** `broker`, `retention` and `erasure` beside `backups` on `GET
  /installation`, and in `ankka installation`. `ANKKA_BROKER_NODES` is set on the control plane's
  Deployment (`1`) and by `broker-three-nodes` (`3`).
- **Who acted on a forwarded write.** A component call made from an authenticated endpoint carries
  `ankka-attribution`, the principal's subject; at the home a command context exposes `attribution`
  and `forwardedFrom`, each persisted event's journal record keeps both (`NON_ABSENT`, so records
  without them are unchanged), and an event context reads them back. The home refuses a forwarded
  write from an installation it does not name, `Forbidden`, naming both installations.
- **Sidecar protocol 1.22.** `attribution` and `forwarded_from` on `EventSourcedIn.Command` (8, 9),
  `EventSourcedIn.Event` (4, 5) and a module's `FoldRequest` (8, 9); every SDK exposes them on its
  command and event contexts. Additive: a process at 1.21 is served as before.

## Before you upgrade

1. **`ReplicationTransportProvider.transport` gains a parameter**, `replicated: Set[ComponentId]`. A
   provider written against the previous release does not compile against this one; there is no
   default, so an old provider cannot silently serve every sharded type. `ankka-replication` moves
   with this release.
2. **A workflow's or key value entity's context does not yet expose the attribution of a call.** Only
   event sourced entities replicate, and only their contexts carry it (listed in the limitations).
3. **`--project` on `ankka organizations tokens create` is the token's scope**, no longer the
   general project option (`-p`) it shared with the other commands.
