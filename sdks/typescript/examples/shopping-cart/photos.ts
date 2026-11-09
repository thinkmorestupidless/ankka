// A listing of photos: the entity keeps each photo's name, the bucket keeps its bytes. An endpoint over
// the service's buckets: keep, read, list, describe, delete, sign and address.
import {
  Acl,
  Done,
  done,
  Endpoint,
  EventSourcedEntity,
  HttpProblem,
  command,
  del,
  get,
  jsonCodec,
  post,
  query,
  s,
  type Bucket,
  type Infer,
} from "ankka"

// docs:start entity
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
// docs:end entity

// docs:start endpoint
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
// docs:end endpoint
