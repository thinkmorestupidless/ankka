# Feature Specification: A Storage Client in the SDKs — A Service Reaches Its Bucket as It Reaches Its Secrets

**Feature Branch**: `055-storage-client`

**Created**: 2026-10-19

**Status**: Draft

**Input**: User description: "A storage client in the SDKs: a service that has asked for a bucket
reaches it as `context.storage`, beside `context.secrets`, on endpoints, workflow steps, consumers,
timed actions and agents, never entities or views. Filesystem vocabulary, object semantics: paths
with slashes, streaming put and get with a content type, delete, list by prefix, stat; signed upload
and download URLs made against the public endpoint; no directories, rename, append or partial
writes, said on the limitations page. Entities hold the path, the store holds the bytes. One S3
implementation in the runtime over the JDK's HTTP client, no AWS SDK dependency, configured from
the ANKKA_S3_* variables features 034 and 039 already give; a directory on disk when the service
runs on a developer's machine under `ankka local`; an in-memory store in AnkkaTestKit so tests need
no Docker. A descriptor field naming prefixes that are publicly readable without a signature, so a
listing photo has a cacheable URL anyone can GET; the platform sets the bucket's policy, the cloud
provider may put a CDN in front, and the service gets one more variable with the public base so
`publicUrl(path)` answers without the service knowing whether a CDN exists. The Python, TypeScript
and Rust SDKs speak S3 themselves from the same variables rather than carrying bytes through the
sidecar. Out of scope: multipart resume, object metadata beyond content type, lifecycle rules,
Azure or any non-S3 backend."

## Context

Feature 034 gives a service that sets `provisionObjectStorage: true` one bucket in the
installation's object store and one storage credential that reaches that bucket and no other,
and feature 039 keeps that bucket in Google Cloud Storage when the installation says so. Both
stop at the service's door: the program is given `ANKKA_S3_ENDPOINT`, `ANKKA_S3_REGION`,
`ANKKA_S3_BUCKET`, `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY` (and `ANKKA_S3_PUBLIC_ENDPOINT`
when its bucket is reachable from the internet), and "any S3 client works". 034 declined an SDK
client on the ground that every language has one and the platform gains nothing by wrapping it.
The limitations page says so in those words, and adds that a service run on a developer's own
machine is given no bucket.

Three things have changed since. First, the client is not free: `docs/platform/object-storage.md`
now carries a page of settings every client needs — path-style addressing, the region, checksums
only when required, an interceptor for Google's version listing — and a service gets them wrong
before it gets them right. Second, a service written for the platform cannot run on a laptop: its
S3 client has nowhere to point, so a developer either installs a store by hand or writes a
second code path for local runs, and its tests need Docker for a bucket when they need it for
nothing else. Third, the secret store (features 023 and 038) showed the shape that works: one
small interface on the contexts that may block, `context.secrets`, with the backend the
installation's business, the same code on every backend, a test double in the testkit and the
same surface in every SDK. The storage client is that shape applied to the bucket.

The decisions this feature makes:

- **One client, beside the secret store.** A service reaches its bucket as `context.storage` on
  the contexts that may block — endpoints, workflow steps, consumers, timed actions and agents —
  and not on an entity or a view, for the reason the secret store gives: a put or a get is a
  blocking call the single-writer path must not make, and an object read in a handler is one
  line away from an event. An entity keeps the object's *name*; the bucket keeps the bytes.
- **Filesystem vocabulary, object semantics.** An object's name may contain `/`, and a listing
  is by prefix, so `listings/42/photos/` reads like a directory. It is not one: there is no
  directory to make or remove, no rename, no append and no partial write, and the client offers
  none of them rather than a slow or unsafe imitation (a rename on every S3 store is a copy and a
  delete, not an atomic move). The limitations page says what the client is not.
- **The operations are the ones every S3 store does well.** Put and get as streams with a content
  type, so an object larger than an instance's memory passes through an endpoint; delete; list by
  prefix; stat, answering size, content type and when the object was last kept. A signed URL for
  reading or keeping one object, made against the public address, as the platform page already
  tells a service to do by hand.
- **Buckets are named in the descriptor, each reached in its own way.** The input asked for
  prefixes readable without a signature. Neither store the platform runs can grant that: Garage
  has no bucket policy and makes a whole bucket readable or none of it, and 039 enforces public
  access prevention on every bucket the platform makes in Google Cloud Storage (FR-009), which is
  a bucket-wide setting. And one service wants more than one way of being read: an auction site
  has photos anyone may see, exports a signed URL hands to one person, and documents nothing
  outside the cluster may touch. So the unit of exposure is the bucket, and a service may have
  several. The descriptor's `buckets` list names each one beside the bucket `provisionObjectStorage`
  already asks for, which stays as the service's unnamed bucket so that nothing deployed changes:

  ```json
  {
    "service": {
      "provisionObjectStorage": true,
      "buckets": [
        { "name": "photos", "exposure": "public" },
        { "name": "exports", "exposure": "signed", "origins": ["https://app.example.com"] },
        { "name": "archive", "supplied": { "endpoint": "https://s3.eu-west-2.amazonaws.com",
                                           "region": "eu-west-2", "bucket": "acme-archive",
                                           "credential": "archive-store" } }
      ]
    }
  }
  ```

  A named bucket's **exposure** is `private` (reached inside the installation only, the default),
  `signed` (reachable from the internet for URLs the service signs, with the origins a browser may
  send from, as `exposeObjectStorage` and `objectStorageOrigins` do for the unnamed bucket) or
  `public` (every object readable from the internet without a signature, at an address the
  platform derives). A bucket the platform makes is named from the project, the service and the
  bucket's name, and the service's one storage credential reaches every bucket the platform made
  for it and no other service's. A **supplied** bucket is one the user made and configured
  themselves: its entry gives where it is and the project secret holding its credential, the
  platform makes nothing for it, and the client reaches it the same way. The unnamed bucket keeps
  034's and 039's fields and rules unchanged.
- **The client reaches a bucket by name.** `context.storage` is the unnamed bucket;
  `context.storage.bucket("photos")` is a named one, with the same operations. Asking for a name
  the descriptor does not give is refused, naming the list. A public bucket answers one more
  operation, `url(name)`: the address a browser reads that object at.
- **The public address is the platform's.** For every named bucket the service is given
  `ANKKA_S3_BUCKET_<NAME>`, and for a public one `ANKKA_S3_PUBLIC_URL_<NAME>`, the base every
  object in it is read at. On Garage the platform routes it under the store's hostname; on Google
  Cloud Storage it is Google's address or, when the installation puts a content delivery network
  in front, that network's. `url(name)` is the base and the name, computed and never fetched, so a
  service cannot tell and need not care whether a CDN exists.
- **One implementation in the runtime, speaking S3 over the JDK's HTTP client.** The operator
  already reaches Garage that way (`GarageStore`), with no AWS dependency, and a service's
  dependencies should not grow by an SDK the size of the runtime for five operations. The client is
  configured from the variables 034 and 039 give and nothing else, so a service that brings its
  own store through `ANKKA_S3_*` is given the same client against it.
- **A directory on a developer's machine.** A service run on a laptop, with `sbt run`, beside
  `ankka local`, has no `ANKKA_S3_*` variables and is given a client over a directory on disk
  instead, as it is given a database on `localhost:5432` by default. Signed URLs and the public
  bucket's addresses are served by the service's own HTTP server from a path the runtime
  reserves, so a page written against them works on a laptop unchanged.
- **An in-memory store in the testkit.** `AnkkaTestKit` boots the service with a store in memory
  that a test can seed and read, so a test of an endpoint that keeps an upload needs no Docker,
  and `restartService()` keeps the objects as it keeps the journal. The Python and TypeScript
  testkits do the same.
- **Every SDK speaks S3 itself.** The sidecar carries a secret's value because a secret is small;
  an object is not, and the sidecar's protocol is the wrong place for it. The Python, TypeScript
  and Rust SDKs each offer the same client, configured from the same variables, signing and
  sending their own requests. A module reaches the network only through its host's outbound call
  (feature 030), so the Rust client's put and get carry a whole object through the module's memory.

What this feature is not: resumable or multipart uploads; metadata on an object beyond its content
type; lifecycle rules, which stay the platform's (`objectStorageVersionAgeDays`); a store that
does not speak S3; or a change to how the unnamed bucket is provisioned, moved, credentialed or
exposed.

## Clarifications

### Session 2026-10-19

- Q: Public objects: a second public bucket per service, public prefixes on the one bucket, or
  none in this feature? → A: Neither as asked. Buckets are named and configured in `service.json`,
  each with its own exposure (private, signed or public), each made by the platform or supplied by
  the user, and the storage client reaches each by name. The unnamed bucket 034 gives stays as it
  is.
- Q: Which SDKs carry the client in this feature? → A: All four. Scala in the runtime; Python,
  TypeScript and Rust each speaking S3 themselves from the same variables; Rust holding a whole
  object in the module's memory, said on the limitations page.
- Q: Who serves signed URLs and public addresses on a developer's machine? → A: The service
  itself, from a path the runtime reserves on its HTTP port, only while its store is a directory;
  a deployed service serves nothing there.
- Q: What does the client do for a service with `objectStorageCredential: false`, which has no key
  to sign with? → A: Refuses every call with a reason naming `objectStorageCredential`; such a
  service uses Google's own client, as 039's docs say.
- Q: Are the proposed terms right: storage client, named bucket, exposure (private, signed,
  public), supplied bucket, public bucket, public address, content type, prefix, content delivery
  network, directory store, with the synonyms each refuses? → A: Yes, all as written.

### Amended in planning, 2026-10-19

Planning found three things the spec assumed wrongly or did not know. Each is decided in
[research.md](research.md) and the text below was changed to match.

- **A module cannot speak S3** (R6). Feature 030's outbound call reaches only another ankka
  service by name, and a module has no other network. The Rust client is a new `storage` host
  import answered by the runtime's own client, so a module's objects pass through the platform's
  program whole, at most 4,000,000 bytes as every module call is. FR-014 and the Context say so;
  "no object passes through the platform's own program" holds for Python and TypeScript.
- **The HTTP layer cannot stream** (R4). Every request body is read whole with an 8 MiB limit
  and every response is strict, so an object larger than an instance's memory cannot pass
  through an endpoint today. `ankka-http` gains a streamed request body and a streamed response
  as part of this feature. FR-002.
- **A deployed service with no bucket has no store** (analysis, I1). The directory store is for
  a service run on a developer's machine; a service whose formation is the cluster's and that
  has no `ANKKA_S3_*` variable is given the client that refuses, naming `provisionObjectStorage`.
- **A laptop's signed keep for Python and TypeScript is bounded** (analysis, I2). Their
  directory store's endpoint is a route served through the sidecar, so FR-014 is narrowed to
  the client's own calls and the limitations page says the bound.
- **A put declares its length** (R5). S3's `PutObject` needs `Content-Length`; a stream of
  unknown length needs the encoding Garage refuses. FR-002 and the limitations page say so.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service keeps and reads objects through the client (Priority: P1)

A developer writes an endpoint that takes an uploaded image, keeps it in the service's bucket
under `listings/42/photos/front.jpg` with its content type through `context.storage`, and records
the name on the listing entity. Another endpoint reads it back as a stream and answers it with
the content type the store kept. The service lists what is under `listings/42/` and deletes one
object. The same code runs against Garage, against Google Cloud Storage and against a store the
service brought itself.

**Why this priority**: This is the feature. A client that does not put, get, list and delete is
nothing.

**Independent Test**: Boot a service in the testkit against the in-memory store and round-trip
an object with its content type; deploy the same service in the k3s suite with
`provisionObjectStorage` and round-trip an object through its endpoints against Garage; run the
same cases against Google Cloud Storage where `GcsCompatibilitySuite` runs. An object larger than
the instance's heap passes through put and get.

**Acceptance Scenarios**:

- added `features/storage-client/client.feature`: a service keeps an object through the storage client and reads it back
- added `features/storage-client/client.feature`: an object is read back with the content type it was kept with
- added `features/storage-client/client.feature`: a service lists the objects under a prefix
- added `features/storage-client/client.feature`: a service asks what an object is and is told its size, its content type and when it was kept
- added `features/storage-client/client.feature`: a service deletes an object and it is no longer there
- added `features/storage-client/client.feature`: reading an object that is not there answers nothing rather than failing
- added `features/storage-client/client.feature`: an object larger than an instance's memory passes through the storage client
- added `features/storage-client/client.feature`: a service's code against the storage client is the same on every object store
- added `features/storage-client/client.feature`: an object's name that breaks the rule is refused before anything is sent
- added `features/storage-client/client.feature`: an object's name longer than the limit is refused
- added `features/storage-client/client.feature`: a storage client call is refused with a reason when the object store cannot be reached
- added `features/storage-client/client.feature`: a keep that fails part way leaves no partial object

---

### User Story 2 - The client is offered where a blocking call belongs and nowhere else (Priority: P1)

The client is on the contexts of endpoints, workflow steps, consumers, timed actions and agents,
as the secret store is. An entity's command context and a view have no `storage` at all. A
service that asked for no bucket, and brought none, is given a client that refuses every call
and says why.

**Why this priority**: Where the client is offered is a rule about the single-writer path, and
it is cheaper to hold from the first release than to withdraw later.

**Independent Test**: Compile-time: an entity's command handler cannot name `storage`. Runtime:
a workflow's command handler calling it is refused as a secret store call is; a service without
a bucket gets `Unavailable` with a reason naming `provisionObjectStorage`.

**Acceptance Scenarios**:

- added `features/storage-client/contexts.feature`: the storage client is offered to an endpoint, a workflow step, a consumer, a timed action and an agent
- added `features/storage-client/contexts.feature`: an entity and a view have no storage client
- added `features/storage-client/contexts.feature`: a workflow keeps an object in a step and not in a command
- added `features/storage-client/contexts.feature`: a service that has no bucket is told so when it calls the storage client
- added `features/storage-client/contexts.feature`: a service that reaches its bucket as its workload identity is refused by the storage client with the reason
- added `features/storage-client/contexts.feature`: an entity keeps an object's name and the bucket keeps the object

---

### User Story 3 - A browser reads and keeps objects through URLs the client signs (Priority: P2)

A service whose bucket is reachable from the internet asks the client for a signed URL to read
`exports/march.pdf`, and for one that lets a page keep `uploads/passport.pdf`, each for a span
the service chooses. The client signs against the public address without the service naming it.
A service whose bucket is not reachable is refused a signed URL with a reason naming
`exposeObjectStorage`.

**Why this priority**: Signing by hand is where services get the address wrong today; the
platform page spends a section on it. It is P2 because the service can proxy bytes through
story 1 until it exists.

**Independent Test**: In the k3s suite, deploy a service with `exposeObjectStorage`, have it sign
a read and a keep URL through the client, and use both from the host through the gateway.
Deploy one without and assert the client refuses.

**Acceptance Scenarios**:

- added `features/storage-client/signed-urls.feature`: a service makes a signed URL for reading an object through the storage client and a browser reads the object
- added `features/storage-client/signed-urls.feature`: a service makes a signed URL for keeping an object through the storage client and a browser keeps the object
- added `features/storage-client/signed-urls.feature`: a signed URL expires when the service said it would
- added `features/storage-client/signed-urls.feature`: a signed URL for a span longer than the bound is refused
- added `features/storage-client/signed-urls.feature`: a service whose bucket is not reachable from the internet is refused a signed URL with the reason
- added `features/storage-client/signed-urls.feature`: a signed URL is made without a request to the object store

---

### User Story 4 - Buckets named in the descriptor, each reached in its own way (Priority: P2)

A developer names three buckets in `service.json`: `photos`, public; `exports`, signed, with the
page's origin; and `archive`, a bucket they made themselves in another store, with its credential
in a project secret. The service keeps a listing's photos through
`context.storage.bucket("photos")` and asks it for `url("listings/42/front.jpg")`, the address it
puts in the page; a browser reads the photo without a signature. It signs a URL for an export
through `bucket("exports")`. It keeps a document in `archive`, and the platform made nothing for
that one. Nothing in a private bucket is readable from outside the cluster. The installation
puts a content delivery network in front of public buckets or does not, and the service's code
is the same.

**Why this priority**: It is what an auction site, a shop or a gallery needs and the one thing the
platform's storage cannot do today: one service, several buckets, each exposed differently. It
is P2 because a signed URL (story 3) serves the private case on the unnamed bucket, and because
it changes what the platform provisions.

**Independent Test**: In the k3s suite, deploy a service with the three buckets, keep an object in
each through the client, read the public one from the host at the address the client answered
with no signature, read the signed one through a URL the client signed, and read the supplied
one from the store the test supplied; assert an unsigned read of the private, unnamed bucket is
refused. On Google Cloud Storage, assert the public bucket is readable by anyone and every other
bucket keeps public access prevention.

**Acceptance Scenarios**:

- added `features/storage-client/buckets.feature`: a service is given every bucket its descriptor names
- added `features/storage-client/buckets.feature`: a service reaches a named bucket through the storage client
- added `features/storage-client/buckets.feature`: a bucket name the descriptor does not give is refused
- added `features/storage-client/buckets.feature`: a browser reads an object in a public bucket without a signed URL
- added `features/storage-client/buckets.feature`: an object in a private bucket is not readable from the internet whatever the service's other buckets admit
- added `features/storage-client/buckets.feature`: a service signs a URL for an object in a signed bucket
- added `features/storage-client/buckets.feature`: a signed URL or a public address is refused on a bucket whose exposure does not allow it
- added `features/storage-client/buckets.feature`: the address of an object in a public bucket is answered without a request to the object store
- added `features/storage-client/buckets.feature`: a service's code is the same whether or not a content delivery network stands in front of its public buckets
- added `features/storage-client/buckets.feature`: a supplied bucket is reached through the storage client and the platform makes nothing for it
- added `features/storage-client/buckets.feature`: the storage credential of a service reaches every bucket the platform made for it and no other service's
- added `features/storage-client/buckets.feature`: a descriptor that names two buckets alike is refused
- added `features/storage-client/buckets.feature`: a descriptor whose bucket name cannot name a bucket is refused
- added `features/storage-client/buckets.feature`: the status of a service names each of its buckets, its exposure and its address
- added `features/storage-client/buckets.feature`: deleting a service keeps every bucket the platform made and its objects
- added `features/storage-client/buckets.feature`: a service that stops naming a bucket keeps it and loses its variables
- added `features/storage-client/buckets.feature`: a service's buckets are moved together

---

### User Story 5 - The same service runs on a laptop and in a test (Priority: P2)

A developer runs the service with `sbt run`, beside `ankka local`, and no `ANKKA_S3_*` variable. The
client keeps objects in a directory under the working directory; a signed URL and a public
bucket's address point at the service's own HTTP port and work from a browser on the machine. In
a test, `AnkkaTestKit` boots the service against a store in memory; the test seeds an object,
calls the endpoint, and reads what the service kept. The objects survive `restartService()`.

**Why this priority**: Without this a service with a bucket cannot be developed or tested
without a cluster, which is the limitation the feature exists to remove. P2 only because stories
1 and 2 define what it must imitate.

**Independent Test**: Start a service from the template with no variables and round-trip an
object; find it under the directory; read its public address with `curl`. In the testkit, seed,
call, read back, restart, read back again.

**Acceptance Scenarios**:

- added `features/storage-client/local.feature`: a service run on a developer's machine keeps its objects in a directory
- added `features/storage-client/local.feature`: a signed URL made on a developer's machine is served by the service itself
- added `features/storage-client/local.feature`: a browser keeps an object on a developer's machine through a signed URL the service serves
- added `features/storage-client/local.feature`: a request on a developer's machine without a signed URL is refused by the service
- added `features/storage-client/local.feature`: a public address on a developer's machine is served by the service itself
- added `features/storage-client/local.feature`: a service given the variables of an object store uses it rather than a directory
- added `features/storage-client/local.feature`: a service whose directory store cannot be written does not start
- added `features/storage-client/local.feature`: a deployed service serves no signed URL and no public address itself
- added `features/storage-client/testkit.feature`: a test keeps an object and the service reads it through the storage client
- added `features/storage-client/testkit.feature`: a test reads what the service kept through the storage client
- added `features/storage-client/testkit.feature`: objects kept in a test survive a restart of the service
- added `features/storage-client/testkit.feature`: objects kept in a test go when the test kit stops
- added `features/storage-client/testkit.feature`: two services started by the test kit do not see each other's objects
- added `features/storage-client/testkit.feature`: a test reaches a service's public bucket through the storage client

---

### User Story 6 - Every SDK offers the client (Priority: P3)

A Python, TypeScript or Rust service reaches its bucket through the same client with the same
operations and the same names, configured from the same variables, and its testkit holds the
same store in memory. No object passes through the sidecar.

**Why this priority**: The platform's promise is one programming model in four languages; a
client in one would be a divergence to list. P3 because each is a port of a settled interface.

**Independent Test**: Each SDK's test suite round-trips an object against its in-memory store
and against a Garage the suite starts; the conformance suite checks the name rule and the error
codes against the fixture every SDK reads.

**Acceptance Scenarios**:

- added `features/storage-client/languages.feature`: an object is kept and read through the storage client in every language
- added `features/storage-client/languages.feature`: the storage client lists, tells what an object is and deletes in every language
- added `features/storage-client/languages.feature`: no object passes through the platform's own program
- added `features/storage-client/languages.feature`: every SDK refuses the same object names for the same reasons
- added `features/storage-client/languages.feature`: the storage client in every language keeps its objects in a directory on a developer's machine

---

### Edge Cases

- An object's name that the store would accept and the client refuses: the rule is the client's,
  the same in every SDK, stricter than any store's, so a name that works on Garage works on
  Google Cloud Storage. A leading `/`, an empty segment, a `.` or `..` segment, and a name longer
  than the rule's limit are refused before anything is sent, naming the rule.
- A put that fails part-way: the store keeps the old object or none; the client never leaves a
  partial object readable. A stream that ends before its declared length is a failure, not a
  shorter object.
- A get whose stream the caller abandons: the connection is released; the instance does not hold
  the object.
- A list over more objects than one answer carries: the client pages for the caller, so a listing
  is a stream of entries, not an array that stops at a thousand.
- A signed URL asked for a span longer than the store accepts: refused, naming the bound (seven
  days, the lowest common bound of the stores).
- A public bucket on an installation that keeps buckets in Garage without the route for public
  reads: the bucket and its credential are made, and the bucket is `Failed` naming the operator's
  setting for the web listener, so that the setting alone makes it readable with no reissue.
- A bucket named while the service's buckets are being moved to the cloud: it is made in the
  store the service's buckets are in after the move; a service's buckets move together, as one
  move.
- A service that stops naming a bucket: the bucket, its objects and its address are kept, as the
  platform deletes nothing; the variables are withdrawn, and the status says the bucket is kept
  and no longer named.
- A named bucket whose exposure changes: from private to signed or public, the route or the
  policy is added and the variables arrive; from public to private, the address stops answering
  and URLs a page holds break, as a signed URL breaks when `exposeObjectStorage` is withdrawn.
- A supplied bucket whose credential or store is wrong: the platform cannot tell at apply, since
  it never opens a bucket; the client fails with the store's refusal on the first call, and the
  status reports the bucket as supplied and nothing more.
- A supplied bucket whose project secret is deleted: the service's next instances do not start,
  as any service whose descriptor names a missing secret does not.
- The variable derived from a bucket's name: `photos` is `ANKKA_S3_BUCKET_PHOTOS`; a name is
  lower-case letters, digits and `-`, 1 to 32 characters, so the derivation is total and two names
  cannot collide; `-` becomes `_`.
- A directory store whose directory is not writable: a service whose `service.json` declares a
  bucket does not start, and says which directory and why. One that declares none (an image run
  outside a cluster with no descriptor beside it) starts, and a call that cannot be kept fails
  `Unavailable` naming the directory.
- A `url(name)` for a name that has no object yet: answered, since it is computed; a browser that
  follows it is told the object is not there. The address of an object is its name's, not its
  existence's.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Scala SDK MUST offer a storage client on the contexts of endpoints, workflow
  steps, consumers, timed actions and agents, beside the secret store, and MUST NOT offer it on an
  entity's command context, an event sourced or key value entity's context, or a view's. A
  workflow's command handler calling it MUST be refused as its secret store call is.
- **FR-002**: The client MUST offer: keep an object from a stream of bytes of a declared length
  with a content type, and from a byte array; read an object as a stream with its content type
  and length, and as a byte array, answering nothing for an object that is not there; the HTTP
  layer MUST let an endpoint take a request body as a stream and answer a response as one, so an
  object larger than an instance's memory passes through an endpoint; delete an object, deleting nothing being no error; list the objects under a prefix as a
  stream of entries, each with the object's name, size and when it was kept, paging as needed;
  and stat an object, answering its size, content type and when it was kept. It MUST offer no
  directory, rename, move, copy, append or partial-write operation.
- **FR-003**: An object's name MUST follow one rule, declared once and read by every SDK through
  a fixture under `protocol/fixtures/`: UTF-8, 1 to 1024 bytes, segments separated by `/`, no
  leading `/`, no empty segment, no segment `.` or `..`. A name that breaks it MUST be refused
  before any request is made, naming the rule.
- **FR-004**: Every call MUST block the calling thread until the store has answered and MUST throw
  `CommandError`: `BadRequest` for a name or an argument that breaks a rule; `Unavailable` when the
  store cannot be reached, when the service has no bucket, naming `provisionObjectStorage`, or
  when the service has a bucket and no storage credential to sign with, naming
  `objectStorageCredential`; and
  `Internal` for a store's answer the client cannot read. A failed keep MUST leave no partial
  object readable.
- **FR-005**: The runtime's client MUST speak S3 over the JDK's HTTP client, adding no dependency,
  configured from `ANKKA_S3_ENDPOINT`, `ANKKA_S3_REGION`, `ANKKA_S3_BUCKET`, `ANKKA_S3_ACCESS_KEY`
  and `ANKKA_S3_SECRET_KEY` and nothing else, so a store the service brought through those
  variables is reached by the same client; and MUST apply the settings the platform page lists:
  path-style addressing, the given region, checksums only where an operation needs one, and
  and no request for an object's versions, so Google's version listing never arises.
- **FR-006**: The client MUST make a signed URL for reading one object, and one for keeping one
  object, for a span the caller gives up to seven days, against `ANKKA_S3_PUBLIC_ENDPOINT`, by
  computation alone. A service without that variable MUST be refused one with a reason naming
  `exposeObjectStorage`.
- **FR-007**: The descriptor MUST accept `buckets`, a list of named buckets beside the unnamed one,
  projected and declared together as `provisionObjectStorage` is. Each entry MUST have a `name`
  (lower-case letters, digits and `-`, 1 to 32 characters, unique in the list, and such that the
  bucket's name in the store keeps within the store's limit) and MUST be either made by the
  platform, with an `exposure` of `private` (the default), `signed` (with `origins`, as
  `objectStorageOrigins`) or `public`, or `supplied`, giving the store's endpoint, region and
  bucket and the project secret holding its credential. `ServiceSpec.problems` MUST refuse a
  repeated or malformed name, `origins` on a bucket not `signed`, `exposure` on a supplied bucket,
  and a name whose derived bucket name breaks the store's limit, naming each. `buckets` without
  `provisionObjectStorage` MUST be accepted: a service may have only named buckets.
- **FR-008**: For every named bucket the platform makes, it MUST make the bucket, named from the
  project, the service and the bucket's name, in the store the service's buckets are in; MUST
  grant the service's one storage credential on it, so that the credential reaches every bucket
  the platform made for the service and no other service's; and MUST give the service
  `ANKKA_S3_BUCKET_<NAME>`. For a `signed` bucket it MUST do what `exposeObjectStorage` does for
  the unnamed one: route it, set its CORS rule from its `origins`, and sign against the one
  `ANKKA_S3_PUBLIC_ENDPOINT`. For a `public` bucket it MUST make every object readable from the
  internet without a signature at an address the platform derives, and give the service
  `ANKKA_S3_PUBLIC_URL_<NAME>`. For a supplied bucket it MUST make nothing and MUST give the
  service `ANKKA_S3_ENDPOINT_<NAME>`, `ANKKA_S3_REGION_<NAME>`, `ANKKA_S3_BUCKET_<NAME>`,
  `ANKKA_S3_ACCESS_KEY_<NAME>` and `ANKKA_S3_SECRET_KEY_<NAME>` from the entry and its secret. A
  private bucket and the unnamed bucket MUST stay as 034 and 039 leave them: nothing in them
  readable without a signature.
- **FR-009**: 039's FR-009 is narrowed: public access prevention is enforced on every bucket the
  platform makes in Google Cloud Storage except a `public` one, which MUST be readable by anyone
  and nothing more. The cloud provider's bucket request MUST say which exposure it makes.
- **FR-010**: The platform MUST never delete a named bucket or its objects; a descriptor that
  stops naming one MUST keep the bucket and withdraw its variables, and the status MUST say so. A
  move of a service's buckets MUST move every bucket the platform made for it, as one move. The
  status MUST name each bucket, its exposure, its store and its address when it has one, and
  whether it is supplied, and the console MUST show them beside the unnamed bucket.
- **FR-011**: The client MUST offer each named bucket as `context.storage.bucket(<name>)` with the
  operations of FR-002, signed URLs as FR-006 on a `signed` bucket, and on a `public` bucket
  `url(name)`, the base from `ANKKA_S3_PUBLIC_URL_<NAME>` and the object's name, by computation
  alone. A name the descriptor does not give MUST be refused naming the names it does; a signed
  URL on a bucket not `signed`, or `url` on one not `public`, MUST be refused naming the bucket's
  exposure. `ANKKA_S3_PUBLIC_URL_<NAME>` MUST be whatever the installation serves public objects
  at: the route under the store's hostname on Garage, Google's address on Google Cloud Storage,
  or a content delivery network's when the installation names one.
- **FR-012**: A service with no `ANKKA_S3_*` variable MUST be given a client over a directory,
  `.ankka/storage/` under the working directory unless `ANKKA_STORAGE_DIR` names another, one
  subdirectory per bucket, and MUST refuse to start when it cannot be written and the service's
  `service.json` declares a bucket, naming it; a service that declares none MUST start, and a call
  that cannot be kept MUST fail `Unavailable` naming the directory. Its
  signed URLs and its public bucket's addresses MUST point at the service's own HTTP server, at a
  path the runtime reserves, and MUST be served there: a read of a signed or public address
  answers the object, a keep through a signed URL keeps it, and an expired or unsigned address to
  the service's bucket is refused.
- **FR-013**: `AnkkaTestKit` MUST boot the service against a store in memory, offer the test the
  same client to seed and read with, keep objects across `restartService()`, and drop them when
  the kit stops. Its store MUST answer the unnamed bucket and every bucket the service's
  descriptor names, read from the `service.json` beside the service when there is one, and MUST
  let a test declare buckets and their exposures itself when there is not.
- **FR-014**: The Python, TypeScript and Rust SDKs MUST each offer the client with the same
  operations, names and errors, with the directory fallback of FR-012 and an in-memory store in
  their testkits. Python and TypeScript MUST be configured from the same variables, signing and
  sending their own requests, and no object or part of one MUST pass through the sidecar's
  protocol on the client's own calls. On a developer's machine their directory store's addresses
  are served as any route of theirs is, through the sidecar, so a keep through a signed URL there
  carries at most 4,000,000 bytes; the limitations page says so. Rust MUST reach the runtime's own client through one host import, since a
  module has no other network, carrying a whole object of at most 4,000,000 bytes and refusing a
  larger one naming the bound. The conformance suite MUST check the name rule and the error
  codes against the fixture of FR-003 in all four.
- **FR-015**: The docs MUST gain `docs/build/object-storage.md` beside `secrets.md`, saying what
  the client is and is not, with compiled samples; the platform page and the descriptor reference
  MUST gain named buckets, their exposures and supplied buckets; the limitations page MUST drop "No storage client in the SDKs" and
  say instead that the client offers no directories, rename, append or partial writes, and that a
  module's put and get carry a whole object; and the rendered skills MUST say the same.

### Key Entities

- **Storage client**: what a service reaches its bucket through, one per service, on the contexts
  that may block. Holds no state of its own.
- **Object's name**: the key an object is kept and read under; `/` inside it means nothing to the
  store and everything to a listing by prefix.
- **Named bucket**: a bucket the descriptor's `buckets` list names beside the unnamed one, with a
  name the client reaches it by; made by the platform in the service's store, or supplied by the
  user from another; never deleted by the platform; moved with the service's other buckets.
- **Exposure**: how a bucket the platform made is reached: `private`, inside the installation
  only; `signed`, from the internet through URLs the service signs, with the origins a browser
  may send from; `public`, every object readable from the internet without a signature.
- **Supplied bucket**: a named bucket the user made and configured themselves, whose entry gives
  its store and the project secret holding its credential; the platform makes nothing for it and
  reports it as supplied.
- **Public address**: the base a public bucket's objects are read at, derived by the platform,
  given as a variable per bucket, possibly a content delivery network's.
- **Store**: what stands behind the client: the installation's object store through S3, a
  directory on a developer's machine, or memory in a test.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: One service, unchanged, round-trips an object through the client in the testkit,
  on a laptop with no variables, against Garage in the k3s suite and against Google Cloud Storage
  where that suite runs.
- **SC-002**: A 2 GiB object passes through an endpoint's keep and read with the instance's heap
  bounded at 512 MiB.
- **SC-003**: A service's dependency list gains nothing: the runtime's client adds no library.
- **SC-004**: An entity's handler naming `storage` does not compile; a workflow's command handler
  calling it is refused at runtime.
- **SC-005**: An object in a public bucket is read from the host with `curl` and no signature;
  the same request to the service's bucket is refused, on Garage and on Google Cloud Storage.
- **SC-006**: A page that reads a public address and keeps through a signed URL works against a
  service on a laptop and against one deployed, with no change to the page or the service.
- **SC-007**: Each SDK's conformance run refuses the same names and answers the same codes as the
  Scala client, from one fixture.
- **SC-008**: The limitations page no longer says there is no storage client, and `just docs`
  compiles every sample on the new page.

## Assumptions

- The input's "publicly readable prefixes" became named buckets with an exposure each
  (clarified 2026-10-19), because neither Garage nor a bucket under 039's public access
  prevention can make part of a bucket readable; see the Context. A service that wants private,
  signed and public objects names a bucket for each and chooses per object by which it keeps it
  in.
- The storage credential reaching every bucket the platform made for a service keeps 034's
  isolation: it reaches that service's buckets and no other service's. A supplied bucket's
  credential is the user's, in a project secret, and the platform never holds it.
- A supplied bucket's entry carries its store's endpoint, region and bucket name in the
  descriptor and names a project secret for the key, so the descriptor stays free of secrets as
  `secretKeyRef` keeps it today; the plan decides the exact shape and whether the five variables
  per supplied bucket are also accepted in `env` as the unnamed bucket's are.
- A bucket's name in the store is `<project>.<service>.<name>` on Garage and the cloud provider's
  derivation with the name appended on Google Cloud Storage; the unnamed bucket's names are
  unchanged.
- A public bucket is readable on Garage through a route the platform renders under the store's
  hostname, serving the bucket's objects unsigned; the plan decides whether that is Garage's
  website mode behind a rewrite or a reader of the platform's own.
- The content delivery network is the installation's to put in front of Google's address, and
  the installation's to name in `ANKKA_S3_PUBLIC_URL`'s source; this feature carries the variable
  and no provider's CDN resource.
- Streaming is as far as the hosting allows: a Scala, Python or TypeScript client streams; a
  module's host import carries a whole body of at most 4,000,000 bytes, so the Rust client's put
  and get hold the object in the module's memory, and the limitations page says so.
- The directory store keeps a content type and a kept-at time beside each object, so stat and a
  listing answer on a laptop what they answer in a cluster.
- The reserved path the service serves signed and public addresses from on a laptop is served
  only when the store is a directory; a deployed service serves nothing there.
- Signed URL spans are bounded at seven days, the lowest of the stores' bounds, and default to
  fifteen minutes when the caller gives none.
- The testkit's in-memory store is per kit, so two kits in one JVM do not see each other's
  objects, as they do not share a database.

## Dependencies

- **034-object-storage** and **039-gcs-object-storage**: the bucket, the credential, the
  variables, the public endpoint and the CORS rule this feature's client reads and this feature's
  public bucket sits beside. Hard dependencies, both released.
- **044-cloud-provider**: the bucket request the public bucket extends with its kind, and the
  identity and HMAC key the credential is granted through on Google Cloud Storage.
- **023-secret-store** and **038-secret-store-backends**: the contexts the client joins and the
  pattern of one interface, one fixture and one test double across SDKs.
- **030-wasm-request-and-clock**: the outbound call and the `config` import a module's client is
  built on; without them the Rust client cannot reach a store.

## Open Questions

- Whether a public bucket's objects on Google Cloud Storage are read at Google's address or at a
  CDN's is the installation's choice; whether the platform ever provisions the CDN itself is a
  later feature, as 034 left Google Cloud Storage to 039.
- Whether `ankka local web`, the proxy and a web-hosted service's program (which has the variables
  and no SDK) want anything from this feature beyond the public bucket; the plan says, and the
  default is nothing.
