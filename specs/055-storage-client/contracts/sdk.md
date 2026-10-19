# Contract: the storage client, in each SDK

What a service's code sees. Decisions are in [research.md](../research.md) (R1, R2, R5, R6, R14,
R16). The same operations, names and errors in every language; each language's idiom for the
rest.

## Scala (`modules/sdk`)

```scala
trait ObjectStorage:
  def default: Bucket                 // the unnamed bucket; refuses when the service has none
  def bucket(name: String): Bucket    // a named bucket; refuses when the descriptor does not give it
  def buckets: Vector[Bucket]

trait Bucket:
  def name: Option[String]
  def exposure: BucketExposure         // Private | Signed | Public | Supplied
  def put(name: String, body: InputStream, length: Long, contentType: String): Unit
  def putBytes(name: String, body: Array[Byte], contentType: String): Unit
  def get(name: String): Option[StoredObject]
  def getBytes(name: String): Option[(String, Array[Byte])]
  def delete(name: String): Unit
  def list(prefix: String): Iterator[ObjectEntry]
  def stat(name: String): Option[ObjectInfo]
  def signedUrl(name: String, access: SignedAccess, span: Duration = default): URI   // Signed only
  def url(name: String): URI                                                         // Public only

enum SignedAccess { case Read, Keep }
final case class StoredObject(contentType: String, length: Long, lastKept: Instant, body: InputStream) extends AutoCloseable
final case class ObjectInfo(name: String, contentType: String, length: Long, lastKept: Instant)
final case class ObjectEntry(name: String, length: Long, lastKept: Instant)

object ObjectStorage:
  val unavailable: ObjectStorage
object ObjectRules:
  def nameProblem(name: String): Option[String]
  def prefixProblem(prefix: String): Option[String]
  val MaxNameBytes = 1024; val MaxSignedUrl = 7.days; val DefaultSignedUrl = 15.minutes
```

Offered as `storage: ObjectStorage` on `WorkflowContext` (steps only), `ConsumerContext`,
`TimedActionContext`, `AgentContext`, `AutonomousAgentContext`, `BlueprintContext` and
`EndpointClients`; absent from `EntityContext`, `CommandContext`, `ViewComponentContext`.

Every call blocks the virtual thread until the store answers and throws `CommandError`:

| Code | When |
|---|---|
| `BadRequest` | a name, prefix, length or span breaks a rule; `signedUrl` on a bucket not `Signed`; `url` on a bucket not `Public`; a workflow command handler calling |
| `Unavailable` | the store cannot be reached or times out; `unavailable`; no unnamed bucket (names `provisionObjectStorage`); an unknown bucket name (names the known ones); no credential (names `objectStorageCredential`) |
| `Internal` | an answer the client cannot read |

`get` and `stat` answer `None` for an object that is not there. `delete` of nothing succeeds.
`signedUrl` and `url` make no request.

## Python (`ankka.storage`)

```python
class Buckets:                      # context.storage
    default: Bucket
    def bucket(self, name: str) -> Bucket
    buckets: list[Bucket]

class Bucket:
    name: str | None
    exposure: Literal["private", "signed", "public", "supplied"]
    async def put(self, name, body: AsyncIterable[bytes] | bytes, *, length: int | None = None, content_type: str) -> None
    async def get(self, name) -> StoredObject | None          # .content_type, .length, .last_kept, .body (async iterator), .read()
    async def delete(self, name) -> None
    def list(self, prefix) -> AsyncIterator[ObjectEntry]
    async def stat(self, name) -> ObjectInfo | None
    def signed_url(self, name, access: Literal["read", "keep"], span: timedelta = ...) -> str
    def url(self, name) -> str
```

`length` is required when `body` is an iterable. `HasStorage` mixin on `Endpoint`, `Consumer`,
`GraphConsumer`, `TimedAction`, `Agent`, `AutonomousAgent`; `Workflow.storage` guarded by
`_in_step`. `InMemoryBuckets()` and `DirectoryBuckets(path)` for tests and laptops. Errors are
`CommandError` with the codes above.

## TypeScript (`ankka`)

```ts
interface Buckets { readonly default: Bucket; bucket(name: string): Bucket; readonly buckets: Bucket[] }
interface Bucket {
  readonly name: string | undefined
  readonly exposure: "private" | "signed" | "public" | "supplied"
  put(name: string, body: ReadableStream<Uint8Array> | Uint8Array, options: { contentType: string; length?: number }): Promise<void>
  get(name: string): Promise<StoredObject | undefined>     // contentType, length, lastKept, body: ReadableStream, bytes()
  delete(name: string): Promise<void>
  list(prefix: string): AsyncIterable<ObjectEntry>
  stat(name: string): Promise<ObjectInfo | undefined>
  signedUrl(name: string, access: "read" | "keep", spanSeconds?: number): string
  url(name: string): string
}
```

Getter/setter `storage` on endpoint, consumer, graph consumer, timed action, agent, autonomous
agent; workflow guarded by `#inStep`. `InMemoryBuckets`, `DirectoryBuckets`. No `enum`
(erasable syntax only).

## Rust (`ankka::storage`)

```rust
pub struct Storage { .. }            // ctx.storage() -> Option<Storage>, None where not offered
impl Storage { pub fn default(&self) -> Bucket; pub fn bucket(&self, name: &str) -> Bucket }
impl Bucket {
    pub fn put(&self, name: &str, body: &[u8], content_type: &str) -> Result<(), StorageError>
    pub fn get(&self, name: &str) -> Result<Option<StoredObject>, StorageError>   // bytes, whole
    pub fn delete(&self, name: &str) -> Result<(), StorageError>
    pub fn list(&self, prefix: &str) -> Result<Vec<ObjectEntry>, StorageError>    // every page, joined by the host
    pub fn stat(&self, name: &str) -> Result<Option<ObjectInfo>, StorageError>
    pub fn signed_url(&self, name: &str, access: SignedAccess, span: Duration) -> Result<String, StorageError>
    pub fn url(&self, name: &str) -> Result<String, StorageError>
}
```

Each call is one `storage` import, a `StorageRequest` in and a `StorageReply` out, answered by
the runtime's own client; a body is at most 4,000,000 bytes, and a larger `put` or `get` is a
`StorageError::TooLarge` naming the bound. `with_storage()` on the same contexts as
`with_secrets()`. Natively, with no host, a `HashMap` behind the same API.

## The rules fixture

`protocol/fixtures/storage/rules.json`:

```json
{ "about": "…",
  "objectNames":  [ { "unit": "a", "repeat": 1024, "accepted": true, "why": "at the limit" }, { "unit": "/a", "repeat": 1, "accepted": false, "why": "leading slash" }, … ],
  "bucketNames":  [ … ],
  "signedUrl":    { "maxSeconds": 604800, "defaultSeconds": 900 } }
```

Read by `ObjectRulesSuite` (Scala), `tests/test_storage.py`, `test/storage.test.ts`,
`tests/storage.rs`, each asserting the same acceptance and the same words in the refusal.
