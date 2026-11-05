# Review: feat(042): personal data erasure by crypto-shredding per data subject

**Verdict:** Request changes
**What it does:** It adds `Personal` in all seven SDKs. Its codec seals each personal field under a per-subject key fetched from a new standalone keyring component. Erasure is a control plane request with holds, overrides, a two-copy erasure log, a sweep and a certificate. On an erasure the keyring destroys the subject's key and pushes a destroyed notice to every instance. Each instance then drops the key, redacts its view rows and lookup tokens, ends the subject's agent sessions, runs the service's erasure handler and acknowledges. The CLI, console and MCP server carry the request in full.
**Should it exist:** yes. The append-only journal, topics and backups cannot otherwise forget a person (GDPR Article 17). Putting the cryptography in the codec, so every store passes through one place, is the right layer, and the spec argues it well.
**Read:**
- **Read in full:** `modules/core` (`Personal.scala`, `Keyring.scala`, `Serializer.scala`, `KeyringWire.scala`, `Serializers.scala`); `modules/runtime/.../keyring/` (`KeyringClient`, `KeyCache`, `KeyringSettings`, `KeyringSupervision`, `ErasureDuties`, `ErasureReplay`, `ErasureHandlerHost`, `ViewRedaction`, and `RemoteKeyStoreClient`'s notices half); `LookupTokens.scala`; the `ViewStore.redact` diff; the `EventSourcedEntityHost` diff; `sdk/PersonalFields.scala`; `keyring/` (`KeyStore`, `Admission`, `KeyringServer`, `KeyringService`, `Notices`, `ErasureLogReplay`, `Main`); `docs/reference/limitations.md` (the diff, and lines 44-52 around it); the spec, plan and tasks; research.md's *Found during implementation*.
- **Read by three parallel reviewers**, each finding confirmed against HEAD:
  - The Python, TypeScript and Go SDKs.
  - The sidecar, `protocol/`, the Rust crate, and Java and Kotlin.
  - `controlplane/`, `controlplane-api/`, `cli/`, `console/`, `operator/`, `crd/`, `kustomization/`, `build.sbt` and `.github/`.
- **Context opened outside the diff:** `.github/workflows/release.yml`, `Rendering.scala` (`StorageEnv.softDelete`), and the KeyringClientSuite and KeyringOutageSuite cases.
- **Skipped:**
  - The generated `*.pb.go`, `uv.lock`, and the fixture copies under each SDK.
  - The skill mirrors under `marketplace/` and `ankka.g8/`, which repeat `docs/`.
  - The golden file `operator/.../personal-service.txt`.
- **Read only lightly, worth a second reviewer:**
  - The agent module's sealing (`Sealing.scala`, `SessionMemoryEntity`, `TaskEntity`).
  - The k3s suites (`ErasureClusterFeatures`, `ErasureRestoreClusterFeatures`).
  - The console's `project.tsx` and the CLI's `Main`/`Output`.
- **Needs specialist review:** a cryptography reviewer for the envelope and the key hierarchy, and a database reviewer for the keyring schema and the redaction SQL.

**Size:** 619 files, about +43,946 −887 hand-written lines across 109 commits. That is far past the point where line-by-line defect-finding holds up. The spec decided on one pull request, so there is no release with personal fields but without erasure. That decision can stand while review still goes in slices. The slices this review was cut along would be acceptable:
1. Core and runtime.
2. The keyring.
3. The control plane and its surfaces.
4. The process SDKs and the sidecar.
5. Rust and Wasm.
6. Agents.

The findings below are dense in the parts read in full, which suggests the lightly read parts hold more.

## Blocking

- `.github/workflows/release.yml:150` and `:178` — issue (blocking): the `ankka-keyring` image is never published.
  - `IMAGES` and the `Docker/publish` step list every other platform image but not this one.
  - The cloud overlay names `ghcr.io/thinkmorestupidless/ankka-keyring` at the release tag. So an installation that turns erasure on after a release sits in ImagePullBackOff, and erasure cannot be turned on anywhere but kind.
  - Suggestion: add `ankka-keyring` to `IMAGES` and `keyring/Docker/publish` to the publish step, and check the multi-arch manifest loop at `:202` and `:233` too.
  - Resolved: `ankka-keyring` is in `IMAGES` and the `Docker/publish` step, and the manifest loops read `IMAGES`, 6850521f5

- `sdks/typescript/src/personal.ts:65-67` — issue (blocking): `Present.toJSON()` returns `{subject, value}`, so `JSON.stringify(event)` prints the plaintext.
  - This breaks FR-029: "`Personal`'s textual form in every SDK MUST be `Personal(<subject>)` and nothing else". It also breaks SC-009, since logging a whole event is exactly the case that SC names, and `JSON.stringify` is how TypeScript code logs an object.
  - The codec never uses `toJSON`, because the edge form comes from the schema. The only other caller is the agent tool result in `server/agent.ts:99`.
  - `personal.test.ts:285` checks only `util.inspect`, so it passes with this leak.
  - Suggestion: make `toJSON` return `{ subject }` (or the `Personal(<subject>)` string), encode tool results through their schema, and add a `JSON.stringify` assertion.
  - Resolved: `toJSON` gives `{subject}` only; tests stringify a value holding a personal field and send one through a tool result, 2507a55db

- `sdks/python/src/ankka/keyring.py:57,164` and `sdks/typescript/src/keyring.ts:2-3,41,170` — issue (blocking): the process caches hold every key with no expiry, no bound and no outage bound, and treat another project's keys the same way.
  - No notice of another project ever reaches a process. The JVM bounds that case with `foreign-ttl` (30 s), but these two SDKs have no equivalent.
  - So a Python or TypeScript consumer in project B, reading project A's subjects under a decrypt grant, keeps decrypting after A erases the subject or revokes the grant, until the process restarts or its stream reconnects.
  - This breaks FR-020, FR-022, SC-004, FR-027 and `other-projects.feature`'s revoked-grant scenario. The TypeScript header says "cached for the life of the process" outright.
  - The same caches also hold a read's erased answer forever (`keyring.py:164`, `keyring.ts:146,171`), so a decrypt grant made later is never honoured.
  - Suggestion:
    - Stamp each entry with its fetch time.
    - Expire own-project keys at `ttl` (5 min) and other projects' at `foreign-ttl` (30 s), and bound the size at `max-keys`.
    - Hold read-side erased answers for `foreign-ttl` at most.
    - Test each rule with an injected clock.
  - Resolved: both caches hold own keys 5 min, other projects' 30 s, at most `max-keys` (LRU), an erased read answer at most `foreign-ttl`, with an injected clock, c26febaa4 a3eac5822

- `sdks/go/keyring.go:43,89` — issue (blocking): Go holds another project's keys for the own-project lifetime (5 min).
  - With no notices from that project, a Go consumer decrypts an erased subject for up to 5 minutes, past SC-004's 60 seconds.
  - Suggestion: use `foreign-ttl` for a key whose project is not this process's own, and add a cache-lifetime test (none exists in Go).
  - Resolved: another project's key is held for `foreign-ttl`, with a cache-lifetime test under `-race`, c5622e8ab

- `operator/src/main/scala/com/thinkmorestupidless/ankka/operator/Rendering.scala:920`, `:958`, `:2338` — issue (blocking): `StorageEnv.softDelete` renders `ANKKA_S3_SOFT_DELETE_DAYS` on every service with a cloud bucket on a GCS installation, whether it is personal or not.
  - `softDeleteDays` is never 0 on GCS, so every bucket-holding service there rolls on the operator upgrade.
  - This breaks the plan's constraint "nothing rendered for a service without `personal` differs" and kubernetes.md's "never render what must not roll".
  - `RenderingUnchangedSuite` has no cloud-bucket fixture, so it passes while the property it guards is false.
  - Suggestion: gate the variable on `spec.personal` (or on an erasure handler being declared), and add a GCS-bucket fixture to `RenderingUnchangedSuite`.
  - Resolved: rendered only for `spec.personal`; a GCS-bucket fixture in `RenderingUnchangedSuite` goes red without the gate, a8bf6dc75

- `keyring/src/main/scala/com/thinkmorestupidless/ankka/keyring/Notices.scala:54-56` and `:257-312` — issue (blocking): an acknowledgement is recorded for any `seq` the instance names, and `status(seq)` groups acks by service name without the project.
  - The instance's project is admitted at hello, but nothing checks that `ack.seq` is an erasure of `hello.project`.
  - So any service of any project can write `notice_ack` rows for another project's erasure. That adds a phantom service to its status, or completes a same-named service whose expected instances have gone.
  - The control plane turns that status into completions and the erasure certificate, so one tenant can corrupt another tenant's compliance record.
  - Suggestion:
    - In `received`, refuse an ack whose `seq` is not in `applied_log` for `hello.project` (the backlog query already reads that table).
    - Filter both `notice_expect` and `notice_ack` by project in `status`.
    - Add an `AdmissionSuite` or `NoticesSuite` case with an instance of another project.
  - Resolved: an ack is refused unless `applied_log` holds its seq for the instance's project, and `status` filters by that project; a `NoticesSuite` case with another project's instances, 2af11d624

## Should fix

- `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/keyring/KeyringClient.scala:173-185` with `KeyCache.scala:38-43` — issue (non-blocking): in a real outage the outage bound (`cache.outage-bound = 15m`) cannot take effect.
  - The notices channel goes unconfirmed in an outage, so `check()` calls `dropAll()` after `notice-bound` (60 s), and `stale()` has nothing left to serve.
  - SC-010 ("keeps answering reads of cached subjects for the outage bound") therefore holds for 60 s, not 15 minutes.
  - `KeyringClientSuite`'s outage case (line 115) never calls `check()`, and `KeyringOutageSuite` runs within the first second, so neither can see this.
  - FR-020 and FR-022 genuinely pull against each other here. Suggestion: decide which wins and record it in the spec. Then either make `outage-bound` ≤ `notice-bound` a settings rule and document it, or keep stale keys only while every fetch also fails (and say why that is safe). Add a test that composes `check()` with the outage.
  - Resolved: the person decided the notice bound wins (FR-020 amended, 6e0e2e665); the outage bound defaults to 60 s, counts from when the keyring was last heard, and settings refuse one above the notice bound; a test composes `check()` with an outage, b75e3549f

- `modules/runtime/.../keyring/KeyringClient.scala:43,144` with `RemoteKeyStoreClient.scala:141-142` — issue (non-blocking): every instance starts with `lastSeq = 0`, so its hello asks for the project's whole erasure history on every start.
  - `ErasureDuties` then reruns, for every erasure ever applied:
    - a `jsonb_path_exists` scan of every view table;
    - the agent duty;
    - the erasure handler, on whichever instance holds the singleton, beyond FR-015's 30-day window ("and not after").
  - At a casino's volume of erasures, start-up cost grows without bound.
  - Separately, `flush` advances `acked` to the highest acked seq (`:186`), so a notice whose duties failed is skipped by the next hello once a later one succeeds.
  - Suggestion: start the hello from what the service's own database records as carried out. `ankka_erasure_replay` already holds a line's high-water mark, so store the seq there per line. Advance `acked` only over a contiguous prefix.
  - Resolved: the hello starts from `carried_through` in `ankka_erasure_replay`, advanced only over a contiguous prefix (`CarriedThrough`), d0cc2c7f8

- `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ViewStore.scala` (`redact`, `lookup = NULL`) — issue (non-blocking): redacting one subject from a row drops the row's whole `lookup` column, including other subjects' tokens.
  - The spec's edge case "One event, two subjects" says erasing one leaves the other's fields readable. Here the other subject's row stops matching declared lookup queries until the projection happens to rewrite it.
  - Suggestion: drop only the paths whose envelope belongs to the erased subject (`lookup - <paths>`). Add a two-subject row to `ViewRedactionSuite`.
  - Resolved: only the erased subject's lookup paths are dropped; a two-subject row in `ViewRedactionSuite`, 5a57a987e

- `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/LookupTokens.scala` (`forRow`, `.distinctBy(_._1)`) — issue (non-blocking) (if-minor): a lookup field inside a collection (`contacts[*].email`, which `PersonalFields` derives) keeps only the first element's token, so a lookup query for any other element finds nothing.
  - When `found.size != sealedFields.size`, every token is silently dropped.
  - Suggestion: refuse `lookup` on a `[*]` path at registration, or store a token array per path. Fail loudly on a size mismatch.
  - Resolved: a lookup on a `[*]` path is refused at registration, and a size mismatch refuses the write, bfeca7eb4

- `keyring/.../Notices.scala:258-311` with `controlplane/.../erasure/ErasureApplication.scala:129-140` — issue (non-blocking): a service counts toward completion and finality only if it was connected when the notice was written.
  - A paused or crash-looping service, or one with no channel, is simply absent, and the request is certified final without it.
  - `Rendering.scala:340` renders `ANKKA_KEYRING_ADDRESS` only for `spec.personal`. A service whose only part is an erasure handler (US7's KYC bucket service) therefore never connects, its handler never runs, and the certificate says final.
  - Suggestion:
    - Finalise against the project's service rows, or list on the certificate the services that did not complete.
    - Render the keyring address for any service that declares an erasure handler.
    - Say this in `docs/build/personal-data.md`.
  - Resolved: a request waits on, and the certificate lists, every personal service of the project; a service with an erasure handler declares `"personal": true`, and one that does not is refused at start, f89210cce db77f360d

- `controlplane/.../erasure/ErasureRoutes.scala:55-100` with `ProjectEntity.recordErasureRefused` — issue (non-blocking): any service certificate in the installation can make any project persist an unbounded number of `ErasureAskRefused` events.
  - A project that does not exist answers 404, not 403, which confirms project ids.
  - Suggestion: record one refusal per caller and subject per window, and answer 403 for an unknown project.
  - Resolved: one refusal recorded per caller and subject an hour, and 403 for an unknown project, 10f36350a f89210cce

- `controlplane/.../domain/events.scala:345` — todo: `ErasureAskRefused` is not pinned in `EventCompatibilitySuite`, which control-plane.md requires for every new control plane event. Add its fixture.
  - Resolved: `ErasureAskRefused` pinned in `EventCompatibilitySuite`, with `ApplyFailed` and `RanQuietly` fixtures, f89210cce

- `controlplane/.../erasure/ErasureApplication.scala:168-178` with `ErasureRequestEntity.scala:155` — issue (non-blocking): a handler that keeps failing records a `HandlerRan` event every 15 minutes for 30 days.
  - That is about 2,880 events per service, each one appended to the entity's state and to the audit.
  - Suggestion: dedupe on an unchanged error, as `applyFailed` already does.
  - Resolved: a run with an unchanged error is not recorded again, f89210cce

- `controlplane/.../erasure/ErasureApplication.scala:156-159` — issue (non-blocking): `known(service)` folds the request-wide `lastRunAt` into every service's high-water mark.
  - One service's quiet run, recorded first, hides another service's earlier failing run, which is then never audited (FR-016).
  - Suggestion: keep a last-run time per service.
  - Resolved: quiet runs are kept per service, f89210cce

- `controlplane/.../erasure/ErasureRoutes.scala:172-193` — issue (non-blocking): FR-014 makes a held request "withdrawable and replaceable by its filer or a member". Withdraw and replace need a principal, and the protocol has only `AskErasure`, so the service that filed a held request (US8's players service) cannot withdraw or replace it.
  - Suggestion: admit the filing service on both routes, or amend FR-014.
  - Resolved: the filing service may replace and withdraw while it holds the grant, f89210cce

- `sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/ClientLogic.scala:663-669` and `:791-798` — issue (non-blocking): when the keyring cannot be reached, a process or module receives `INTERNAL` instead of `UNAVAILABLE`.
  - The cause: `KeyringUnavailable` is a `RuntimeException`, not a `CommandError`, so it falls through to the `NonFatal` case.
  - The proto comment and Rust's `from_host` both expect `UNAVAILABLE` with `personal=unavailable`.
  - Suggestion: add `case e: KeyringUnavailable => refused(error(e.error))` in both places, plus a `ClientPersonalSuite` case with the kit's keyring in an outage. That suite also lacks a `Refused` case.
  - Resolved: `KeyringUnavailable` answers UNAVAILABLE with `personal=unavailable` in both places; `ClientPersonalSuite` cases for an outage and for Refused, 4a43abd4b

- `sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/ProcessNotices.scala:551,591-592` — issue (non-blocking): when a stream reopens under the same instance name, the old stream's termination deregisters the new one.
  - The cause: `close(instance)` removes by key, not by queue.
  - Python reconnects under the same instance name, so the process can end up with a live stream that never hears another notice, while `notify` reads "no stream" as done. That breaks FR-022.
  - Suggestion: `streams.remove(instance, queue)`, and settle waiters only on that removal. Add a test that opens twice and then notifies.
  - Resolved: streams are removed by (instance, queue) and only that queue's waits settled; open-twice-then-notify test, fce86acf8

- `sidecar/.../ProcessNotices.scala:561-582` — issue (non-blocking): nothing bounds the wait for a process's acknowledgement.
  - A hung process leaves `ErasureDuties.run` pending forever, so the service never completes.
  - Suggestion: give each instance a deadline at `notice-bound`, then fail its stream (which every SDK answers with `drop_all`) and settle it.
  - Resolved: each notice has a deadline at `notice-bound`, past which the stream fails and the wait settles, fce86acf8

- `modules/runtime/.../keyring/KeyringClient.scala:50-59`, Python `_fetch_all`, TypeScript `#ensure` (`keyring.ts:170`), Go `prefetch` (`keyring.go:123`) — issue (non-blocking): if a fetch is in flight when the destroyed notice arrives, it puts the old key back after the drop.
  - The JVM then holds it as fresh for `ttl` (5 min), and the process caches without expiry hold it for good.
  - The window is narrow, but it is exactly the case FR-022's bound exists for.
  - Suggestion: keep a drop generation, and discard any answer whose fetch began before the latest drop of its subject or of the whole cache.
  - Resolved: a drop generation discards an answer whose fetch began before the latest drop, in the JVM and in each process SDK, 06a30a46c c26febaa4 a3eac5822 c5622e8ab

- `sdks/python/src/ankka/storage.py` (`parse_versions`), `sdks/typescript/src/storage.ts:1093`, `sdks/go/storage_s3.go:536` — issue (non-blocking): a listed version id of `null` (an object written before versioning was enabled) is deleted without a version id.
  - On a versioned S3-compatible store that only adds a delete marker, so the object stays recoverable and is left out of the count.
  - Suggestion: send `versionId=null` explicitly.
  - Resolved: a `null` version is deleted as `versionId=null` in all three SDKs, 43b5725f6; and they read Google's `Generation` listing too, 9d69309d0

- `docs/reference/limitations.md:48-50` and the new *Personal data and erasure* section — issue (non-blocking): the honest list now contradicts the code three times.
  - "The installation does not report erasure … which the platform does not yet provide" is false since FR-033.
  - "A store's soft-delete window is reported only when the service is told it … the platform does not set it" was written before `97a183377`, which renders it.
  - "the relay … is not defined yet" contradicts the `KeyringRelay` SPI this change ships.
  - Suggestion: rewrite all three to say what is true today.
  - Resolved: the three entries rewritten to what is true, 82e0f135a

- `Description` / `specs/042-personal-data-erasure/spec.md` — issue (non-blocking): several MUSTs are not implemented and are recorded only in `limitations.md` and research.md, while tasks.md marks all 106 tasks done and the spec says "one pull request with every story, requirement and SDK".
  - **FR-010 and FR-008:** Java and Kotlin have no erasure handler and cannot ask for an erasure.
  - **FR-027:** Java, Kotlin, the process SDKs and Rust seal a field read from another project under their own project's key. An erasure in the producing project then never reaches that copy, which is the privacy property story 5 exists for.
  - **FR-032:** a follower behind the sidecar is not routed. It reads with its own installation's keyring, which holds no key, so it reads `Erased` rather than retrying, against the outage edge case's "never decodes as Erased".
  - Suggestion: for each one, either implement it or have the person decide and amend the spec. This is expected before merge.
  - Resolved: the person decided to build all of them here (research.md, 2026-11-05). FR-008/FR-010 in Java and Kotlin, 7026eadf9; FR-027 in Java and Kotlin b311b6f71, Python/TypeScript/Go ee24d04dd, Rust and modules 4a43abd4b; FR-032 in the sidecar 4a43abd4b and the process SDKs (protocol 1.23's home), 19b238ab8..fa3dcdd8b. Left, recorded as a follow-up: a follower encodes a command's reply under its own installation's keyring, in every language, so a personal field in a reply is sealed with a key the follower's keyring makes and an erasure at the home does not reach it.

- `.claude/rules/sdk-go.md` — todo: it still says no SDK outside Scala has a personal type, and lists `erasure/languages.feature` as unbacked. Point the row at the `personal.*` conformance cases.
  - Resolved: the row names the `personal.*` conformance cases, 39eebeda9

- `controlplane/.../erasure/KeyringAdmin.scala:67-71` — suggestion (if-minor): `Door.call` builds an `HttpClient` per call, and the sweep calls it for every open request every pass. Build one per `Door`, rebuilt on rotation.
  - Resolved: one client per SSL context, rebuilt on rotation, 41f991345

- `controlplane/.../erasure/ErasureSweep.scala:68,91` with `ErasureRequestsView.scala:163-172` — suggestion (if-minor): every pass runs unindexed jsonb scans and filters the 30-day window in memory. A request that never finalises stays in `open` and is polled forever. Filter `appliedAt` in SQL, and stop polling past the window.
  - Resolved: the window is filtered in SQL and polling stops at its end, 7195124de

## Nits

- `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/Personal.scala:118` — nitpick (non-blocking): `PersonalScope.within(manifest, nonce)` is public API that lets any caller fix the GCM nonce. A nonce used twice under one subject key breaks GCM. Make the `nonce` overload `private[ankka]` and give the fixture suites a test-only door.
  - Resolved: the nonce overload is `private[ankka]`, 9fca7bf81
- `Personal.scala:32` and the subject-rule errors in the Python, TypeScript and Go SDKs — nitpick (non-blocking): the refusal repeats the refused value. A caller who passes an email as the subject puts the email in an error message, which FR-029 covers. Name the rule, not the value.
  - Resolved: the refusal names the rule, in Scala and the three SDKs, 9fca7bf81 d2f5078df
- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/mcp/AnkkaTools.scala` — nitpick (non-blocking): `erasure_file` is annotated `idempotent = true`, but two calls before the request is applied make two requests. Mark it `false`.
  - Resolved: `idempotent = false`, and `McpServerSuite` asserts the hint, b34cffa21
- `sidecar/.../ClientLogic.scala` — nitpick (non-blocking): fully qualified `com.thinkmorestupidless.ankka.core.*` names repeat through the personal block. Imports would read better. `EventSourcedEntityHost` does the same.
  - Resolved: imports, 4a43abd4b
- `sdks/go/keyring.go:120-121` — nitpick (non-blocking): a full cache is cleared all at once, which causes a burst of refetches. Evict one entry.
  - Resolved: a full cache evicts one key, c5622e8ab
- `specs/042-personal-data-erasure/spec.md` (story 7, FR-026) — nitpick (non-blocking): the prefix is still given as `subjects/player/8c1f…/`. The code and docs use the escaped `subjects/player%2F8c1f/` that research decided on.
  - Resolved: the escaped prefix, 509504579

## Questions

- `keyring/src/main/scala/com/thinkmorestupidless/ankka/keyring/ErasureLogReplay.scala:89` — question: replay starts after `max(erasure_seq)` in `applied_log`.
  - If the control plane can order two erasures concurrently, a keyring database restored to a point holding seq 6 but not 5 skips 5, and that subject's key comes back.
  - Is the order serialised (one sweep, one at a time)? If yes, say so where `lastApplied` is read. If no, replay every log entry missing from `applied_log` rather than everything above a high-water mark.
  - Answered: not serialised (a destroy that met an unavailable keyring is retried later); replay now applies every log entry missing from `applied_log`, db84a30c0
- `keyring/.../KeyStore.scala:83-125` — question: the comment says the vacuum leaves "no dead row" that keeps the key.
  - `VACUUM` frees the tuple but does not overwrite the page. The WAL, and CNPG's WAL archive and base backups, keep the wrapped key, and the KEK and root key are what unwrap it.
  - Replay covers a restore. Does `docs/platform/erasure.md` say plainly that whoever holds a keyring backup and the root key can recover an erased subject's key until that backup ages out? If not, it should, and the comment should claim less.
  - Answered: no; `erasure.md` now says a keyring backup or WAL archive plus the root key can recover an erased key until it ages out, and the comment claims less, 8bd7fd3c1
- `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Ankka.scala:586-606` — question: the remote (process and module) entity hosts are not wrapped in `KeyringSupervision`, so a replay that needs an uncached key during an outage rejects the command and drops the session. Is the next command's re-open meant to be the retry R4 describes? If so, the docs should say so.
  - Answered: yes; the runtime never decrypts a remote host's journal, so the next command's re-open is R4's retry; `limitations.md` and research say so, 7adfd4eb7
- `sidecar/.../ClientLogic.scala:230` — question: `LocalDate.parse(text.take(10))` accepts `2026-01-01garbage` and drops any time or offset. Is not-before meant to be a date only? Then refuse anything longer than 10 characters.
  - Resolved: exactly a date, or an instant at midnight UTC; anything else is BAD_REQUEST, 4a43abd4b
- `sidecar/.../RemoteErasureHandler.scala:624` — question: the process's erasure handler is sent `Metadata.empty`, so its calls lose the trace and the caller. sidecar.md's trap says anything the runtime asks a process to do carries the handler's metadata, and the Wasm side stamps it. Should this too?
  - Resolved: it sends `Trace.outbound(Metadata.empty)`, 095a78932
- `controlplane/.../erasure/ErasureRequestEntity.scala:38` — question: `Replaced(notBefore = None)` leaves the request held with no date, so it lapses on the next pass. Is replacing a hold without a date meant to release it? If not, refuse it.
  - Resolved: not meant to release it; a replacement with no date, or a date passed, is refused, f89210cce
- `features/erasure/holds.feature:36-45` against `ErasureRoutes.scala:113` — question: the scenario reads "whoever asked for it asks for an erasure request", but it is bound to `PUT …/{rid}`. Meanwhile a second `POST` for a subject with a held request makes a second held request. Which behaviour is meant?
  - Resolved: the person decided a second ask is refused with 409 naming the held request; the scenario binds the replacement to PUT, and a new scenario holds the refusal, 04902402a
- `controlplane/.../erasure/ErasureRoutes.scala` (`certificate`) — question: the certificate carries the hold and override reasons, which are free text a member typed, while FR-016 says it holds nothing personal. Should the docs tell members to keep personal data out of a reason?
  - Answered: yes; `erasure.md` says to write why, never who, 82e0f135a
- `keyring/.../Admission.scala:74-77` and `:87-89` — thought: a decrypt grant on one topic admits keys, or decryption, for every subject of the producing project, since an envelope does not carry its topic. That matches FR-018 as written. The docs should say that `decrypt` is project-wide in effect.
  - Answered: `erasure.md` and the `Admission` comment say a decrypt grant is project-wide in effect, 8bd7fd3c1

## Praise

- `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/Personal.scala:153-200` — praise: the envelope is tight.
  - AES-256-GCM with a fresh CSPRNG nonce.
  - Length-prefixed associated data binding subject, project and manifest, so a spliced envelope fails as corrupt, never as erased.
  - `Destroyed`, `Absent`, `Refused` and `Unavailable` are kept apart all the way through `KeyringUnavailable`.
  - The Python, TypeScript and Go copies match it byte for byte against an independent generator.
- `controlplane/.../erasure/ErasureApplication.scala:70-91` with `ErasureLog.scala:72-88` — praise: the entity refuses `keyDestroyed` without a `LogWritten`, which makes "an erasure whose log write failed destroys no subject key" hold by construction. Every step is safe to rerun.
- `keyring/.../KeyStore.scala:48-80` — praise: create and destroy share one per-subject advisory lock and check the tombstone first, so FR-005's "once, whichever instance writes first" and "a tombstoned subject is never minted again" are one transaction, not a convention.
- `modules/runtime/.../EventSourcedEntityHost.scala` (`encodeEvents`) — praise: sealing before persisting turns a keyring refusal into the command's answer rather than a journal failure that times out. The new runtime.md trap explains why, so the next host will copy it.
- `sdks/rust/ankka/src/abi/imports.rs` — praise: each new import has its own link block, so a module with no personal field still runs on an older runtime, exactly as wasm.md's trap asks.
- `RemoteEntitySuite` (`personal.sidecar-reads-nothing`) — praise: it sends the sidecar bytes that are not JSON, which proves FR-006 by construction. It is a check that would go red if the property were false.
- `controlplane/.../erasure/ErasureRoutes.scala:197` — praise: override is owner-only on the server, not merely hidden in the console. The keyring's network policy and the gateway route expose only `POST /v1/decrypt`.

## Out of scope

- note: `ProjectGrants.fromDirectory` and `KubernetesGrants` re-read grants on each ask. This is fine for a revocation, but each `POST /v1/keys` from another project's consumer is a read of the file or the API. If the keyring is load-tested, consider an interval cache with the five-minute resync bound the operator already has.

## Checks applied

- **General checklist groups applied:** Correctness, Design and scope, Error handling, Concurrency and state, Security, Data and migrations, Performance, Tests, Observability and operability, API and compatibility, Dependencies and build, Documentation, Commit and PR hygiene. None was skipped.
- **Commit and PR hygiene:**
  - All 109 commit headers parse as Conventional Commits, scoped `042` and under 72 characters.
  - The branch is up to date with `origin/main`.
  - There is no PR yet, so the description and CI were not checked against `.github/pull_request_template.md`. The PR title should be `feat(042): …`.
- **Language skills:**
  - `pr-review-scala`: concurrency, codecs, totality, error handling, tests and sbt.
  - `pr-review-python`, `pr-review-typescript`, `pr-review-go`: the SDK caches, the codecs, the textual forms and storage.
  - `pr-review-rust`: the crate's codec and imports.
  - `pr-review-java` and `pr-review-kotlin`: the record codec and the facade.
  - No repository-local `pr-review-<language>` skills or `REVIEW.md` exist.
- **Project rules read:**
  - The root CLAUDE.md, plus the workspace CLAUDE.md files and brains.
  - Rule files: `runtime.md` (the four 042 traps), `messaging.md`, `secrets.md`, `cluster-and-tls.md`, `observability.md`, `testing.md`, `sidecar.md`, `wasm.md`, `sdk-java.md`, `control-plane.md` (its *Erasure requests* section), `kubernetes.md` and `console.md`.
  - The new traps were recorded in runtime.md, testing.md and sidecar.md, as CLAUDE.md asks. `sdk-go.md` was not updated (above).
- **Enforced by tooling and not reviewed:** formatting (`.scalafmt.conf` and the pre-commit hook), compile warnings (`-Werror`), and the commit-message hook.
- **Tests:** not run. The instructions said not to run sbt, and every suite the diff touches needs containers (Postgres, the sidecar image) or k3s. "Could this pass while false" was asked of the tests read: four suites were found that can pass while the property they hold is false (the outage-bound, `JSON.stringify`, `RenderingUnchangedSuite` and Rust host-unavailable cases above).

## Second round

# Review: feat(042): the first review's fixes (6e0e2e665...3e73b7fe5)

**Verdict:** Request changes
**What it does:** It answers the first round's 41 findings in 46 commits. The fixes cover the release image, the TypeScript textual form, the process and Go key caches, the operator's soft-delete gate, the keyring's project check on acks, the outage bound, the hello's starting point, lookup tokens, the control plane's completion, refusal, re-run and filer rules, the sidecar's errors and notice streams, and storage versions. It also builds what the spec left open: Java and Kotlin erasure handlers and asking (FR-008, FR-010), FR-027 in every SDK, and FR-032 routing behind the sidecar and in the process SDKs, on protocol 1.23's `home` fields.
**Should it exist:** yes. Each commit answers a recorded finding or a decision the person made on 2026-11-05 (research.md), and nothing else came in with them.
**Read:**
- **Read in full (the diff):** `keyring/` (`Notices`, `KeyStore`, `ErasureLogReplay`, `Admission`); `modules/runtime/.../keyring/` (`KeyringClient`, `KeyCache`, `KeyringSettings`, `CarriedThrough`, `ErasureDuties`, `ErasureReplay`, `ErasureHandlerHost`, `RemoteKeyStoreClient`); `Ankka.scala`, `ViewStore.redact`, `LookupTokens`, `View`/`KeyedView`; `core/Personal.scala`; the remote host and `Conversation`; `controlplane/.../erasure/` and `ProjectEntity`/`model.scala`; `sidecar/` (`ClientLogic`, `ProcessNotices`, `RemoteErasureHandler`, `SidecarExtension`, the wasm call site); `operator/Rendering.scala`; `release.yml`; `sdks/python/src/ankka/keyring.py` (whole file), `codec.py`, `personal.py`; `sdks/typescript/src/keyring.ts` (the cache and `#ensure`), `personal.ts`; `sdks/go/keyring.go`, `storage_s3.go`; `sdks/rust/ankka/src/personal.rs`; the Java SDK's `erasure/` package and its bridge (`Erasures.scala`, `Service.scala`, `Stores.scala`, `JsonReader`/`JsonWriter`); the Java test kit's `ServiceKit` and `InstallationsKit`.
- **Tests read:** `KeyringClientSuite`, `NoticesSuite`, `ErasureLogReplaySuite`, `CarriedThroughSuite`, `ViewRedactionSuite`, `ErasureFeatures` (the holds steps), `PersonalSuite`. `PersonalRecordCodecSuite` and the Kotlin `PersonalTest` were opened outside the diff because of a finding below.
- **Specs and docs:** the spec's diff, `limitations.md`, `holds.feature`, and the console's erasure pages, to check the awaiting services render.
- **Skipped:** the mirrors under `marketplace/` and `ankka.g8/`, the generated `*.pb.go`, the protocol copies under each SDK (checked only as copies), and the 1,026-line GCS golden fixture.
- **Read lightly:** the new Python, TypeScript and Go test files (`*_home*`, `*_origin*`, `keyring_cache*`), `ErasureJavaSuite`, `ClientPersonalSuite`, and the TypeScript and Go codec changes for FR-027.
- **Still needs specialist review**, as in round one: cryptography and database.

**Size:** 194 files, +7,745 −879 hand-written lines. That is over the split threshold. But this is a fix round, and each commit is scoped to one recorded finding, so the review went commit by commit against the first report rather than asking for a split.

## Resolutions from the first round

Each first-round resolution, checked against HEAD:

**Blocking**
1. `ankka-keyring` image unpublished (6850521f5): **holds.** The image is in `IMAGES` and in the publish step, and the manifest loops read `IMAGES`.
2. TypeScript `toJSON` (2507a55db): **holds.** `toJSON` gives the subject only.
3. The Python and TypeScript caches (c26febaa4, a3eac5822): **holds for the rules asked for.** Own keys are held 5 minutes, other projects' 30 seconds, the cache is bounded LRU, a read's erased answer is held for the foreign TTL, and the clock is injected. A gap remains that the first round's own suggestion left open: see the first item under Should fix.
4. Go's foreign TTL (c5622e8ab): **holds.** A small race remains in the new drop generation (Should fix).
5. `softDelete` gate (a8bf6dc75): **holds.** The variable is gated on `spec.personal`, and the GCS-bucket fixture is in `RenderingUnchangedSuite`.
6. Acks across projects (2af11d624): **holds.** An ack is refused unless `applied_log` gives its seq to the hello's project. `status` filters expected instances, acks and live connections by that project. The `NoticesSuite` case covers an intruder, a phantom and a never-applied seq.

**Should fix**
7. Outage bound (b75e3549f): **holds.** The bound counts from when the keyring was last heard. Settings refuse an outage bound above the notice bound, and a test composes `check()` with the outage.
8. Hello from the start (d0cc2c7f8): **holds for its purpose**, but it introduces a lost-acknowledgement window (Should fix).
9. Lookup tokens on redaction (5a57a987e): **holds.** Only the erased subject's paths are dropped. The two-subject row and the second erasure clearing both rows are asserted.
10. Lookup inside a collection (bfeca7eb4): **holds.** Both view shapes refuse a `[*]` path, and a size mismatch refuses the write.
11. Completion waits on every personal service (f89210cce, db77f360d): **holds.** A start with an erasure handler and no keyring is refused (`requireKeyring`). There is a cost note under Should fix.
12. Refusal flood and 404 (10f36350a, f89210cce): **holds.** One refusal is recorded per caller and subject an hour, and an unknown project answers 403.
13. `ErasureAskRefused` pinned: **holds.**
14. `HandlerRan` every pass (f89210cce): **holds**, with a question about a failure that recurs after a quiet run.
15. Quiet runs per service: **holds.**
16. The filer withdraws and replaces: **holds.** Any service may reach the routes, but `filerOrMember` admits only the filing service while it still holds the grant.
17. `KeyringUnavailable` as UNAVAILABLE (4a43abd4b): **holds** in both places.
18. A reopened stream deregistered (fce86acf8): **holds.** Streams are removed by `(instance, queue)`, and only that queue's waits are settled.
19. Unbounded ack wait (fce86acf8): **holds.** Each notice has a deadline at `notice-bound`, after which the stream fails and the wait settles.
20. A fetch in flight at a drop: **holds** in the JVM (`putIfUndropped` under the cache's lock), in Python and in TypeScript, where nothing runs between the flight check and the put. **Not quite in Go** (Should fix).
21. Null version ids (43b5725f6, 9d69309d0): **holds.**
22. `limitations.md` (82e0f135a): **holds.** The three entries are true now.
23. Unimplemented MUSTs: **holds**, with the reply-encoding follow-up recorded in research. One gap in the Java test kit (Should fix).
24. `sdk-go.md`: **holds.**
25. `HttpClient` per call (41f991345): **holds.** Two threads may race to build the client once after a rotation, which is harmless.
26. Sweep window in SQL (7195124de): **holds.** A small residue is noted under Nits.

**Nits**
27. Nonce overload `private[ankka]`: **holds.**
28. Refusal repeats the value (9fca7bf81, d2f5078df): **does not hold.** Two JVM tests now fail, and Rust still repeats the value (Blocking).
29. `erasure_file` idempotency: **holds.**
30. Imports in `ClientLogic`: **holds.**
31. Go evicts one entry: **holds.**
32. Escaped subject prefix in the spec: **holds.**

**Questions**
- Replay of every unapplied entry (db84a30c0): **holds.** The new case applies 6 and then replays 5.
- Backup and WAL wording (8bd7fd3c1): **holds.**
- A remote host retries on its next command (7adfd4eb7): answered and documented.
- Not-before parsing: **holds.** It accepts exactly a date, or an instant at midnight UTC.
- The trace on a process's erasure handler (095a78932): **holds for the trace**. `Trace.outbound` also carries the current caller when one is set.
- Replacing a hold without a date: **holds.** Refused.
- A second ask on a held subject: **holds.** It answers 409. The new scenario is bound and asserts that one request is still held.
- Personal data in reasons, and decrypt being project-wide: answered in the docs.

## Blocking

- `modules/sdk-java/src/test/scala/com/thinkmorestupidless/ankka/javasdk/codec/PersonalRecordCodecSuite.scala:146-150` and `sdks/kotlin/ankka-kotlin/src/test/kotlin/com/thinkmorestupidless/ankka/kotlin/codec/PersonalTest.kt:127` — issue (blocking): both tests still expect `'<value>' is not a data subject: <rule>`.
  - Since 9fca7bf81, core's `DataSubject.from` answers `the value given is not a data subject: <rule>`. The Java `DataSubject` and Kotlin's `subject()` both refuse through `PersonalJson.subjectProblem`, so these two cases fail on HEAD.
  - The commit changed `PersonalSuite` and the three process SDKs' tests, but not these two. Neither was run, since sbt was not run here, but the assertion is exact equality on the message.
  - Suggestion: assert `s"the value given is not a data subject: ${message}"` in both.
  - While there, two more places still repeat the value:
    - `sdks/rust/ankka/src/personal.rs:124-130` (`'{shown}' is not a data subject`). Rust is the seventh SDK, and FR-029 covers it as much as the others.
    - The console's fake control plane (`console/package/src/testing/fake-control-plane.ts:1642`), which then no longer matches the real one.
  - Resolved: both tests expect `the value given is not a data subject: <rule>`; Rust and the console's fake control plane no longer repeat the value, and their tests check the exact message, 209557825

## Should fix

- `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/keyring/ErasureDuties.scala:80-83` — issue (non-blocking): the service's database records a notice as carried out before the keyring has its acknowledgement.
  - `record(after)` advances `carried_through` first. The ack then waits in `RemoteKeyStoreClient`'s in-memory `pending` queue until a socket takes it.
  - If the instance stops in between, the next start says hello from the raised mark and is never handed that notice again. During a keyring outage that is any restart, since acks queue for the outage's length.
  - The keyring's `status` needs at least one ack per service (`acks.isEmpty` is incomplete). A single-instance service, or a service whose every instance restarted, therefore never completes that erasure. The request waits out the window and is read no more, never final.
  - Before d0cc2c7f8 every start re-ran the whole history, which healed this case.
  - Suggestion: advance the database mark only once the keyring has taken the ack, from `flush` once the frame is sent (as `RemoteKeyStoreClient`'s own `carried` does). Or have the keyring's backlog also hand any applied seq this service has no ack for. Add a case that stops an instance between the duties and the ack and restarts it.
  - Resolved: `KeyStoreClient.ack` answers once the ack frame is sent, and only then is `carried_through` advanced; an `ErasureDutiesSuite` case stops an instance between the duties and the ack and sees the notice handed again on restart, acba498b8

- `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/keyring/KeyringClient.scala:204-217` with `sdks/python/src/ankka/keyring.py`, `sdks/typescript/src/keyring.ts` and `sdks/go/keyring.go` — issue (non-blocking): the notice bound that "wins" (FR-020, decided 2026-11-05) holds only for the JVM's own cache.
  - When the runtime's channel to the keyring goes unconfirmed past `notice-bound`, `check()` drops the runtime's keys. Nothing reaches the process beside it: `ProcessNotices` is told only of notices, and its stream stays open.
  - A Python, TypeScript or Go process therefore keeps serving a key from its own cache for `ttl` (5 minutes) after the runtime stopped hearing notices. An erasure applied in that window reaches its reads up to 5 minutes late, past SC-004's 60 seconds.
  - The process SDKs' docstrings claim "the runtime's rules", and the JVM cache now enforces them.
  - Suggestion: when `check()` drops all, fail every open `SubjectNotices` stream (`ProcessNotices` gains an `endAll`), which each SDK already answers with `drop_all`. Add a sidecar case with an unconfirmed channel.
  - Resolved: `KeyringClient.check()` tells `onDropAll` listeners when the channel lapses, and `ProcessNotices.endAll` ends every process stream, so each SDK drops its cache; a `ClientPersonalSuite` case with an outage past the notice bound, e3b9685b4

- `sdks/go/keyring.go:255-271` with `:240-246` — issue (non-blocking) (if-minor): Go checks `f.dropped` under the lock in `fetchOne`, releases it, and `prefetch` re-locks later to `put`.
  - A `drop` that runs in that gap finds neither the flight nor a cache entry, and the old key is then put back. This is the window round one's fix closed in the JVM (`putIfUndropped`), in Python and in TypeScript (single-threaded between the check and the put).
  - Suggestion: put the answer inside `fetchOne` under the same lock that reads `dropped`, or keep a generation as the JVM does.
  - Resolved: the answer is cached or forgotten inside `fetchOne`, under the lock that reads `dropped`; a `-race` test drops in that window, 4f78b9461

- `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/erasure/ErasurePlane.scala:23-34` — suggestion (if-minor): `personalServices` costs a view query plus one entity call per service of the project, and it runs often.
  - It is called for every open applied request on every sweep pass, and again on every GET of a request and its certificate.
  - For a project of tens of services with a backlog of applied requests, that is hundreds of entity calls per pass.
  - Suggestion: carry `personal` on the `ServiceRows` row and filter in the query, or compute the set once per project per pass.
  - Resolved: the sweep reads a project's personal services once per pass; carrying `personal` on `ServiceRows` was declined, since that row is the `services list` wire type and the view would need a version (research.md), 3970d82d1

- `modules/testkit-java/src/main/scala/com/thinkmorestupidless/ankka/javasdk/testkit/bridge/InstallationsKit.scala:17-27` — issue (non-blocking) (if-minor): `start` and `restart` drop the `configure` function that `ServiceKit.start` uses to hand over the Java erasure handler (`(_, _, _, _, _)`).
  - A Java service started through `InstallationsKit` runs with no erasure handler, and nothing says so.
  - Suggestion: pass `configure` into `group.start`/`restart`. If the Scala group cannot take one, refuse a service that has a handler, naming it.
  - Resolved: `InstallationsKit` hands `configure` to the group's start and restart; an `ErasureJavaSuite` case runs the handler at a started and a restarted installation, f81313614

## Nits

- `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/keyring/ErasureHandlerHost.scala:57-64` — nitpick (non-blocking): two scaladoc blocks are stacked on `requireKeyring`. The first ("Marks the instance holding it…") belongs to `marker`, below. Move it there.
  - Resolved: moved to `marker`, aec885152
- `controlplane/.../erasure/ErasureRequestEntity.scala:190` (`ranQuietly`) — nitpick (non-blocking): quiet runs are now one `RanQuietly` event per service per pass. A request whose S handlers keep re-running quietly appends about S × 2,880 events over the 30 days, where it used to append 2,880. The state stays small, but consider recording a quiet run only when it changes the service's last known run.
  - Resolved: a quiet run is recorded only when it changes the service's last known run, e6ae17255
- `controlplane/.../erasure/ErasureRequestsView.scala:175` — nitpick (non-blocking): the `open` query keeps a request polled past the window whenever `objectsFinalAt` is set. A request that has an objects date but is missing a service's completion is therefore still polled forever, a smaller case of round one's finding. Bound that clause too, for example by `objectsFinalAt` plus the window.
  - Resolved: the `objectsFinalAt` clause is bounded by the window too, ac0864a96

## Questions

- `controlplane/.../erasure/ErasureRequestEntity.scala:174-178` — question: a failure is deduped against the service's last `HandlerRun`, and quiet runs are not `HandlerRun`s. So failure A, then a quiet success, then failure A again records the second failure as no news, and FR-016's audit never shows that the handler broke again. Is that intended? If not, dedupe only when the service's last quiet run is not later than its last `HandlerRun`.
  - Answered: not intended. A failure is deduplicated only against the service's last known run, quiet runs and its completion included, so a failure after a quiet run is audited again; an entity test, e6ae17255

## Praise

- `modules/runtime/.../keyring/CarriedThrough.scala` — praise: the contiguous-prefix mark is small, well named and tested on gapped numbering. It is the right shape for a project's notices, which are numbered installation-wide.
- `sidecar/.../ProcessNotices.scala:107-117` — praise: removing by `(instance, queue)` and settling only that queue's waits fixes the reconnect race completely. The new trap in `sidecar.md` states the general rule, and the next keyed-stream registry will need it.
- `modules/runtime/.../ViewStore.scala:146-161` — praise: computing the erased subject's lookup paths from the pre-redaction payload inside the same CTE keeps the row and its tokens in one statement. The suite's second erasure proves the other subject's token goes when its own erasure comes.
- `keyring/.../NoticesSuite.scala` (another project's instances) — praise: one case covers an intruder named as the victim's service, a phantom service, and an ack of a seq never applied. That is exactly the set of ways the old code could be fooled.
- `modules/runtime/.../KeyringClientSuite.scala` (the in-flight fetch) — praise: the latch-gated store reads its answer before the notice and returns it after, so the case would go red without the drop generation.

## Checks applied

- **General checklist groups applied:** Correctness, Design and scope, Error handling, Concurrency and state, Security (the widened ACL on replace and withdraw, the 403 for an unknown project, the cross-project ack), Data and migrations (`carried_through` added by `ADD COLUMN IF NOT EXISTS`, additive, and `RanQuietly.service` an optional field), Performance, Tests, Observability, API and compatibility (protocol 1.23's new `home` fields are additive, and the Java `ServiceBuilder` gained a method), Dependencies and build (`release.yml`), Documentation, and Commit and PR hygiene. None was skipped.
- **Commit and PR hygiene:**
  - All 46 headers parse as Conventional Commits scoped `042`, under 72 characters, and every commit carries the trailer.
  - 3e73b7fe5's "39 fixed" is a known miscount: 36 resolved and 5 answered.
  - There is no PR, so CI was not checked.
- **Language skills:** `pr-review-scala` (concurrency in `KeyCache`/`KeyringClient`, `CarriedThrough`, `ProcessNotices` and the singleton ask; codec placement; totality); `pr-review-python`, `pr-review-typescript` and `pr-review-go` (the caches' lifetimes and races, origin tracking, and storage paging); `pr-review-rust` (`DataSubject`'s equality ignores the origin, deliberately and consistently); `pr-review-java` and `pr-review-kotlin` (the facade and the test kits). The language checklists were applied from memory of the bundled skills; no repository-local `pr-review-<language>` skills or `REVIEW.md` exist.
- **Project rules read:** the root CLAUDE.md and the workspace brains; `runtime.md`, `sidecar.md`, `messaging.md`, `secrets.md`, `cluster-and-tls.md`, `observability.md`, `testing.md`, `sdk-go.md`, `sdk-typescript.md` and `console.md`. The new traps (the singleton wait, removing streams by pair, the home on the wire, caches keyed by home) are recorded where CLAUDE.md asks.
- **Enforced by tooling and not reviewed:** formatting, `-Werror` and the commit-message hook.
- **Tests:** not run. The instructions said not to run sbt, and the touched suites need Postgres, the sidecar image or k3s. "Could this pass while false" was asked of each new test read. The failing JVM refusal-message tests were found by reading, not by a run.
