---
paths:
  - "modules/core/src/main/scala/**/personal/**"
  - "modules/runtime/src/main/scala/**/erasure/**"
  - "keyring/**"
  - "controlplane/src/main/scala/**/ErasureEndpoint.scala"
  - "controlplane/src/main/scala/**/deploy/Erasure*"
  - "controlplane/src/main/scala/**/application/Erasure*"
  - "kustomization/components/keyring/**"
  - "modules/testkit/src/main/scala/**/InMemoryKeyring.scala"
  - "protocol/fixtures/personal/**"
---

# Personal data erasure: the personal type, the keyring and erasure requests

Feature 042. A field typed `Personal[A]` (`core/personal`) is stored only as a personal envelope —
`{"subject","project","data"[,"lookup"]}`, AES-256-GCM over the value's JSON with the subject and project
as associated data — because its codec, found by any derived codec through an `inline given` in the
companion, writes nothing else. Erasure destroys the subject's key in the keyring (`keyring/`, an ankka
application of its own, deployed beside the control plane), so every copy becomes unreadable without
being rewritten. `docs/platform/erasure.md` is the contract with a service; `specs/042-*/contracts/` the
contracts between the parts.

## The scope a codec reads its keys from

`PersonalScope` is a thread-local (keyring, project, lookupAllowed) with a JVM default: every running
service registers its keyring and project, and when all registrations share one project that is the
default. On the platform a JVM runs one service, so the default is always right; in a test JVM with two
projects there is no default and a site that set no scope **fails closed** (`Unavailable`). Sites that
must be explicit: every row write (`PersonalScope.allowingLookup`, the only place a lookup token is
written) and anything a two-project suite exercises. Encoding `Erased` needs a project only.

## Traps

- **A running entity holds plaintext.** It decoded or built its state before the erasure. `Personal`'s
  accessors (`toOption`, `getOrElse`, `fold`, `map`, `isErased`) consult `PersonalScope.isDestroyed`, which
  every service's key cache fills on a notice, so a held value reads as erased at once; a pattern match on
  `Present` does not, and the docs say to read through the accessors.
- **A stored value is redacted, a fresh one refused.** `Present.stored` is set when a value is written or
  read; re-encoding a stored value of an erased subject (the next snapshot, a key value state written for
  another field) writes the erased form, where a fresh one is refused. The refusal is made in
  `Personal.present`, inside the handler, because a refusal thrown during a persist crashes the entity and
  the caller times out. Both entity hosts now answer a thrown `CommandError` as the refusal it is.
- **`Personal[X]` inside `X` expands forever.** The inline given derives the inner codec where it is
  used, so an enum with a `Personal[ThatEnum]` case never finishes compiling. Wrap a separate type
  (`SessionContent` beside `SessionMemoryEvent`).
- **`writeToArray` inside a codec corrupts the outer write.** jsoniter reuses one writer per thread; a field
  codec encodes its inner value with `writeToArrayReentrant` and decodes with `readFromArrayReentrant`.
- **The manifest is not in the associated data.** A consumer in another service or language decodes a
  message under its own type and manifest; binding the manifest made every such read corrupt.
- **Redaction is one statement over the envelope's fixed grammar** (`ViewRedaction`): the key order every
  SDK writes is load-bearing. A subject's alphabet has one regular-expression character, `.`, escaped.
- **The keyring applies its own schema** (`Schema`, from the runtime jar's DDL): kustomize's load
  restrictor stops its component reading `postgres/ddl`, and a copy would be a second schema.
- **`protocol/fixtures/personal/` is not compared byte for byte** — the nonce is random — so
  `PersonalFixturesSuite` pins the rows and checks every stored envelope decodes as its row says.
- **Every test kit runs the JVM's `InMemoryKeyring` by default.** A suite that runs the keyring, or a
  service with its own channel, passes `keyring = None` to every other kit, or the JVM default scope sees
  two projects and fails closed.
