// What a TypeScript service on a developer's machine serves at `/_ankka/storage/<bucket>/<name>` while its
// store is a directory (feature 055, research R12): the signed URLs and public addresses that store
// makes, answered through the sidecar like any route, so a browser reads and keeps through them as it
// would through an object store's. The runtime's `DirectoryStorageEndpoint` says the same in Scala.
//
// The token is the whole of the authority, as a signature is on an object store: a `GET` reads with a
// token for `read`, or with none on a public bucket; a `PUT` keeps with a token for `keep`. Anything
// else is `403`, and an object not there `404`. The sidecar matches a route by whole segments and an
// object's name holds slashes, so one route is declared per depth, up to `MAX_ADDRESS_SEGMENTS`; a
// deeper name travels as one segment with its slashes encoded.

import { binaryCodecs } from "../codec.ts"
import type { RequestContext } from "../context.ts"
import { Endpoint } from "../endpoint.ts"
import { Acl, get, HttpProblem, put, RawReply, type RouteRef } from "../routes.ts"
import { DirectoryBuckets, MAX_ADDRESS_SEGMENTS, STORAGE_PATH, type Bucket } from "../storage.ts"

/** A segment as the sidecar forwards it, encoded; one that is not valid encoding is taken as it is. */
function decoded(segment: string): string {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

type Params = Readonly<Record<string, string>>

/** The bucket's segment and the object's name a request's path parameters spell. */
function addressed(params: Params, depth: number): { segment: string; objectName: string } {
  const parts: string[] = []
  for (let i = 1; i <= depth; i++) parts.push(decoded(params[`n${i}`] ?? ""))
  return { segment: decoded(params.bucket ?? ""), objectName: parts.join("/") }
}

const NOWHERE = "no object at this address"
const UNSIGNED = "this address is not signed for that, or its signature has expired"

export class DirectoryStorageEndpoint extends Endpoint {
  static readonly prefix = STORAGE_PATH
  static readonly acl = Acl.allowAll
  /** @internal The SDK's own endpoint under `/_ankka`, which registration admits. */
  static readonly _sdkReserved = true
  static readonly routes: Readonly<Record<string, RouteRef<DirectoryStorageEndpoint, any, any, any>>> = (() => {
    const table: Record<string, RouteRef<DirectoryStorageEndpoint, any, any, any>> = {}
    for (let depth = 1; depth <= MAX_ADDRESS_SEGMENTS; depth++) {
      const template = "/{bucket}" + Array.from({ length: depth }, (_, i) => `/{n${i + 1}}`).join("")
      table[`read${depth}`] = get(template, binaryCodecs.bytes, (ep: DirectoryStorageEndpoint, req: RequestContext<any>) =>
        ep.read(req.params as Params, depth) as unknown as Promise<Uint8Array>,
      )
      table[`keep${depth}`] = put(template, binaryCodecs.bytes, binaryCodecs.bytes, (ep: DirectoryStorageEndpoint, req: RequestContext<any>, body: Uint8Array) =>
        ep.keep(req.params as Params, depth, body) as unknown as Promise<Uint8Array>,
      )
    }
    return Object.freeze(table)
  })()

  #store(segment: string): { store: DirectoryBuckets; bucket: Bucket; segment: string } {
    const store = this.storage
    if (!(store instanceof DirectoryBuckets)) throw new HttpProblem(404, NOWHERE)
    const found = store.bySegment(segment)
    if (!found) throw new HttpProblem(404, NOWHERE)
    return { store, bucket: found.bucket, segment: found.segment }
  }

  #signedFor(store: DirectoryBuckets, segment: string, objectName: string, access: string): boolean {
    const q = this.request.query
    const expires = Number(q.get("expires") ?? "")
    const token = q.get("token")
    return q.get("access") === access && token !== null && Number.isInteger(expires) && store.admits(segment, objectName, access, expires, token)
  }

  async read(params: Params, depth: number): Promise<RawReply> {
    const { segment, objectName } = addressed(params, depth)
    const { store, bucket } = this.#store(segment)
    const open = bucket.exposure === "public" && bucket.name !== undefined
    if (!open && !this.#signedFor(store, segment, objectName, "read")) throw new HttpProblem(403, UNSIGNED)
    const object = await bucket.get(objectName)
    if (!object) throw new HttpProblem(404, `no object '${objectName}'`)
    return new RawReply(200, object.contentType, await object.bytes())
  }

  async keep(params: Params, depth: number, body: Uint8Array): Promise<RawReply> {
    const { segment, objectName } = addressed(params, depth)
    const { store, bucket } = this.#store(segment)
    if (!this.#signedFor(store, segment, objectName, "keep")) throw new HttpProblem(403, UNSIGNED)
    const contentType = this.request.headers.get("content-type") ?? "application/octet-stream"
    await bucket.put(objectName, body, { contentType })
    return new RawReply(200, "text/plain; charset=utf-8", new Uint8Array())
  }
}
