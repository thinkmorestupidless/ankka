# Objects a service keeps

> Keep photos, documents and exports in a service's buckets — put, get, list, stat and delete by name, sign a URL a browser uploads or downloads with, give a public address, and run the same code with no object store.

Source: https://docs.ankka.cloud/build/object-storage/
Some of what a service holds is not a record: a listing's photos, a signed contract as a PDF, the export
a customer asked for. Kept in an entity, those bytes would be in every event, every snapshot and every
view built from them. They belong in a bucket, and the entity keeps the object's **name**.

The **storage client** is how a service reaches its buckets. It reads like a file system and behaves like
an object store: an object has a name made of segments separated by `/`, a content type and a length, and
it is kept, read, listed, described and deleted whole. Where the bucket lives is the platform's to decide:
the installation's own store, Google Cloud Storage, or a store you bring. On your machine it is a
directory, and in a test it is memory. Your code is the same in every case.

## Who has the client

The client is offered to endpoints, workflow steps, consumers, timed actions and agents. It is **not**
offered to an entity or a view: their contexts have no storage at all, so in Scala reaching for it does not
compile, in Python, TypeScript and Rust the attribute does not exist, and in Go `ankka.StorageFrom(ctx)`
answers `nil` for an entity's or a view's context. A put or a get waits on the store, which an
entity's single-writer path must not do. An entity records the name; whatever handled the bytes calls the
entity with it.

A workflow's command handlers and its steps share one context, so a workflow's client answers only in a
step; a command handler that calls it is refused.

## The buckets a service has

A service has the **unnamed bucket** when its descriptor sets `provisionObjectStorage`, and a **named
bucket** for each entry under `buckets`:

```json
{
  "provisionObjectStorage": true,
  "buckets": [
    { "name": "photos", "exposure": "public" },
    { "name": "exports", "exposure": "signed" }
  ]
}
```

`storage.default` is the unnamed bucket, and `storage.bucket("photos")` a named one. A name the
descriptor does not give is refused as `Unavailable`, naming the buckets it does give. Each bucket has an
**exposure**, which decides what can be read from outside the service:

| Exposure | What it means |
|---|---|
| `private` | only the service reaches it |
| `signed` | the service signs URLs a browser uploads to or downloads from |
| `public` | anyone reads an object at its address, with no signature |
| `supplied` | a bucket the service brings, with its own variables |

What the platform makes for each, and the variables it gives, are in
[Object storage on the platform](../platform/object-storage.md).

## Keeping and reading

Ten operations, by name:

- **put(name, body, length, contentType)** keeps the object, replacing what was there. The body is a
  stream, passed to the store as it is read, so an object of any size is never held whole. A put declares
  its length.
- **putBytes(name, bytes, contentType)** keeps an object you already hold.
- **get(name)** answers the object as a stream with its content type, length and the time it was kept,
  or nothing when none is kept. **getBytes(name)** answers it read whole.
- **delete(name)** removes the object. Removing nothing is not an error.
- **list(prefix)** answers the names under a prefix, in order, with each length and the time it was
  kept. An empty prefix lists everything.
- **stat(name)** answers the content type, length and time kept, or nothing.
- **signedUrl(name, access, span)** answers a URL that reads the object, or keeps one under that name,
  until the span ends. A signed bucket's only.
- **url(name)** answers the address anyone reads the object at, with no signature. A public bucket's only.

Each call blocks the handler until the store has answered. The components that have a client run on
virtual threads (Scala, Java, Kotlin) or call through the runtime beside them (Python, TypeScript, Rust,
Go), so the wait holds nothing else up.

An entity that records a photo's name:

**Scala**

```scala
/** What a listing records about a photo: its name in the bucket, never its bytes. */
final case class PhotoRecorded(name: String)

final case class ListingState(photos: Vector[String])

final class Listing extends EventSourcedEntity[ListingState, PhotoRecorded]:
  def emptyState: ListingState = ListingState(Vector.empty)

  def applyEvent(event: PhotoRecorded): ListingState =
    currentState.copy(photos = currentState.photos :+ event.name)

  def recordPhoto(name: String): Effect[Done] =
    effects.persist(PhotoRecorded(name)).thenReply(_ => Done)

  def photos: ReadOnlyEffect[ListingState] = effects.reply(currentState)

private val listingSerializer: Serializer[ListingState] = Codecs.serializer[ListingState]("listing")

object Listing
    extends EventSourcedEntity.Companion[Listing, ListingState, PhotoRecorded](
      componentId = ComponentId("listing"),
      stateSerializer = listingSerializer,
      eventSerializer = Codecs.serializer[PhotoRecorded]("photo-recorded")
    ):
  given Serializer[ListingState]                 = listingSerializer
  def create(context: EventSourcedEntityContext) = new Listing

  val recordPhoto = command("record-photo")(_.recordPhoto)
  val photos      = query("photos")(_.photos)
```

**Java**

```java
/** A listing's photos: their names in the bucket, never their bytes. */
@ComponentId("listing")
public class Listing extends EventSourcedEntity<Listing.ListingState, Listing.PhotoRecorded> {

  /** What a listing records about a photo: its name in the bucket. */
  @Manifest("photo-recorded")
  public record PhotoRecorded(String name) {}

  @Manifest("listing")
  public record ListingState(List<String> photos) {}

  @Override
  protected ListingState emptyState() {
    return new ListingState(List.of());
  }

  @Override
  protected ListingState applyEvent(ListingState state, PhotoRecorded event) {
    var photos = Stream.concat(state.photos().stream(), Stream.of(event.name())).toList();
    return new ListingState(photos);
  }

  @Command("record-photo")
  public Effect<Done> recordPhoto(String name) {
    return effects().persist(new PhotoRecorded(name)).thenDone();
  }

  @Query("photos")
  public ReadOnlyEffect<ListingState> photos() {
    return effects().reply(currentState());
  }
}
```

**Kotlin**

```kotlin
/** What a listing records about a photo: its name in the bucket, never its bytes. */
@Manifest("photo-recorded")
data class PhotoRecorded(val name: String)

@Manifest("listing")
data class ListingState(val photos: List<String> = emptyList())

@ComponentId("listing")
class Listing : EventSourcedEntity<ListingState, PhotoRecorded>() {
    override fun emptyState() = ListingState()

    override fun applyEvent(state: ListingState, event: PhotoRecorded) = state.copy(photos = state.photos + event.name)

    @Command("record-photo")
    fun recordPhoto(name: String): Effect<Done> = effects { persist(PhotoRecorded(name)).thenDone() }

    @Query("photos")
    fun photos(): ReadOnlyEffect<ListingState> = effects { reply(currentState()) }
}
```

**Python**

```python
@dataclass(frozen=True)
class PhotoRecorded:
    """What a listing records about a photo: its name in the bucket, never its bytes."""

    name: str


@dataclass(frozen=True)
class ListingState:
    photos: list[str]


class Listing(EventSourcedEntity[ListingState, PhotoRecorded]):
    component_id = "listing"
    state_codec = json_codec(ListingState, "listing")
    event_codec = json_codec(PhotoRecorded, "photo-recorded")

    def empty_state(self) -> ListingState:
        return ListingState([])

    def apply_event(self, state: ListingState, event: PhotoRecorded) -> ListingState:
        return ListingState([*state.photos, event.name])

    @command("record-photo")
    def record_photo(self, name: str) -> EventSourcedEffect[ListingState, PhotoRecorded, Done]:
        return self.effects.persist(PhotoRecorded(name)).then_reply(lambda _: DONE)

    @query("photos")
    def photos(self) -> ReadOnlyEffect[ListingState, PhotoRecorded, ListingState]:
        return self.effects.reply(self.state)
```

**TypeScript**

```ts
/** What a listing records about a photo: its name in the bucket, never its bytes. */
export const PhotoRecorded = s.record("PhotoRecorded", { name: s.string })
export type PhotoRecorded = Infer<typeof PhotoRecorded>
export const ListingState = s.record("ListingState", { photos: s.list(s.string) })
export type ListingState = Infer<typeof ListingState>

export class Listing extends EventSourcedEntity<ListingState, PhotoRecorded> {
  static readonly componentId = "listing"
  static readonly state = jsonCodec(ListingState, "listing")
  static readonly events = jsonCodec(PhotoRecorded, "photo-recorded")

  static readonly handlers = {
    recordPhoto: command("record-photo", s.string, Done, (l: Listing, name) =>
      l.effects.persist({ name }).thenReply(() => done),
    ),
    photos: query("photos", ListingState, (l: Listing) => l.effects.reply(l.state)),
  }

  emptyState(): ListingState {
    return { photos: [] }
  }

  applyEvent(state: ListingState, event: PhotoRecorded): ListingState {
    return { photos: [...state.photos, event.name] }
  }
}
```

**Rust**

```rust
/// What a listing records about a photo: its name in the bucket, never its bytes.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct PhotoRecorded {
    pub name: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
pub struct ListingState {
    pub photos: Vec<String>,
}

pub struct Listing;

impl Listing {
    fn record_photo(_: &ListingState, name: String, _: &Context) -> Effect<PhotoRecorded, Done> {
        effects::persist(PhotoRecorded { name }).then_reply_value(Done)
    }

    fn photos(listing: &ListingState, _: (), _: &Context) -> ReadOnlyEffect<ListingState> {
        effects::reply(listing.clone())
    }
}

impl EventSourcedEntity for Listing {
    type State = ListingState;
    type Event = PhotoRecorded;
    const COMPONENT_ID: &'static str = "listing";
    const STATE_MANIFEST: Option<&'static str> = Some("listing");
    const EVENT_MANIFEST: Option<&'static str> = Some("photo-recorded");

    fn empty_state(_: &str) -> ListingState {
        ListingState::default()
    }

    fn apply(mut listing: ListingState, event: &PhotoRecorded) -> ListingState {
        listing.photos.push(event.name.clone());
        listing
    }

    fn handlers() -> Handlers<Listing> {
        Handlers::new()
            .command("record-photo", Listing::record_photo)
            .query("photos", Listing::photos)
    }
}
```

**Go**

```go
// PhotoRecorded is what a listing records about a photo: its name in the bucket, never its bytes.
type PhotoRecorded struct {
	Name string `json:"name"`
}

// ListingState is the names of a listing's photos.
type ListingState struct {
	Photos []string `json:"photos"`
}

type listingEffect = ankka.Effect[ListingState, PhotoRecorded, ankka.Done]

// Listing records the photos kept for it.
var Listing = ankka.EventSourced[ListingState, PhotoRecorded]("listing", nil,
	func(string) ListingState { return ListingState{} },
	func(s ListingState, e PhotoRecorded) ListingState {
		s.Photos = append(append([]string{}, s.Photos...), e.Name)
		return s
	},
	ankka.Command("record-photo", func(_ context.Context, _ ListingState, name string) (listingEffect, error) {
		return ankka.Persist[ListingState, PhotoRecorded, ankka.Done](PhotoRecorded{Name: name}).
			ThenReplyValue(ankka.Done{}), nil
	}),
	ankka.Query("photos", func(_ context.Context, s ListingState, _ ankka.Unit) (ankka.ReadOnlyEffect[ListingState], error) {
		return ankka.Reply(s), nil
	}),
	ankka.StateCodec(ankka.CodecFor[ListingState](ankka.Manifest("listing"))),
	ankka.EventCodec(ankka.CodecFor[PhotoRecorded](ankka.Manifest("photo-recorded"))),
)
```

And an endpoint over the service's buckets. The upload route passes the request's body to the bucket as
it arrives and the download route writes the object as it is read; the last route keeps the bytes and
gives the entity the name:

**Scala**

```scala
/** An endpoint over the service's buckets: keep, read, list, describe, delete, sign and address. */
final class PhotosEndpoint(clients: EndpointClients) extends HttpEndpoint("/storage"):

  val acl: Acl = Acl.AllowAll

  private def bucket: Bucket = chosen(clients.storage, query.optional[String]("bucket"))

  postBody("/objects") { (request: KeepObject) =>
    keep(clients.storage, request)
    Done: Done
  }

  // An upload passed to the bucket as it arrives, never held whole: any size the route admits.
  putStream("/upload") { (body: StreamedBody) =>
    val length =
      body.length.getOrElse(throw HttpProblem.badRequest("an upload declares its length"))
    bucket.put(query.required[String]("name"), body.body, length, body.contentType)
    Done: Done
  }

  // A download written as it is read from the bucket.
  get("/download") { () =>
    val name = query.required[String]("name")
    bucket
      .get(name)
      .fold(throw HttpProblem.notFound(s"no object '$name'"))(o =>
        Streamed(o.contentType, Some(o.length), o.body)
      )
  }

  get("/objects") { () =>
    val name = query.required[String]("name")
    bucket
      .getBytes(name)
      .fold(throw HttpProblem.notFound(s"no object '$name'"))((contentType, body) =>
        Bytes(contentType, body)
      )
  }

  get("/stat") { () =>
    val name = query.required[String]("name")
    bucket
      .stat(name)
      .fold(throw HttpProblem.notFound(s"no object '$name'"))(info =>
        Described(info.name, info.contentType, info.length, info.lastKept.toString)
      )
  }

  get("/list") { () =>
    Listed(
      bucket
        .list(query.optional[String]("prefix").getOrElse(""))
        .map(e => ListedObject(e.name, e.length, e.lastKept.toString))
        .toVector
    )
  }

  delete("/objects") { () =>
    bucket.delete(query.required[String]("name"))
    Done: Done
  }

  get("/signed") { () =>
    val access = query.required[String]("access") match
      case "keep" => SignedAccess.Keep
      case _      => SignedAccess.Read
    val span = query.optional[Int]("seconds").fold(ObjectRules.DefaultSignedUrl)(_.seconds)
    bucket.signedUrl(query.required[String]("name"), access, span).toString
  }

  get("/url") { () =>
    bucket.url(query.required[String]("name")).toString
  }

  // The entity keeps the name; the bucket keeps the object.
  postBody("/listings/{id}/photos") { (id: String, request: RecordPhoto) =>
    clients.storage.default.putBytes(
      request.name,
      request.text.getBytes(UTF_8),
      request.contentType
    )
    clients.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(Listing.recordPhoto)
      .invoke(request.name)
  }
```

**Java**

```java
/** An endpoint over the service's buckets: keep, read, describe, list, delete, sign and address. */
public class PhotosEndpoint extends HttpEndpoint {

  public record KeepObject(String name, String text, String contentType) {}

  public record Described(String name, String contentType, long length, String lastKept) {}

  public record ListedObject(String name, long length, String lastKept) {}

  public record Listed(List<ListedObject> objects) {}

  public record RecordPhoto(String name, String text, String contentType) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  /** The bucket the request names, or the service's unnamed one. */
  private Bucket bucket() {
    return request().query("bucket").map(storage()::bucket).orElseGet(storage()::defaultBucket);
  }

  @Post("/storage/objects")
  public Done keep(@Body KeepObject request) {
    bucket().putBytes(request.name(), request.text().getBytes(UTF_8), request.contentType());
    return Done.done();
  }

  // An upload (a POST) passed to the bucket as it arrives, never held whole: any size the route
  // admits.
  @StreamBody("/storage/upload")
  public Done upload(StreamedBody body) {
    var length =
        body.length()
            .orElseThrow(
                () -> new ProblemException(Problem.badRequest("an upload declares its length")));
    bucket().put(request().requiredQuery("name"), body.body(), length, body.contentType());
    return Done.done();
  }

  // A download. A Java route answers its body whole, so the object is read before it is sent.
  @Get("/storage/objects")
  public Response object() throws IOException {
    var name = request().requiredQuery("name");
    try (var object = bucket().get(name).orElseThrow(() -> noObject(name))) {
      return Response.bytes(object.body().readAllBytes(), object.contentType());
    }
  }

  @Get("/storage/stat")
  public Described stat() {
    var name = request().requiredQuery("name");
    var info = bucket().stat(name).orElseThrow(() -> noObject(name));
    var kept = info.lastKept().toString();
    return new Described(info.name(), info.contentType(), info.length(), kept);
  }

  @Get("/storage/list")
  public Listed list() {
    var prefix = request().query("prefix").orElse("");
    return new Listed(
        bucket().list(prefix).stream()
            .map(e -> new ListedObject(e.name(), e.length(), e.lastKept().toString()))
            .toList());
  }

  @Delete("/storage/objects")
  public Done delete() {
    bucket().delete(request().requiredQuery("name"));
    return Done.done();
  }

  @Get("/storage/signed")
  public String signed() {
    var name = request().requiredQuery("name");
    var access = request().query("access").filter("keep"::equals).isPresent()
        ? SignedAccess.KEEP
        : SignedAccess.READ;
    var span = request().query("seconds").map(s -> Duration.ofSeconds(Long.parseLong(s)));
    return span.map(d -> bucket().signedUrl(name, access, d))
        .orElseGet(() -> bucket().signedUrl(name, access))
        .toString();
  }

  @Get("/storage/url")
  public String url() {
    return bucket().url(request().requiredQuery("name")).toString();
  }

  // The entity keeps the name; the bucket keeps the object.
  @Post("/storage/listings/{id}/photos")
  public Done recordPhoto(String id, @Body RecordPhoto request) {
    storage()
        .defaultBucket()
        .putBytes(request.name(), request.text().getBytes(UTF_8), request.contentType());
    return componentClient()
        .forEventSourcedEntity(id)
        .call(Listing::recordPhoto)
        .invoke(request.name());
  }

  private static ProblemException noObject(String name) {
    return new ProblemException(Problem.notFound("no object '" + name + "'"));
  }
}
```

**Kotlin**

```kotlin
/** An endpoint over the service's buckets: keep, read, list, describe, delete, sign and address. */
class PhotosEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    /** The bucket `bucket` names, or the unnamed one. */
    private fun bucket(named: String? = request().queryOrNull("bucket")): Bucket =
        named?.let { storage().bucket(it) } ?: storage().defaultBucket()

    private fun name(): String = request().requiredQuery("name")

    @Post("/storage/objects")
    fun keep(@Body request: KeepObject): Done {
        bucket(request.bucket).putBytes(request.name, request.text.toByteArray(), request.contentType)
        return Done.done()
    }

    // An upload passed to the bucket as it arrives, never held whole: any size the route admits.
    @StreamBody("/storage/upload")
    fun upload(body: StreamedBody): Done {
        val length = body.length().orElseThrow { ProblemException(Problem.badRequest("an upload declares its length")) }
        bucket().put(name(), body.body(), length, body.contentType())
        return Done.done()
    }

    // A download: a Java or Kotlin route answers a body whole, so the object is read before it is sent.
    @Get("/storage/download")
    fun download(): Response {
        val name = name()
        val found = bucket().getOrNull(name) ?: throw ProblemException(Problem.notFound("no object '$name'"))
        return found.use { Response.bytes(it.body().readAllBytes(), it.contentType()) }
    }

    @Get("/storage/stat")
    fun stat(): Described {
        val name = name()
        val info = bucket().statOrNull(name) ?: throw ProblemException(Problem.notFound("no object '$name'"))
        return Described(info.name(), info.contentType(), info.length(), info.lastKept().toString())
    }

    @Get("/storage/list")
    fun list(): Listed =
        Listed(bucket().list(request().queryOrNull("prefix") ?: "").map { ListedObject(it.name(), it.length(), it.lastKept().toString()) })

    @Delete("/storage/objects")
    fun delete(): Done {
        bucket().delete(name())
        return Done.done()
    }

    @Get("/storage/signed")
    fun signed(): String {
        val access = if (request().queryOrNull("access") == "keep") SignedAccess.KEEP else SignedAccess.READ
        val seconds = request().queryOrNull("seconds")?.toLong()
        val url = if (seconds == null) bucket().signedUrl(name(), access) else bucket().signedUrl(name(), access, Duration.ofSeconds(seconds))
        return url.toString()
    }

    @Get("/storage/url")
    fun url(): String = bucket().url(name()).toString()

    // The entity keeps the name; the bucket keeps the object.
    @Post("/storage/listings/{id}/photos")
    fun record(id: String, @Body request: RecordPhoto): Done {
        storage().defaultBucket().putBytes(request.name, request.text.toByteArray(), request.contentType)
        return componentClient().forEventSourcedEntity(id).call(Listing::recordPhoto).invoke(request.name)
    }
}
```

**Python**

```python
# The service's buckets. `bucket` names one (absent: the unnamed bucket) and `name` the object;
# `op` is get, stat, list (under `prefix`), url or signed-url (`access` read or keep). A post
# keeps its body under `name`; a post with `rules` set to `name` or `prefix` instead tries its
# body as an object's name or a listing's prefix, and keeps nothing.
@post("/storage")
async def keep_object(self, body: str) -> StorageRecord:
    async def call(bucket: Bucket) -> StorageRecord:
        rules = self.request.query_param("rules")
        if rules == "prefix":
            async for _ in bucket.list(body):
                break
        elif rules is not None:
            await bucket.stat(body)
        else:
            content_type = self.request.header("Content-Type") or "application/octet-stream"
            await bucket.put(self._required("name"), body.encode("utf-8"), content_type=content_type)
        return StorageRecord("ok")

    return await self._storage_call(call)

@get("/storage")
async def ask_storage(self) -> StorageRecord:
    async def call(bucket: Bucket) -> StorageRecord:
        name = self.request.query_param("name") or ""
        op = self._required("op")
        if op == "get":
            found = await bucket.get(name)
            if found is None:
                return StorageRecord("absent")
            data = await found.read()
            return StorageRecord("ok", contentType=found.content_type, text=data.decode("utf-8"), length=len(data))
        if op == "stat":
            info = await bucket.stat(name)
            return StorageRecord("absent") if info is None else StorageRecord("ok", contentType=info.content_type, length=info.length)
        if op == "list":
            prefix = self.request.query_param("prefix") or ""
            return StorageRecord("ok", names=[entry.name async for entry in bucket.list(prefix)])
        if op == "url":
            return StorageRecord("ok", url=bucket.url(name))
        if op == "signed-url":
            access: Access = "keep" if self.request.query_param("access") == "keep" else "read"
            return StorageRecord("ok", url=bucket.signed_url(name, access))
        return StorageRecord("refused", "BAD_REQUEST", f"no storage op '{op}'")

    return await self._storage_call(call)

@delete("/storage")
async def delete_object(self) -> StorageRecord:
    async def call(bucket: Bucket) -> StorageRecord:
        await bucket.delete(self._required("name"))
        return StorageRecord("ok")

    return await self._storage_call(call)
```

**TypeScript**

```ts
const KeepObject = s.record("KeepObject", { name: s.string, contentType: s.string, text: s.string })
const Described = s.record("Described", { name: s.string, contentType: s.string, length: s.int, lastKept: s.string })
const ListedObject = s.record("ListedObject", { name: s.string, length: s.int, lastKept: s.string })
const Listed = s.record("Listed", { objects: s.list(ListedObject) })
const RecordPhoto = s.record("RecordPhoto", { name: s.string, contentType: s.string, text: s.string })

const utf8 = new TextEncoder()

/** An endpoint over the service's buckets: keep, read, list, describe, delete, sign and address. */
export class PhotosEndpoint extends Endpoint {
  static readonly prefix = "/storage"
  static readonly acl = Acl.allowAll

  /** The bucket the request names, or the unnamed bucket. */
  bucket(name: string | null): Bucket {
    return name === null ? this.storage.default : this.storage.bucket(name)
  }

  static readonly routes = {
    // A route reads its body whole, so an upload is bounded by what the route admits.
    keep: post("/objects", KeepObject, Done, async (ep: PhotosEndpoint, req, request) => {
      await ep.bucket(req.query.get("bucket")).put(request.name, utf8.encode(request.text), { contentType: request.contentType })
      return done
    }),
    read: get("/objects", s.string, async (ep: PhotosEndpoint, req) => {
      const name = required(req.query.get("name"))
      const object = await ep.bucket(req.query.get("bucket")).get(name)
      if (!object) throw new HttpProblem(404, `no object '${name}'`)
      return new TextDecoder().decode(await object.bytes())
    }),
    stat: get("/stat", Described, async (ep: PhotosEndpoint, req) => {
      const name = required(req.query.get("name"))
      const info = await ep.bucket(req.query.get("bucket")).stat(name)
      if (!info) throw new HttpProblem(404, `no object '${name}'`)
      return { name: info.name, contentType: info.contentType, length: info.length, lastKept: info.lastKept.toString() }
    }),
    list: get("/list", Listed, async (ep: PhotosEndpoint, req) => {
      const objects = []
      for await (const e of ep.bucket(req.query.get("bucket")).list(req.query.get("prefix") ?? "")) {
        objects.push({ name: e.name, length: e.length, lastKept: e.lastKept.toString() })
      }
      return { objects }
    }),
    remove: del("/objects", Done, async (ep: PhotosEndpoint, req) => {
      await ep.bucket(req.query.get("bucket")).delete(required(req.query.get("name")))
      return done
    }),
    signed: get("/signed", s.string, (ep: PhotosEndpoint, req) => {
      const access = req.query.get("access") === "keep" ? "keep" : "read"
      const seconds = req.query.get("seconds")
      const span = seconds === null ? undefined : Number(seconds)
      return ep.bucket(req.query.get("bucket")).signedUrl(required(req.query.get("name")), access, span)
    }),
    url: get("/url", s.string, (ep: PhotosEndpoint, req) =>
      ep.bucket(req.query.get("bucket")).url(required(req.query.get("name"))),
    ),
    // The entity keeps the name; the bucket keeps the object.
    recordPhoto: post("/listings/{id}/photos", RecordPhoto, Done, async (ep: PhotosEndpoint, req, request) => {
      await ep.storage.default.put(request.name, utf8.encode(request.text), { contentType: request.contentType })
      return ep.client.of(Listing, req.params.id).call(Listing.handlers.recordPhoto).invoke(request.name)
    }),
  }
}

function required(name: string | null): string {
  if (name === null) throw new HttpProblem(400, "the query parameter 'name' is required")
  return name
}
```

**Rust**

```rust
/// An endpoint over the service's buckets: keep, read, list, describe, delete, and record a photo.
pub struct PhotosEndpoint;

impl PhotosEndpoint {
    /// The bucket `bucket` names in the query, or the unnamed one.
    fn bucket(request: &Request) -> Result<Bucket, HttpProblem> {
        let storage = request
            .context()
            .storage()
            .ok_or_else(|| HttpProblem::new(500, "an endpoint has buckets"))?;
        Ok(match request.query("bucket") {
            Some(name) => storage.bucket(name),
            None => storage.default(),
        })
    }

    fn name(request: &Request) -> Result<&str, HttpProblem> {
        request
            .query("name")
            .ok_or_else(|| HttpProblem::new(400, "the query needs 'name'"))
    }

    fn keep(request: &Request, keep: KeepObject) -> Result<Done, HttpProblem> {
        Self::bucket(request)?.put(&keep.name, keep.text.as_bytes(), &keep.content_type)?;
        Ok(Done)
    }

    /// The object as it was kept, under its own content type.
    fn read(request: &Request) -> Result<Response, HttpProblem> {
        let name = Self::name(request)?;
        let object = Self::bucket(request)?
            .get(name)?
            .ok_or_else(|| HttpProblem::new(404, format!("no object '{name}'")))?;
        Ok(Response::bytes(object.content_type, object.body))
    }

    fn stat(request: &Request) -> Result<Described, HttpProblem> {
        let name = Self::name(request)?;
        let info = Self::bucket(request)?
            .stat(name)?
            .ok_or_else(|| HttpProblem::new(404, format!("no object '{name}'")))?;
        Ok(Described {
            name: name.to_string(),
            content_type: info.content_type,
            length: info.length,
        })
    }

    fn list(request: &Request) -> Result<Vec<String>, HttpProblem> {
        let prefix = request.query("prefix").unwrap_or_default();
        let entries = Self::bucket(request)?.list(prefix)?;
        Ok(entries.into_iter().map(|e| e.name).collect())
    }

    fn remove(request: &Request) -> Result<Done, HttpProblem> {
        Self::bucket(request)?.delete(Self::name(request)?)?;
        Ok(Done)
    }

    /// Keeps the bytes, then gives the entity the name: the entity never sees the bytes.
    fn photo(request: &Request, keep: KeepObject) -> Result<Done, HttpProblem> {
        Self::bucket(request)?.put(&keep.name, keep.text.as_bytes(), &keep.content_type)?;
        Ok(request
            .client()
            .invoke(Listing, request.path("id"), "record-photo", keep.name)?)
    }

    fn photos(request: &Request) -> Result<ListingState, HttpProblem> {
        Ok(request
            .client()
            .invoke(Listing, request.path("id"), "photos", ())?)
    }
}

impl Endpoint for PhotosEndpoint {
    const ENDPOINT_ID: &'static str = "PhotosEndpoint";
    const PREFIX: &'static str = "/storage";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<PhotosEndpoint> {
        Routes::new()
            .post("/objects", PhotosEndpoint::keep)
            .get("/objects", PhotosEndpoint::read)
            .get("/stat", PhotosEndpoint::stat)
            .get("/list", PhotosEndpoint::list)
            .delete("/objects", PhotosEndpoint::remove)
            .post("/photos/{id}", PhotosEndpoint::photo)
            .get("/photos/{id}", PhotosEndpoint::photos)
    }
}
```

**Go**

```go
// bucketOf is the bucket the request names with ?bucket=, or the unnamed bucket.
func bucketOf(ctx context.Context, req *ankka.Request) (ankka.Bucket, error) {
	store := ankka.StorageFrom(ctx)
	if store == nil {
		return nil, ankka.Refuse(ankka.Unavailable, "this service has no object storage")
	}
	if req.Query.Has("bucket") {
		return store.Bucket(req.Query.Get("bucket"))
	}
	return store.Default()
}

// PhotosEndpoint is an endpoint over the service's buckets: keep, read, list, describe, delete,
// sign and address. A Go route reads its body whole, so an upload of any size goes through a
// signed URL instead.
var PhotosEndpoint = ankka.NewEndpoint("/storage", ankka.AllowAll(),
	// Keeps the body under the name, as the content type it was sent as.
	ankka.Put("/objects/{name}", func(ctx context.Context, req *ankka.Request, body string) (ankka.Done, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ankka.Done{}, err
		}
		contentType := req.ContentType
		if contentType == "" {
			contentType = "application/octet-stream"
		}
		return ankka.Done{}, bucket.Put(ctx, req.Param("name"), strings.NewReader(body), int64(len(body)), contentType)
	}),
	// The object's bytes as they were kept, under their own content type; 404 for none.
	ankka.Get("/objects/{name}", func(ctx context.Context, req *ankka.Request) (ankka.RawResponse, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ankka.RawResponse{}, err
		}
		obj, err := bucket.Get(ctx, req.Param("name"))
		if err != nil || obj == nil {
			return ankka.RawResponse{}, notFoundUnless(err, "no object '"+req.Param("name")+"'")
		}
		data, err := io.ReadAll(obj.Body)
		return ankka.RawResponse{Status: 200, ContentType: obj.ContentType, Body: data}, err
	}),
	// What is kept under the name, without its bytes.
	ankka.Get("/objects/{name}/info", func(ctx context.Context, req *ankka.Request) (ObjectInfo, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ObjectInfo{}, err
		}
		info, err := bucket.Stat(ctx, req.Param("name"))
		if err != nil || info == nil {
			return ObjectInfo{}, notFoundUnless(err, "no object '"+req.Param("name")+"'")
		}
		return ObjectInfo{Name: req.Param("name"), ContentType: info.ContentType, Length: info.Length}, nil
	}),
	// Every name under the prefix, as the store pages through them.
	ankka.Get("/objects", func(ctx context.Context, req *ankka.Request) ([]string, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return nil, err
		}
		names := []string{}
		for entry, err := range bucket.List(ctx, req.Query.Get("prefix")) {
			if err != nil {
				return nil, err
			}
			names = append(names, entry.Name)
		}
		return names, nil
	}),
	ankka.Delete("/objects/{name}", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ankka.Done{}, err
		}
		return ankka.Done{}, bucket.Delete(ctx, req.Param("name"))
	}),
	// A URL a browser may use for a quarter of an hour to read the object, computed here: no
	// request reaches the store, and the bytes never pass through the service.
	ankka.Get("/objects/{name}/url", func(ctx context.Context, req *ankka.Request) (string, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return "", err
		}
		return bucket.SignedURL(req.Param("name"), ankka.AccessRead, 0)
	}),
	// Keeps the bytes and gives the listing the name: the entity records what the bucket holds.
	ankka.Post("/listings/{listing}/photos/{name}", func(ctx context.Context, req *ankka.Request,
		body string) (ankka.Done, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ankka.Done{}, err
		}
		name := req.Param("listing") + "/" + req.Param("name")
		if err := bucket.Put(ctx, name, strings.NewReader(body), int64(len(body)), "image/png"); err != nil {
			return ankka.Done{}, err
		}
		return ankka.Invoke[ankka.Done](ctx, "listing", req.Param("listing"), "record-photo", name)
	}),
)
```

`putStream` and `Streamed` are an endpoint's streamed bodies; [HTTP endpoints](http-endpoints.md#streamed-bodies)
says what bounds them.

In Python, an endpoint, a consumer, a timed action, an agent and a workflow step reach the client as
`self.storage`, and every call is awaited; a body may be bytes or an async iterable of bytes with its
length. In TypeScript the same components have `storage`, and a body may be a `Uint8Array` or a
`ReadableStream<Uint8Array>` with its length. In Rust, `ctx.storage()` answers the client, or `None` in
an entity, a view and a workflow's command handler; a module's put and get carry a whole object of at
most 4,000,000 bytes, and anything larger goes through a signed URL. In Go, `ankka.StorageFrom(ctx)`
answers the client, or `nil` for an entity's, a view's or a workflow command's context, and a put reads
its body from an `io.Reader` of a declared length.

A Python, TypeScript or Go service speaks to its store itself, from the same variables, so an object
never passes through the platform's own program on its way.

## A data subject's objects

The platform cannot see what a service writes to a bucket, so an object about a person is the service's to
erase. Keep each [data subject's](personal-data.md) objects under that subject's **subject prefix**,
`subjects/`, the data subject with every `/` written `%2F`, and `/` (`subjects/player%2F8c1f/` for
`player/8c1f`). Build it with `ObjectRules.subjectPrefix` in Scala, `subject_prefix` in Python,
`subjectPrefix` in TypeScript or `ankka.SubjectPrefix` in Go; no data subject's prefix holds another's.

`erase(subject)` on a bucket deletes every object under the prefix, and on a store that keeps versions
every version of each, keeps everything else, and answers how many objects it removed and when they are
gone for good: at once, or after the store's soft-delete window when `ANKKA_S3_SOFT_DELETE_DAYS` gives one.
Call it from the service's [erasure handler](personal-data.md#the-erasure-handler), which runs again after
each erasure for the re-run window, so an object written under the prefix late is erased too.

## Names

An object's name is 1 to 1024 bytes as UTF-8. Its segments are separated by `/`; it does not begin with
`/`, has no empty segment, no segment `.` or `..` and no control character. Spaces, punctuation and
letters outside ASCII are kept as they are. A prefix follows the same rule, except that it may be empty
and may end in `/`. Every SDK refuses the same names for the same reasons, as `BadRequest`.

There are no directories. `listings/42/front.jpg` is one name; listing `listings/42/` finds it, and
nothing exists at `listings/42` by itself.

## Signed URLs

A signed URL lets a browser upload straight to a signed bucket, or download from it, without the bytes
passing through the service. `access` is `read` or `keep`, and the span is fifteen minutes unless you say
otherwise, at most seven days. Making one sends no request: the URL is computed from the bucket's address
and the service's credential, so it is answered for a name nothing is kept under yet.

A keep through a signed URL is an HTTP `PUT` of the whole object with its `Content-Type`. Once it has
arrived the object is the bucket's like any other; nothing tells the service, so a browser that uploads
tells the service the name it used, and the service records it.

## A public bucket's address

`url(name)` on a public bucket answers the address a browser reads the object at with no signature: the
installation's address for the bucket, or a content delivery network's when the installation puts one in
front. The service never needs to know which. The address is computed, never fetched, and the object is
read from the store directly, never through the service.

## On your machine

A service run on your machine with no store's variables keeps its objects in a directory,
`.ankka/storage` under the directory it runs in, or wherever `ANKKA_STORAGE_DIR` says: one directory
per bucket (`_default` for the unnamed one), holding each object as one file under `objects/`, its name
encoded so that `/` is `%2F` (an object `a` and an object `a/b` can both be kept, as in a bucket), and its
content type, length and the time it was kept in a JSON file of the same name under `meta/`. The buckets
are those the `service.json` beside it declares. Every project
`ankka init` makes ignores `.ankka/` in its `.gitignore`.

When that `service.json` declares a bucket, the directory is made at start, and a service that cannot
write it does not start, naming the directory and why. A service that declares none (an image run
outside a cluster, say, with no `service.json` beside it) starts wherever it runs; its directory is made
at its first call, and a call that cannot be kept there fails as unavailable, naming the directory.

Signed URLs and public addresses work there too. They point at the service's own port, under
`/_ankka/storage/`, which only the runtime may serve: an endpoint declaring a path under `/_ankka` is
refused at start. A signed URL is checked against a key kept in the store's directory, so a URL stays
valid across a restart until its span ends. A deployed service serves nothing under `/_ankka/storage`;
there, a URL points at the store.

A service given a store's variables on your machine uses that store. `ANKKA_STORAGE_BACKEND` chooses
outright: `s3`, `directory` or `memory`.

## Testing

`AnkkaTestKit` gives the service a store in memory of its own: no object store and no Docker, and a
second kit sees nothing the first kept. It holds the buckets the `service.json` beside the test
declares; a test declares one with `declareBucket`, and reads and seeds the store through `kit.storage`:

**Scala**

```scala
kit.declareBucket("photos", BucketExposure.Public)
kit.storage.bucket("photos").putBytes("a/b.png", "png".getBytes(UTF_8), "image/png")
assertEquals(
  kit.service.storage.bucket("photos").stat("a/b.png").map(_.contentType),
  Some("image/png")
)
assert(kit.storage.bucket("photos").url("a/b.png").toString.endsWith("/a/b.png"))
```

**Java**

```java
/** Seeds the store the service reads through the kit, and reads back what it holds. */
static Optional<String> seeded(AnkkaTestKit kit) {
  // The Java kit declares no bucket of its own: a test seeds and reads the unnamed bucket the
  // service has (a named one comes from a descriptor the kit's settings point at).
  kit.storage().defaultBucket().putBytes("a/b.png", "png".getBytes(UTF_8), "image/png");
  return kit.storage().defaultBucket().stat("a/b.png").map(ObjectInfo::contentType);
}
```

**Kotlin**

```kotlin
storage().defaultBucket().putBytes("a/b.png", "png".toByteArray(), "image/png")
val described = get("/storage/stat?name=a/b.png")
assertEquals(200, described.status, described.body)
assertContains(described.body, "\"contentType\":\"image/png\"")
```

**Python**

```python
store = InMemoryBuckets({"photos": "public"})
kit = EndpointTestKit.of(PhotosEndpoint, storage=store)
asyncio.run(kit.storage.bucket("photos").put("b.png", b"png", content_type="image/png"))
assert kit.get("/photos/b.png/type").text() == "image/png"
assert kit.storage.bucket("photos").url("b.png").endswith("/b.png")
```

**TypeScript**

```ts
test("the endpoint keeps, describes, lists, signs, addresses and removes objects in the kit's store", async () => {
  const recorded: string[] = []
  const record = async (name: string) => (recorded.push(name), done)
  const client = { withMetadata: () => client, of: () => ({ call: () => ({ invoke: record }) }) }
  const store = new InMemoryBuckets({ buckets: { photos: "public" } })
  const kit = EndpointTestKit.of(PhotosEndpoint, client as unknown as ComponentClient, store)

  const png = { name: "a/b.png", contentType: "image/png", text: "png" }
  assert.equal((await kit.post("/storage/objects?bucket=photos", png)).status, 204)
  assert.equal((await kit.get("/storage/objects?bucket=photos&name=a/b.png")).text(), "png")
  const described = (await kit.get("/storage/stat?bucket=photos&name=a/b.png")).json() as typeof png & { length: number }
  assert.deepEqual([described.contentType, described.length], ["image/png", 3])
  const listed = (await kit.get("/storage/list?bucket=photos&prefix=a/")).json() as { objects: { name: string }[] }
  assert.deepEqual(listed.objects.map((o) => o.name), ["a/b.png"])
  assert.ok((await kit.get("/storage/url?bucket=photos&name=a/b.png")).text().endsWith("/a/b.png"))
  assert.match((await kit.get("/storage/signed?name=a/b.png&access=keep")).text(), /^http.*a\/b\.png/)
  // The test reads what the service kept from the same store.
  assert.equal((await kit.storage.bucket("photos").stat("a/b.png"))?.contentType, "image/png")

  const jpg = { name: "l1/front.jpg", contentType: "image/jpeg", text: "jpg" }
  assert.equal((await kit.post("/storage/listings/l1/photos", jpg)).status, 204)
  assert.deepEqual(recorded, ["l1/front.jpg"])
  assert.equal((await kit.storage.default.get("l1/front.jpg"))?.contentType, "image/jpeg")

  assert.equal((await kit.delete("/storage/objects?bucket=photos&name=a/b.png")).status, 204)
  assert.equal((await kit.get("/storage/objects?bucket=photos&name=a/b.png")).status, 404)
})
```

**Rust**

```rust
#[test]
fn what_a_route_keeps_is_in_the_store_and_what_the_test_seeds_the_route_reads() {
    let store = InMemoryStorage::new().declare("photos", BucketExposure::Public);
    let kit = EndpointTestKit::<PhotosEndpoint>::with_service(shopping_cart::listing::build());
    with_storage(&store, || {
        let kept = kit
            .request("POST", "/storage/photos/l-1")
            .query("bucket", "photos")
            .json(&png("a/b.png"))
            .send();
        assert_eq!(kept.status, 204, "{}", kept.text());
        // The bytes went to the bucket, the name to the entity.
        assert_eq!(
            store.get(Some("photos"), "a/b.png"),
            Some(("image/png".to_string(), b"png".to_vec()))
        );
        let listing: ListingState = kit.get("/storage/photos/l-1").json().unwrap();
        assert_eq!(listing.photos, vec!["a/b.png"]);

        store.seed(Some("photos"), "a/c.png", b"more", "image/png");
        let described: Described = kit
            .request("GET", "/storage/stat")
            .query("bucket", "photos")
            .query("name", "a/c.png")
            .send()
            .json()
            .unwrap();
        assert_eq!(
            (described.content_type.as_str(), described.length),
            ("image/png", 4)
        );
        let listed: Vec<String> = kit
            .request("GET", "/storage/list")
            .query("bucket", "photos")
            .query("prefix", "a/")
            .send()
            .json()
            .unwrap();
        assert_eq!(listed, vec!["a/b.png", "a/c.png"]);
    });
}
```

What the service kept survives `restartService()` and is gone when the kit stops. A test against a real
store starts the kit with `StorageBackendChoice.FromEnvironment` and the store's variables. The Python,
TypeScript and Rust kits hold the same store in memory, as `kit.storage`. A Go unit test hands a handler
`ankka.NewMemoryStorage()` through `ankka.WithStorage(ctx, store)`, and a Go integration test chooses the
store with `ANKKA_STORAGE_BACKEND`.

## When it fails

Every call refuses with a `CommandError`, and nothing is kept by a refused put:

| Code | When |
|---|---|
| `BadRequest` | a name, prefix, length or span breaks a rule; a stream that ends before its declared length; `signedUrl` on a bucket that is not signed; `url` on a bucket that is not public; a workflow's command handler calling |
| `Unavailable` | the store cannot be reached, refuses or does not answer in time; the service has no unnamed bucket, or no bucket of that name; the service has no credential to sign with |
| `Internal` | the store answered with something the client cannot read |

`get` and `stat` answer nothing for an object that is not there; they do not refuse.

## What it is not

There are no directories, no rename, no copy, no append and no partial writes: an object is kept and read
whole, and replaced whole. Nothing but an object's content type is kept beside it. A put declares its
length, so a body of unknown length is read into memory first by your code, or uploaded through a signed
URL. A gRPC endpoint has no storage client. The whole list is in [Limitations](../reference/limitations.md).
