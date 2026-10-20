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
compile, and in the other SDKs the attribute does not exist. A put or a get waits on the store, which an
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
virtual threads (Scala) or call through their own runtime (Python, TypeScript), so the wait holds nothing
else up.

An entity that records a photo's name, in Scala:

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

And an endpoint over the service's buckets. The upload route passes the request's body to the bucket as
it arrives and the download route writes the object as it is read; the last route keeps the bytes and
gives the entity the name:

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

`putStream` and `Streamed` are an endpoint's streamed bodies; [HTTP endpoints](http-endpoints.md#streamed-bodies)
says what bounds them.

In Python, an endpoint, a consumer, a timed action, an agent and a workflow step reach the client as
`self.storage`, and every call is awaited. A body may be bytes or an async iterable of bytes with its
length:

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

In TypeScript the same components have `storage`, and a body may be a `Uint8Array` or a
`ReadableStream<Uint8Array>` with its length:

```typescript
// The service's buckets. `bucket` names one (absent: the unnamed bucket) and `name` the object; `op`
// is get, stat, list (under `prefix`), url or signed-url (`access` read or keep). A post keeps its body
// under `name`; a post with `rules` set to `name` or `prefix` instead tries its body as an object's
// name or a listing's prefix, and keeps nothing.
keepObject: post("/storage", s.string, StorageRecord, (ep: ConformanceEndpoint, req, body) =>
  storageCall(ep, async (bucket) => {
    const rule = req.query.get("rules")
    if (rule === "prefix") {
      for await (const _ of bucket.list(body)) break
    } else if (rule !== null) {
      await bucket.stat(body)
    } else {
      await bucket.put(req.query.get("name") ?? "", new TextEncoder().encode(body), {
        contentType: req.headers.get("content-type") ?? "application/octet-stream",
      })
    }
    return storageRecord("ok")
  }),
),
readObject: get("/storage", StorageRecord, (ep: ConformanceEndpoint, req) =>
  storageCall(ep, async (bucket) => {
    const name = req.query.get("name") ?? ""
    const op = req.query.get("op") ?? ""
    switch (op) {
      case "get": {
        const object = await bucket.get(name)
        if (!object) return storageRecord("absent")
        const bytes = await object.bytes()
        return storageRecord("ok", { contentType: object.contentType, text: new TextDecoder().decode(bytes), length: BigInt(bytes.length) })
      }
      case "stat": {
        const info = await bucket.stat(name)
        return info ? storageRecord("ok", { contentType: info.contentType, length: BigInt(info.length) }) : storageRecord("absent")
      }
      case "list": {
        const names: string[] = []
        for await (const entry of bucket.list(req.query.get("prefix") ?? "")) names.push(entry.name)
        return storageRecord("ok", { names })
      }
      case "url":
        return storageRecord("ok", { url: bucket.url(name) })
      case "signed-url":
        return storageRecord("ok", { url: bucket.signedUrl(name, req.query.get("access") === "keep" ? "keep" : "read") })
      default:
        return storageRecord("refused", { code: ErrorCode.BadRequest, message: `no storage op '${op}'` })
    }
  }),
),
removeObject: del("/storage", StorageRecord, (ep: ConformanceEndpoint, req) =>
  storageCall(ep, async (bucket) => {
    await bucket.delete(req.query.get("name") ?? "")
    return storageRecord("ok")
  }),
),
```

A Python or TypeScript service speaks to its store itself, from the same variables, so an object never
passes through the platform's own program on its way.

In Rust, `ctx.storage()` answers the client, or `None` in an entity, a view and a workflow's command
handler. A module's put and get carry a whole object of at most 4,000,000 bytes; anything larger goes
through a signed URL:

```rust
// The service's buckets. `bucket` names one (absent: the unnamed bucket) and `name` the object;
// `op` is get, stat, list (under `prefix`), url or signed-url (`access` read or keep). A post
// keeps its body under `name`; a post with `rules` set to `name` or `prefix` instead tries its
// body as an object's name or a listing's prefix, and keeps nothing. Every answer is a record,
// refusals included.
fn bucket(request: &Request) -> Result<ankka::Bucket, StorageError> {
    let storage = request
        .context()
        .storage()
        .ok_or_else(|| StorageError::Internal("an endpoint has buckets".into()))?;
    Ok(match request.query("bucket") {
        Some(name) => storage.bucket(name),
        None => storage.default(),
    })
}

fn keep_object(request: &Request, body: String) -> Result<StorageRecord, HttpProblem> {
    let kept = Self::bucket(request).and_then(|bucket| match request.query("rules") {
        Some("prefix") => bucket.list(&body).map(|_| ()),
        Some(_) => bucket.stat(&body).map(|_| ()),
        None => {
            let content_type = match request.content_type() {
                "" => "application/octet-stream",
                given => given,
            };
            let name = request.query("name").unwrap_or_default();
            bucket.put(name, body.as_bytes(), content_type)
        }
    });
    Ok(StorageRecord::of(kept.map(|()| StorageRecord::ok())))
}

fn read_object(request: &Request) -> Result<StorageRecord, HttpProblem> {
    let name = request.query("name").unwrap_or_default();
    let answered = Self::bucket(request).and_then(|bucket| {
        match request.query("op").unwrap_or_default() {
            "get" => bucket.get(name).map(|found| match found {
                None => StorageRecord::absent(),
                Some(o) => StorageRecord {
                    content_type: o.content_type,
                    text: String::from_utf8_lossy(&o.body).into_owned(),
                    length: o.length,
                    ..StorageRecord::ok()
                },
            }),
            "stat" => bucket.stat(name).map(|found| match found {
                None => StorageRecord::absent(),
                Some(i) => StorageRecord {
                    content_type: i.content_type,
                    length: i.length,
                    ..StorageRecord::ok()
                },
            }),
            "list" => bucket
                .list(request.query("prefix").unwrap_or_default())
                .map(|entries| StorageRecord {
                    names: entries.into_iter().map(|e| e.name).collect(),
                    ..StorageRecord::ok()
                }),
            "url" => bucket.url(name).map(StorageRecord::url),
            "signed-url" => {
                let access = match request.query("access") {
                    Some("keep") => SignedAccess::Keep,
                    _ => SignedAccess::Read,
                };
                let span = Duration::of_seconds(ankka::storage::DEFAULT_SIGNED_URL_SECONDS);
                bucket
                    .signed_url(name, access, span)
                    .map(StorageRecord::url)
            }
            other => Err(StorageError::BadRequest(format!("no storage op '{other}'"))),
        }
    });
    Ok(StorageRecord::of(answered))
}

fn remove_object(request: &Request) -> Result<StorageRecord, HttpProblem> {
    let removed = Self::bucket(request)
        .and_then(|bucket| bucket.delete(request.query("name").unwrap_or_default()));
    Ok(StorageRecord::of(removed.map(|()| StorageRecord::ok())))
}
```

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

```scala
kit.declareBucket("photos", BucketExposure.Public)
kit.storage.bucket("photos").putBytes("a/b.png", "png".getBytes(UTF_8), "image/png")
assertEquals(
  kit.service.storage.bucket("photos").stat("a/b.png").map(_.contentType),
  Some("image/png")
)
assert(kit.storage.bucket("photos").url("a/b.png").toString.endsWith("/a/b.png"))
```

What the service kept survives `restartService()` and is gone when the kit stops. A test against a real
store starts the kit with `StorageBackendChoice.FromEnvironment` and the store's variables. The Python,
TypeScript and Rust kits hold the same store in memory, as `kit.storage`.

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
