# Erasure on the platform

> The keyring that holds every data subject's key apart from project databases, its root key, how an erasure is held, applied and certified, restores, and what reaches other projects.

Source: https://docs.ankka.cloud/platform/erasure/
A service marks the fields that are about a person as personal fields of a data subject, and every store
holds them encrypted under that data subject's **subject key** (see
[Personal data and erasure](../build/personal-data.md)). This page is about the other half: where the keys
are kept, and what happens when one is destroyed.

The keys are kept by the **keyring**, a platform program with its own database, apart from every
project's database and every project's backup. That separation is the whole point: a project's backup
holds only ciphertext, so destroying a key in the keyring makes every copy of that data subject's
personal fields unreadable, the backups included, with nothing rewritten.

## Turning erasure on

Erasure is an installation's setting, read by the control plane when it starts:

| Variable | Setting | Default | Meaning |
|---|---|---|---|
| `ANKKA_ERASURE` | `ankka.controlplane.erasure.enabled` | `off` | `on` when the installation runs a keyring. Anything but `on` or `off` stops the control plane, naming it. |
| `ANKKA_KEYRING_INSTANCES` | `ankka.controlplane.erasure.keyring-instances` | `2` | How many instances of the keyring run; a whole number of at least 1. |
| `ANKKA_ERASURE_RERUN_WINDOW` | `ankka.controlplane.erasure.rerun-window` | `30d` | How long after an erasure is applied every service's erasure handler is run again. |
| `ANKKA_KEYRING_ADDRESS` | | | Where the keyring answers, which the control plane reaches as `ankka://platform/controlplane`. |

`GET /installation` reports whether erasure is on and how many keyring instances hold
its keys. While erasure is off:

- a service whose descriptor declares `"personal": true` is refused with
  `the service declares personal fields and this installation's erasure is off (ANKKA_ERASURE)`;
- every write to a project's erasure requests is refused as `409`, saying erasure is off.

A service reaches the keyring at `ANKKA_KEYRING_ADDRESS`, a variable only the platform sets: a descriptor
that gives it is refused. A service given none, on a cluster, reaches no keyring, and every read or write
of a personal field it makes is refused as unavailable.

## The keyring

The keyring is the image `ankka-keyring`. It serves over mutual TLS, and refuses to start unless its
certificate carries the identity `ankka://platform/keyring`, so every service that calls it knows it is
the keyring. It reads its settings from its environment, and names every setting that is wrong before it
refuses to start:

| Variable | Default | Meaning |
|---|---|---|
| `ANKKA_KEYRING_PORT` | `8443` | Where it serves the platform's programs. |
| `ANKKA_KEYRING_PROBE_PORT` | `7627` | Where it answers `/ready` and `/status`. |
| `ANKKA_CONTROLPLANE_URL` | | The control plane, which holds the first copy of the erasure log. Required. |
| `ANKKA_KEYRING_KMS_KEY` | | A Cloud KMS key's name, for the root key on Google Cloud. |
| `ANKKA_SECRET_KEY` | | The key that keeps the root key on Postgres, when no KMS key is named. |
| `ANKKA_KEYRING_GRANTS_DIR` | | A directory of `<project>/grants.json`, in place of the cluster's project grants. |
| `ANKKA_MACHINE_ISSUER`, `ANKKA_MACHINE_JWKS_URL` | | Where machine tokens are verified; set together or not at all. |
| `ANKKA_BASE_DOMAIN`, `ANKKA_HTTPS_PORT` | `443` for the port | With no machine issuer named, the issuer is the control plane's, `https://api.<base>[:port]`, its keys read on the control plane's `keys` port. Without a base domain no machine is admitted. |

Its certificate is read from `/var/run/secrets/ankka/service`, its database from the same `ANKKA_DB_*`
variables a service's is, and the erasure log's second copy from the platform bucket
`platform.erasure-log` through the `ANKKA_S3_*` variables.

Several instances of the keyring share its database. Every service instance holds one channel open to
one of them; a notice written by any instance reaches every service instance through the database, which
each keyring instance reads every second for the services connected to it.

What it keeps, per project: a **key-encryption key**, wrapped by the root key; a **lookup key**, for the
lookup tokens views compare (see [Views](../build/personal-data.md#views)); and a subject key for each data
subject, wrapped by the project's key-encryption key. When a subject key is destroyed, a **tombstone**
takes its place, so a later first write for that data subject is refused rather than given a new key.

Who may have a key:

- a service of the project, for its own data subjects;
- a consumer in another project, for the data subjects of a topic it consumes, when its grant on that
  topic allows decryption (`--decrypt` on the grant); see
  [Another project and outside machines](#another-project-and-outside-machines);
- nobody else. The control plane's own identity may destroy a key and read an erasure's progress, and a
  key asked for as the control plane is refused. Every refusal is recorded.

## The root key

Every key-encryption key is wrapped by the **root key**, which is never in a project's database:

- **On Postgres** (no `ANKKA_KEYRING_KMS_KEY`), 32 bytes made at the keyring's first start and kept in
  its own secret store, under the name `keyring-root-key`, encrypted with `ANKKA_SECRET_KEY`. Two
  instances starting at once agree on one. It is only as safe as that key and that database: see
  [Secrets on the platform](secrets.md).
- **On Google Cloud** (`ANKKA_KEYRING_KMS_KEY` names a key,
  `projects/…/locations/…/keyRings/…/cryptoKeys/…`), the KMS key itself: the keyring asks Cloud KMS to
  encrypt and decrypt with it, as the identity its pod runs as, and never reads it. An installation that
  keeps real money or regulated records uses this.

**A backup of the keyring's database can recover an erased data subject's key until that backup ages
out.** Destroying a key deletes its row and vacuums the table, which frees the row but does not overwrite
the page it was on, and the write-ahead log, its archive and every base backup taken before the erasure
still hold the wrapped key. Whoever holds such a backup and the root key (on Postgres, the keyring's
secret store and `ANKKA_SECRET_KEY`; on Google Cloud, the right to use the KMS key) can unwrap it. A
keyring restored from such a backup is safe, since it replays the erasure log before it answers and
destroys the key again; the exposure is a copy of the backup read outside the keyring. Erasure is final
for backups only when the oldest backup and archived log taken before it have aged out of the keyring's
retention, so keep that retention as short as recovery allows, and the root key apart from the backups.
How it is backed up, how long to keep it and how to restore it is in
[Back up and restore the keyring's database](../operate/recovery.md#back-up-and-restore-the-keyrings-database).

## Asking for an erasure

A member asks for an erasure of one data subject in one project, by hand:

```bash
ankka projects erasures file player/8c1f -p brand
ankka projects erasures file player/8c1f -p brand --not-before 2031-10-08 --reason aml-retention --correlation closure-4411
ankka projects erasures list -p brand --state held
ankka projects erasures get <request> -p brand
ankka projects erasures replace <request> -p brand --not-before 2032-10-08
ankka projects erasures withdraw <request> -p brand
ankka projects erasures override <request> -p brand --reason "regulator order 2028/41"
ankka projects erasures certificate <request> -p brand
```

The console's project page has the same, in its **Erasures** section, and the platform's MCP server has
the tools `erasures_list`, `erasure_get`, `erasure_file`, `erasure_replace`, `erasure_withdraw`,
`erasure_override` and `erasure_certificate`, each acting as the member who is logged in. A service asks
from code, under the project's `erasure` grant (see
[Asking for an erasure from code](../build/personal-data.md#asking-for-an-erasure-from-code)).

A request is **held** while it has a not-before date that has not passed, **applying** while it is being
carried out, **applied** once its key is destroyed, and **withdrawn** if it was withdrawn while held.

- Whoever asked, or any member, may withdraw a held request, or replace its date and reason; a service
  that asked does so with its own certificate, while it holds the grant. A replacement names a date still
  to come: a request is released early only by an owner's override. A further request for a data
  subject that has a held one is refused (409), naming the held request; the request's history keeps
  each replacement.
- Only an owner may **override** a hold, with a reason, which is recorded with the owner's name; the
  request is applied at once. A member is refused.
- A reason, of a hold or of an override, is kept in the request, its history and its certificate, and
  is not erased with the data subject. Write why, never who: no name, email or account number.
- An applied request cannot be withdrawn, by anyone. Asking again for a data subject already erased
  answers the applied request and applies it again.
- `GET /erasures?correlation=<id>` lists the requests carrying one correlation id in every project the
  caller is a member of, so an auditor reading one project finds the request asked in another.

## Applying an erasure

Applying a request, in order:

1. The control plane writes the erasure to the **erasure log**, in two places: a table in its own
   database, and an object in the platform bucket `platform.erasure-log`. When either write fails, no key
   is destroyed: the request stays applying, and says why (`lastFailure`, `erasure-apply-failed` in the
   project's history), and is tried again.
2. The keyring destroys the subject key and writes its tombstone.
3. The keyring sends a **notice** to every instance of every service of the project. Each instance drops
   the key from its cache, redacts the data subject's fields and lookup tokens in each of its views, ends
   the agent sessions and instances of the data subject, runs the service's erasure handler (on one
   instance of the service), and acknowledges with what it did.
4. The control plane records each service's **completion**, and when the erasure became **final**: when
   every service of the project that declares `"personal": true` has completed, whether or not it was
   running when the key was destroyed, and every object a handler erased is gone for good, which on a store with a
   soft-delete window is when that window has passed.

`ankka projects erasures certificate` gives the **erasure certificate** of an applied request: the request,
the data subject, who asked, the hold and any override, when the key was destroyed, each service's
completion, and when the erasure became final. A personal service that has not completed is listed with no
completion, on the certificate and on the request. It holds no personal field.

The control plane's **sweep** runs every five minutes: it applies each held request whose not-before date
has passed, with no one acting, tries again each request left applying, and reads completions. Every
fifteen minutes, for each request applied within the re-run window (`ANKKA_ERASURE_RERUN_WINDOW`, 30 days
by default), it asks the keyring to send the notice again, so every erasure handler runs again and objects
written under a subject prefix since are erased too.

## The 60-second bound

Every service instance holds its channel to the keyring open from the moment it starts, before any
component recovers, and the keyring confirms it with a heartbeat. A notice drops the subject's key before
anything else hears of it, so no read after the notice opens a field under the destroyed key. An instance
whose channel has gone unconfirmed for `ankka.keyring.notice-bound` (60 seconds) cannot know that it
missed no notice, and drops every key it holds; every read then asks the keyring again, which answers that
the key is destroyed.

So within 60 seconds of an erasure every new key fetch, every recovery, every view and every consumer of
the project reads the data subject as erased. An entity already in memory keeps what it decrypted until it
is passivated or its instance restarts (see [Limitations](../reference/limitations.md#personal-data-and-erasure)).

## The keyring client

Each service instance keeps the keys it has used in a cache:

- `ankka.keyring.cache.max-keys` (`10000`): how many it holds; the least recently used goes first.
- `ankka.keyring.cache.ttl` (`5m`): how long a key is held before it is asked for again.
- `ankka.keyring.cache.foreign-ttl` (`30s`): how long a key of another project's data subject, read under
  a grant that allows decryption, is held. No notice of that project reaches this one, so this is how
  long an erasure or a revoked grant there takes to reach a read here.
- `ankka.keyring.cache.outage-bound` (`60s`): how long a held key still serves while the keyring cannot
  be reached, counted from when the keyring was last heard (a fetch answered, or the notices channel
  confirmed). Past it, a read or write that needs a key the instance must ask for is refused as
  unavailable. It may not exceed `ankka.keyring.notice-bound`, and a service whose settings say otherwise
  does not start: an instance that cannot hear the keyring cannot hear an erasure either, and drops every
  key it holds at the notice bound, so no outage keeps an erased data subject readable past 60 seconds.
- `ankka.keyring.fetch-timeout` (`2s`): how long one fetch waits.

While the keyring cannot be reached no erasure applies, since no key can be destroyed; an entity whose
recovery needs a key it does not hold fails and is recovered again with backoff, and is never read as
erased. When the keyring returns, recovery resumes with the next fetch.

## Restores and the erasure log

A restore must not bring an erased data subject back. Three cases:

- **A project's database restored to before an erasure.** The keyring was not restored, so the key is
  still destroyed: every entity recovered from the restore reads the data subject as erased. A view's rows,
  though, were redacted after the moment restored to. A service switched to a restore is therefore not
  ready, as `erasure-replay`, until it has carried out every notice the keyring holds for its project: its
  views redacted, its lookup tokens dropped, its sessions ended. The restore's status reports when each
  service finished (`erasureReplayedAt`), and each service answers the same on its observability port at
  `/observability/erasure-replay`. A restart on the same database does not wait again; the first start of a
  service on a new database waits once.
- **The keyring's own database restored to before an erasure.** It would hold the destroyed key again.
  Before it answers anything, the keyring reads both copies of the erasure log (the control plane's,
  through `GET /internal/erasure-log`, which only the keyring may call, and the bucket's), applies every
  entry of the union its database has not applied, whatever its number, and reports on `/status` a copy
  that was behind. Entries are not necessarily applied in the order they are numbered (a destroy that met
  an unavailable keyring is retried later), so a backup can hold a later entry and lack an earlier one. With either copy unreadable it applies
  nothing and answers nothing, and tries again every five seconds; `/ready` holds until it has applied the
  log.
- **The control plane's database restored.** Its copy of the log is made whole again from the bucket
  before the restored control plane is released (see
  [Restore the control plane's own database](../operate/recovery.md#restore-the-control-planes-own-database)).

An erasure is in the log before any key is destroyed, so a key that was destroyed is always in at least one
copy.

## Another project and outside machines

A personal field published to a topic stays encrypted under the producing project's subject key, in the
message and in whatever another project keeps of it.

A **consumer in another project** is given the producing project's subject keys only when its grant on
the topic allows decryption:

```bash
ankka projects grants make service:payments/cardholders topic players consume --decrypt -p brand
```

The keyring reads every project's grants, admits the consumer's service because of that grant, and
records each key it gives with the grant it was given under. **A grant with decryption is project-wide in
effect.** An envelope does not say which topic it came from, so the keyring cannot tell a key asked for
to read the granted topic from one asked for any other field of the producing project: a consumer granted
decryption on one topic can be given the key of every data subject of that project, and a machine granted
decryption can have any of the project's fields decrypted. Grant decryption only to a consumer you would
trust with every personal field of the project. What the consumer keeps of the field (a
view's row, an entity's state, a message it publishes again) stays encrypted under the producing
project's keys, so an erasure in the producing project reaches it with no erasure request of its own.
A consumer whose grant does not allow decryption reads every personal field of the producing project as
erased, and the keyring records each refusal. The consumer hears no notice of the producing project: it
holds a key of that project for `ankka.keyring.cache.foreign-ttl` (30 seconds) and never holds a
refusal, so an erasure there, or a revoked grant, reaches its reads within 30 seconds, and a grant made
later counts at once.

A **machine outside the installation** is never given a key. The keyring answers `POST /v1/decrypt` for a
machine token whose grant allows decryption, field by field, and records every decryption against the
machine and the grant, never with a value. The machine reaches it through the installation's gateway at
`https://keyring.<base domain>/v1/decrypt`, the one path routed there, with its token as a bearer:

```json
{"grant": "g-warehouse", "project": "brand", "manifest": "player-event",
 "fields": [{"$personal": 1, "project": "brand", "subject": "player/8c1f", "c": "…"}]}
```

is answered `{"values": ["Ada Byron"]}`, each field's own JSON in order, or `null` for a field that holds
no value. The manifest is the one the message was published under. A revoked grant is refused at the
next request, and so is a request naming an erased data subject, which the refusal names; each refusal is
recorded. A machine whose grant does not allow decryption has only the envelopes, which it cannot open,
and its request for a key is refused like any other.

**An erasure reaches a partner's later reads, not the copies it already stored.** A value a machine was
answered before the erasure is the partner's to erase.

## Replicated entities

A data subject's keys are kept by one installation's keyring, and its notices reach the services of that
installation. A copy of a replicated entity at another installation reads its keys from the home's keyring,
through a keyring relay that a replication layer supplies (`ankka-replication`); its own keyring never makes
one. With no relay, the copy's fetch is unavailable and retried, and never reads a field as erased. See [Limitations](../reference/limitations.md#personal-data-and-erasure) before marking
personal fields in a replicated entity.
