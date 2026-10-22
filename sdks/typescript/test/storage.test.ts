// The storage client as a TypeScript service sees it: the rules every SDK shares, the signer against
// AWS's published vectors, the contract over the in-memory and directory stores, the S3 store's wire
// form against a fake store on loopback, the store a process is given, who is given one, the workflow's
// guard, and the endpoint that answers a directory store's URLs. The conformance suite's `storage.*`
// cases hold the same through a real sidecar.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { createServer, type IncomingMessage, type Server as HttpServer } from "node:http"
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import type { AddressInfo } from "node:net"
import { CommandError } from "../src/effects/common.ts"
import { Agent } from "../src/agent.ts"
import { AutonomousAgent } from "../src/autonomous.ts"
import { Consumer } from "../src/consumer.ts"
import { Endpoint } from "../src/endpoint.ts"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { GraphConsumer } from "../src/graph.ts"
import { KeyValueEntity } from "../src/keyValueEntity.ts"
import { KeyedView } from "../src/keyedView.ts"
import { TimedAction } from "../src/timedAction.ts"
import { View } from "../src/view.ts"
import { Workflow } from "../src/workflow.ts"
import { Acl, post } from "../src/routes.ts"
import { s, Done, done } from "../src/schema.ts"
import { step } from "../src/handlers.ts"
import { Ankka } from "../src/service.ts"
import { renderSpec } from "../src/spec.ts"
import { DirectoryStorageEndpoint } from "../src/server/storageEndpoint.ts"
import { EndpointTestKit } from "../src/testkit/unit.ts"
import { AnkkaTestKit } from "../src/testkit/integration.ts"
import { WorkflowTestKit } from "../src/testkit/kinds.ts"
import { Credentials, EMPTY_PAYLOAD, encode, presign, sign, SignedRequest } from "../src/sigv4.ts"
import {
  chooseStorage,
  declaredBuckets,
  DirectoryBuckets,
  InMemoryBuckets,
  noStorage,
  ObjectRules,
  parseListing,
  S3Bucket,
  S3Buckets,
  s3Settings,
  type Bucket,
  type Buckets,
} from "../src/storage.ts"

interface Row {
  unit: string
  repeat: number
  accepted: boolean
  why: string
  says?: string
}
const rules = JSON.parse(readFileSync(join(import.meta.dirname, "..", "proto", "fixtures", "storage", "rules.json"), "utf8")) as {
  objectNames: Row[]
  prefixes: Row[]
  bucketNames: Row[]
  signedUrl: { maxSeconds: number; defaultSeconds: number }
}

const utf8 = new TextEncoder()
const text = (b: Uint8Array) => new TextDecoder().decode(b)

function streamOf(...chunks: string[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(c) {
      for (const chunk of chunks) c.enqueue(utf8.encode(chunk))
      c.close()
    },
  })
}

async function names(bucket: Bucket, prefix = ""): Promise<string[]> {
  const out: string[] = []
  for await (const e of bucket.list(prefix)) out.push(e.name)
  return out
}

async function refusal(call: () => unknown): Promise<CommandError> {
  try {
    await call()
  } catch (e) {
    if (e instanceof CommandError) return e
    throw e
  }
  assert.fail("the call was not refused")
}

describe("the rules", () => {
  test("every object name in the fixture gets its verdict and its words", () => {
    assert.ok(rules.objectNames.length > 0)
    for (const row of rules.objectNames) {
      const p = ObjectRules.nameProblem(row.unit.repeat(row.repeat))
      assert.equal(p === undefined, row.accepted, row.why)
      if (!row.accepted) assert.ok(p!.includes(row.says!), `${row.why}: ${p}`)
    }
  })

  test("every prefix in the fixture gets its verdict and its words", () => {
    assert.ok(rules.prefixes.length > 0)
    for (const row of rules.prefixes) {
      const p = ObjectRules.prefixProblem(row.unit.repeat(row.repeat))
      assert.equal(p === undefined, row.accepted, row.why)
      if (!row.accepted) assert.ok(p!.includes(row.says!), `${row.why}: ${p}`)
    }
  })

  test("every bucket name in the fixture gets its verdict and its words", () => {
    assert.ok(rules.bucketNames.length > 0)
    for (const row of rules.bucketNames) {
      const p = ObjectRules.bucketNameProblem(row.unit.repeat(row.repeat))
      assert.equal(p === undefined, row.accepted, row.why)
      if (!row.accepted) assert.ok(p!.includes(row.says!), `${row.why}: ${p}`)
    }
  })

  test("a signed URL's bound and default are the fixture's", () => {
    assert.equal(ObjectRules.MAX_SIGNED_URL_SECONDS, rules.signedUrl.maxSeconds)
    assert.equal(ObjectRules.DEFAULT_SIGNED_URL_SECONDS, rules.signedUrl.defaultSeconds)
    assert.equal(ObjectRules.spanProblem(rules.signedUrl.maxSeconds), undefined)
    assert.match(ObjectRules.spanProblem(rules.signedUrl.maxSeconds + 1)!, /at most 7 days/)
    assert.match(ObjectRules.spanProblem(0)!, /positive/)
  })
})

describe("the signer, against AWS's vectors", () => {
  test("a signed request matches AWS's get-vanilla vector", () => {
    const headers = sign(
      new SignedRequest("GET", "https://example.amazonaws.com", "/"),
      {},
      EMPTY_PAYLOAD,
      new Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
      "us-east-1",
      "service",
      new Date("2015-08-30T12:36:00Z"),
    )
    assert.equal(headers["x-amz-date"], "20150830T123600Z")
    assert.equal(
      headers.Authorization,
      "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, " +
        "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
    )
  })

  const s3Credentials = new Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
  const s3Day = new Date("2013-05-24T00:00:00Z")

  test("a signed S3 get matches the S3 documentation's example", () => {
    const headers = sign(
      new SignedRequest("GET", "https://examplebucket.s3.amazonaws.com", "/test.txt"),
      { Range: "bytes=0-9", "x-amz-content-sha256": EMPTY_PAYLOAD },
      EMPTY_PAYLOAD,
      s3Credentials,
      "us-east-1",
      "s3",
      s3Day,
    )
    assert.equal(
      headers.Authorization,
      "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
        "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
        "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
    )
  })

  test("a presigned S3 get matches the S3 documentation's example", () => {
    const url = new URL(presign(new SignedRequest("GET", "https://examplebucket.s3.amazonaws.com", "/test.txt"), s3Credentials, "us-east-1", "s3", 86400, s3Day))
    const raw = Object.fromEntries(url.search.slice(1).split("&").map((p) => p.split("=", 2) as [string, string]))
    assert.equal(raw["X-Amz-Signature"], "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404")
    assert.equal(raw["X-Amz-Credential"], "AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request")
    assert.equal(raw["X-Amz-Expires"], "86400")
    assert.equal(url.host, "examplebucket.s3.amazonaws.com")
    assert.equal(url.pathname, "/test.txt")
  })

  test("a name's space and plus are encoded, its slashes kept in the path", () => {
    const request = new SignedRequest("GET", "http://localhost:3900", "/shop.listings/listings/42/front photo+1.jpg", [["prefix", "a/b c"]])
    assert.equal(request.canonicalPath, "/shop.listings/listings/42/front%20photo%2B1.jpg")
    assert.equal(request.canonicalQuery, "prefix=a%2Fb%20c")
    assert.equal(request.host, "localhost:3900")
    assert.equal(request.url, "http://localhost:3900/shop.listings/listings/42/front%20photo%2B1.jpg?prefix=a%2Fb%20c")
  })

  test("a name outside ASCII is encoded as its UTF-8 bytes", () => {
    assert.equal(encode("café", true), "caf%C3%A9")
  })

  test("credentials never print their secret", () => {
    assert.ok(!String(s3Credentials).includes("wJalr"))
    assert.ok(!JSON.stringify({ c: s3Credentials }).includes("wJalr"))
  })
})

/** The contract every store keeps, run over each. */
function contract(label: string, make: () => { buckets: Buckets; done?: () => void }) {
  describe(`the contract, over ${label}`, () => {
    let store: Buckets
    let finish: (() => void) | undefined
    before(() => {
      const made = make()
      store = made.buckets
      finish = made.done
    })
    after(() => finish?.())

    test("an object kept is read back with its content type and length", async () => {
      await store.default.put("notes/a.jpg", utf8.encode("hello"), { contentType: "image/jpeg" })
      const got = await store.default.get("notes/a.jpg")
      assert.ok(got)
      assert.equal(got.contentType, "image/jpeg")
      assert.equal(got.length, 5)
      assert.equal(text(await got.bytes()), "hello")
      const info = await store.default.stat("notes/a.jpg")
      assert.deepEqual([info?.name, info?.contentType, info?.length], ["notes/a.jpg", "image/jpeg", 5])
    })

    test("a stream is kept to its declared length", async () => {
      await store.default.put("streamed/one", streamOf("he", "llo", " world"), { contentType: "text/plain", length: 5 })
      assert.equal(text(await (await store.default.get("streamed/one"))!.bytes()), "hello")
    })

    test("a stream that ends short keeps nothing, and the old object stays", async () => {
      await store.default.put("short/one", utf8.encode("old"), { contentType: "text/plain" })
      const e = await refusal(() => store.default.put("short/one", streamOf("ab"), { contentType: "text/plain", length: 10 }))
      assert.equal(e.code, "BAD_REQUEST")
      assert.match(e.message, /ended after 2 of its declared 10 bytes/)
      assert.equal(text(await (await store.default.get("short/one"))!.bytes()), "old")
    })

    test("a stream with no declared length is refused", async () => {
      const e = await refusal(() => store.default.put("nolength", streamOf("x"), { contentType: "text/plain" }))
      assert.equal(e.code, "BAD_REQUEST")
    })

    test("an empty object is kept", async () => {
      await store.default.put("empty", new Uint8Array(), { contentType: "application/octet-stream" })
      assert.equal((await store.default.stat("empty"))?.length, 0)
      assert.equal((await (await store.default.get("empty"))!.bytes()).length, 0)
    })

    test("a listing answers the names under a prefix, in order", async () => {
      for (const n of ["listed/b", "listed/a", "elsewhere/c"]) await store.default.put(n, utf8.encode("x"), { contentType: "text/plain" })
      assert.deepEqual(await names(store.default, "listed/"), ["listed/a", "listed/b"])
    })

    test("get and stat of nothing are undefined; a second deletion is not an error", async () => {
      await store.default.put("gone/one", utf8.encode("x"), { contentType: "text/plain" })
      await store.default.delete("gone/one")
      await store.default.delete("gone/one")
      assert.equal(await store.default.get("gone/one"), undefined)
      assert.equal(await store.default.stat("gone/one"), undefined)
    })

    test("a named bucket keeps its own objects", async () => {
      await store.bucket("photos").put("front.jpg", utf8.encode("named"), { contentType: "image/jpeg" })
      assert.equal(text(await (await store.bucket("photos").get("front.jpg"))!.bytes()), "named")
      assert.equal(await store.default.get("front.jpg"), undefined)
    })

    test("a bucket the service does not have is unavailable, naming its own", async () => {
      const e = await refusal(() => store.bucket("videos"))
      assert.equal(e.code, "UNAVAILABLE")
      assert.match(e.message, /no bucket named 'videos'; its descriptor names 'photos', 'scans'/)
    })

    test("a name that breaks the rule is a bad request, before anything is kept", async () => {
      for (const call of [
        () => store.default.put("/front.jpg", utf8.encode("x"), { contentType: "text/plain" }),
        () => store.default.get("a//b"),
        () => store.default.stat(""),
        () => store.default.delete(".."),
        () => store.default.list("/x"),
      ]) {
        const e = await refusal(call)
        assert.equal(e.code, "BAD_REQUEST", e.message)
      }
    })

    test("a public bucket answers an address; the unnamed and a private bucket refuse one", async () => {
      assert.ok(store.bucket("photos").url("listings/42/front.jpg").endsWith("/listings/42/front.jpg"))
      for (const call of [() => store.default.url("front.jpg"), () => store.bucket("scans").url("front.jpg")]) {
        assert.equal((await refusal(call)).code, "BAD_REQUEST")
      }
    })

    test("a signed URL is made for the unnamed bucket, not for a private or public one, and for at most seven days", async () => {
      const url = new URL(store.default.signedUrl("notes/a.jpg", "read"))
      assert.equal(url.searchParams.get("access"), "read")
      const expires = Number(url.searchParams.get("expires"))
      assert.ok(Math.abs(expires - (Date.now() / 1000 + 900)) < 5, `${expires}`)
      assert.match((await refusal(() => store.bucket("scans").signedUrl("x", "read"))).message, /bucket 'scans' is private; a signed URL is made for a signed bucket/)
      assert.match((await refusal(() => store.bucket("photos").signedUrl("x", "keep"))).message, /bucket 'photos' is public/)
      assert.equal((await refusal(() => store.default.signedUrl("x", "read", 604_801))).code, "BAD_REQUEST")
    })
  })
}

const DECLARED = { unnamed: true, buckets: { photos: "public", scans: "private" } } as const

contract("the in-memory store", () => ({ buckets: new InMemoryBuckets({ buckets: DECLARED.buckets }) }))

const directoryRoot = mkdtempSync(join(tmpdir(), "ankka-storage-"))
contract("the directory store", () => ({
  buckets: new DirectoryBuckets(directoryRoot, { declared: DECLARED, addressBase: () => "http://127.0.0.1:9000" }),
  done: () => rmSync(directoryRoot, { recursive: true, force: true }),
}))

describe("the directory store's layout", () => {
  test("is the runtime's: objects/ and meta/ per bucket, a long name under its hash, the key in the root", async () => {
    const root = mkdtempSync(join(tmpdir(), "ankka-layout-"))
    try {
      const store = new DirectoryBuckets(root)
      await store.default.put("a b/c.jpg", utf8.encode("abc"), { contentType: "image/jpeg" })
      assert.deepEqual(readdirSync(join(root, "_default", "objects")), ["a%20b%2Fc.jpg"])
      const meta = JSON.parse(readFileSync(join(root, "_default", "meta", "a%20b%2Fc.jpg.json"), "utf8")) as Record<string, unknown>
      assert.deepEqual([meta.name, meta.contentType, meta.length], ["a b/c.jpg", "image/jpeg", 3])
      assert.match(String(meta.lastKept), /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/)
      await store.bucket("any-name").put("x".repeat(300), utf8.encode("long"), { contentType: "text/plain" })
      const [hashed] = readdirSync(join(root, "any-name", "objects"))
      assert.match(hashed!, /^h-[0-9a-f]{64}$/)
      assert.deepEqual(await names(store.bucket("any-name")), ["x".repeat(300)])
      // With no service.json every name is a private bucket; a malformed one is refused.
      assert.equal(store.bucket("any-name").exposure, "private")
      assert.equal((await refusal(() => store.bucket("Bad_Name"))).code, "BAD_REQUEST")

      const url = new URL(store.default.signedUrl("a b/c.jpg", "read"))
      assert.equal(url.pathname, "/_ankka/storage/_default/a%20b/c.jpg")
      assert.ok(existsSync(join(root, ".key")))
      // A store opened again on the same root honours a URL the first one signed.
      const again = new DirectoryBuckets(root)
      const expires = Number(url.searchParams.get("expires"))
      assert.equal(again.admits("_default", "a b/c.jpg", "read", expires, url.searchParams.get("token")!), true)
      assert.equal(again.admits("_default", "a b/c.jpg", "keep", expires, url.searchParams.get("token")!), false)
      assert.equal(again.admits("_default", "a b/c.jpg", "read", Math.floor(Date.now() / 1000) - 1, again.token("_default", "a b/c.jpg", "read", Math.floor(Date.now() / 1000) - 1)), false)
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })
})

// ── S3, against a fake store ────────────────────────────────────────────────────

interface Seen {
  method: string
  url: string
  headers: IncomingMessage["headers"]
  body: Buffer
  complete: boolean
}

class FakeS3 {
  readonly seen: Seen[] = []
  readonly objects = new Map<string, { type: string; body: Buffer }>()
  refuseWith: number | undefined
  #server: HttpServer | undefined
  port = 0

  async start(): Promise<void> {
    this.#server = createServer((req, res) => {
      const chunks: Buffer[] = []
      const record: Seen = { method: req.method ?? "", url: req.url ?? "", headers: req.headers, body: Buffer.alloc(0), complete: false }
      this.seen.push(record)
      req.on("data", (c: Buffer) => chunks.push(c))
      req.on("end", () => {
        record.body = Buffer.concat(chunks)
        record.complete = true
        if (this.refuseWith) {
          res.writeHead(this.refuseWith, { "content-type": "application/xml" })
          res.end("<Error><Code>AccessDenied</Code><Message>no</Message></Error>")
          return
        }
        const url = new URL(req.url ?? "/", "http://fake")
        const path = decodeURIComponent(url.pathname)
        if (req.method === "PUT") {
          this.objects.set(path, { type: String(req.headers["content-type"]), body: record.body })
          res.writeHead(200).end()
        } else if (req.method === "GET" && url.searchParams.get("list-type") === "2") {
          const bucket = path.slice(1)
          const prefix = url.searchParams.get("prefix") ?? ""
          const all = [...this.objects.keys()].filter((k) => k.startsWith(`/${bucket}/${prefix}`)).sort()
          const start = Number(url.searchParams.get("continuation-token") ?? "0")
          const page = all.slice(start, start + 2)
          const more = start + 2 < all.length
          const contents = page
            .map((k) => `<Contents><Key>${k.slice(bucket.length + 2).replaceAll("&", "&amp;")}</Key><Size>${this.objects.get(k)!.body.length}</Size><LastModified>2026-10-19T10:00:00.000Z</LastModified></Contents>`)
            .join("")
          res.writeHead(200, { "content-type": "application/xml" })
          res.end(`<?xml version="1.0"?><ListBucketResult><IsTruncated>${more}</IsTruncated>${contents}${more ? `<NextContinuationToken>${start + 2}</NextContinuationToken>` : ""}</ListBucketResult>`)
        } else if (req.method === "GET" || req.method === "HEAD") {
          const o = this.objects.get(path)
          if (!o) {
            res.writeHead(404, { "content-type": "application/xml" }).end(req.method === "HEAD" ? undefined : "<Error><Code>NoSuchKey</Code></Error>")
            return
          }
          res.writeHead(200, { "content-type": o.type, "content-length": String(o.body.length), "last-modified": "Mon, 19 Oct 2026 10:00:00 GMT" })
          res.end(req.method === "HEAD" ? undefined : o.body)
        } else if (req.method === "DELETE") {
          this.objects.delete(path)
          res.writeHead(204).end()
        } else res.writeHead(405).end()
      })
    })
    await new Promise<void>((r) => this.#server!.listen(0, "127.0.0.1", () => r()))
    this.port = (this.#server.address() as AddressInfo).port
  }

  async stop(): Promise<void> {
    this.#server?.closeAllConnections()
    await new Promise<void>((r) => this.#server?.close(() => r()) ?? r())
  }
}

describe("the S3 store", () => {
  const fake = new FakeS3()
  let buckets: S3Buckets
  before(async () => {
    await fake.start()
    buckets = new S3Buckets(
      s3Settings({
        ANKKA_S3_ENDPOINT: `http://127.0.0.1:${fake.port}`,
        ANKKA_S3_REGION: "garage",
        ANKKA_S3_BUCKET: "shop-default",
        ANKKA_S3_ACCESS_KEY: "GKtest",
        ANKKA_S3_SECRET_KEY: "s3cr3t",
        ANKKA_S3_PUBLIC_ENDPOINT: "https://objects.example.com",
        ANKKA_S3_BUCKET_PHOTOS: "shop-photos",
        ANKKA_S3_EXPOSURE_PHOTOS: "public",
        ANKKA_S3_PUBLIC_URL_PHOTOS: "https://photos.example.com/",
      }),
    )
  })
  after(() => fake.stop())

  test("a put is path-style, signed with an unsigned payload, and carries its length rather than chunks", async () => {
    fake.seen.length = 0
    await buckets.default.put("listings/42/front photo.jpg", streamOf("hel", "lo"), { contentType: "image/jpeg", length: 5 })
    const put = fake.seen.at(-1)!
    assert.equal(put.method, "PUT")
    assert.equal(put.url, "/shop-default/listings/42/front%20photo.jpg")
    assert.equal(put.headers["content-length"], "5")
    assert.equal(put.headers["transfer-encoding"], undefined)
    assert.equal(put.headers["x-amz-content-sha256"], "UNSIGNED-PAYLOAD")
    assert.equal(put.headers["content-type"], "image/jpeg")
    assert.match(String(put.headers.authorization), /^AWS4-HMAC-SHA256 Credential=GKtest\/\d{8}\/garage\/s3\/aws4_request, SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, Signature=[0-9a-f]{64}$/)
    assert.equal(put.body.toString(), "hello")
    const got = await buckets.default.get("listings/42/front photo.jpg")
    assert.deepEqual([got?.contentType, got?.length, text(await got!.bytes())], ["image/jpeg", 5, "hello"])
    assert.equal(got?.lastKept.toString(), "2026-10-19T10:00:00Z")
  })

  test("a stream that ends short is aborted, and the store keeps nothing", async () => {
    fake.seen.length = 0
    const e = await refusal(() => buckets.default.put("short", streamOf("ab"), { contentType: "text/plain", length: 10 }))
    assert.match(e.message, /ended after 2 of its declared 10 bytes/)
    await new Promise((r) => setTimeout(r, 50))
    assert.equal(fake.objects.has("/shop-default/short"), false)
    assert.ok(fake.seen.every((s) => !s.complete), "the store saw a complete request")
  })

  test("stat, delete and nothing", async () => {
    await buckets.default.put("stat/one", utf8.encode("four"), { contentType: "image/png" })
    const info = await buckets.default.stat("stat/one")
    assert.deepEqual([info?.contentType, info?.length], ["image/png", 4])
    await buckets.default.delete("stat/one")
    await buckets.default.delete("stat/one")
    assert.equal(await buckets.default.stat("stat/one"), undefined)
    assert.equal(await buckets.default.get("stat/one"), undefined)
  })

  test("a listing is read page by page, by continuation token", async () => {
    for (const n of ["paged/c", "paged/a", "paged/e&f", "paged/b", "other/x"]) await buckets.default.put(n, utf8.encode("x"), { contentType: "text/plain" })
    fake.seen.length = 0
    assert.deepEqual(await names(buckets.default, "paged/"), ["paged/a", "paged/b", "paged/c", "paged/e&f"])
    const listings = fake.seen.filter((s) => s.url.includes("list-type=2"))
    assert.equal(listings.length, 2)
    assert.match(listings[1]!.url, /continuation-token=2/)
  })

  test("a refusal from the store is unavailable, naming its code", async () => {
    fake.refuseWith = 403
    try {
      const e = await refusal(() => buckets.default.get("anything"))
      assert.equal(e.code, "UNAVAILABLE")
      assert.match(e.message, /refused the request \(403\): AccessDenied/)
    } finally {
      fake.refuseWith = undefined
    }
  })

  test("a store that cannot be reached is unavailable", async () => {
    const nowhere = new S3Bucket({ endpoint: new URL("http://127.0.0.1:1"), region: "r", bucket: "b", credentials: new Credentials("a", "b"), publicEndpoint: undefined }, undefined, "private", undefined, 2000)
    const e = await refusal(() => nowhere.get("x"))
    assert.equal(e.code, "UNAVAILABLE")
    assert.match(e.message, /cannot be reached/)
  })

  test("a signed URL is presigned at the public endpoint, and a public bucket's address is its base", () => {
    const url = new URL(buckets.default.signedUrl("a b.jpg", "keep", 60))
    assert.equal(url.origin, "https://objects.example.com")
    assert.equal(url.pathname, "/shop-default/a%20b.jpg")
    assert.equal(url.searchParams.get("X-Amz-Expires"), "60")
    assert.ok(url.searchParams.get("X-Amz-Signature"))
    assert.equal(buckets.bucket("photos").url("listings/42/front.jpg"), "https://photos.example.com/listings/42/front.jpg")
    assert.equal(buckets.bucket("photos").exposure, "public")
  })

  test("no credential is unavailable, naming objectStorageCredential", async () => {
    const keyless = new S3Buckets(s3Settings({ ANKKA_S3_ENDPOINT: `http://127.0.0.1:${fake.port}`, ANKKA_S3_REGION: "r", ANKKA_S3_BUCKET: "b" }))
    const e = await refusal(() => keyless.default.get("x"))
    assert.equal(e.code, "UNAVAILABLE")
    assert.match(e.message, /objectStorageCredential/)
  })

  test("a listing's XML is read with its entities", () => {
    const page = parseListing("<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>a&amp;b&#x2F;c</Key><Size>3</Size></Contents></ListBucketResult>")
    assert.deepEqual(page.entries.map((e) => [e.name, e.length]), [["a&b/c", 3]])
    assert.equal(page.next, undefined)
  })
})

describe("the store a process is given", () => {
  const base = { ANKKA_S3_ENDPOINT: "http://garage:3900", ANKKA_S3_REGION: "garage", ANKKA_S3_ACCESS_KEY: "a", ANKKA_S3_SECRET_KEY: "b" }

  test("the variables' families: the unnamed bucket, a made named bucket and a supplied one", () => {
    const settings = s3Settings({
      ...base,
      ANKKA_S3_BUCKET: "u",
      ANKKA_S3_BUCKET_OLD_PHOTOS: "p",
      ANKKA_S3_EXPOSURE_OLD_PHOTOS: "signed",
      ANKKA_S3_ENDPOINT_MINE: "https://s3.example.com",
      ANKKA_S3_REGION_MINE: "eu",
      ANKKA_S3_BUCKET_MINE: "m",
      ANKKA_S3_ACCESS_KEY_MINE: "c",
      ANKKA_S3_SECRET_KEY_MINE: "d",
    })
    assert.equal(settings.unnamed?.bucket, "u")
    assert.deepEqual(settings.named.map((n) => [n.name, n.exposure, n.target.bucket, n.target.endpoint.host]), [
      ["mine", "supplied", "m", "s3.example.com"],
      ["old-photos", "signed", "p", "garage:3900"],
    ])
  })

  test("a family that cannot be read stops the start, naming the variable", () => {
    assert.throws(() => s3Settings({ ...base, ANKKA_S3_REGION: "" }), /ANKKA_S3_REGION is not set/)
    assert.throws(() => s3Settings({ ANKKA_S3_BUCKET_X: "x" }), /ANKKA_S3_ENDPOINT is not set/)
    assert.throws(() => s3Settings({ ...base, ANKKA_S3_BUCKET_X: "x", ANKKA_S3_EXPOSURE_X: "public" }), /ANKKA_S3_PUBLIC_URL_X is not set/)
    assert.throws(() => s3Settings({ ...base, ANKKA_S3_BUCKET_X: "x", ANKKA_S3_EXPOSURE_X: "open" }), /one of private, signed or public/)
    assert.throws(() => chooseStorage({ ANKKA_STORAGE_BACKEND: "s3" }), /ANKKA_S3_ENDPOINT is not set/)
    assert.throws(() => chooseStorage({ ANKKA_STORAGE_BACKEND: "tape" }), /one of s3, directory or memory/)
  })

  test("S3 when a bucket is given, a directory on a laptop, nothing on the platform", () => {
    const dir = mkdtempSync(join(tmpdir(), "ankka-choice-"))
    try {
      assert.equal(chooseStorage({ ...base, ANKKA_S3_BUCKET: "u" }, { serviceDirectory: dir }).backend, "s3")
      const local = chooseStorage({ ANKKA_STORAGE_DIR: join(dir, "store") }, { serviceDirectory: dir })
      assert.equal(local.backend, "directory")
      assert.equal((local.buckets as DirectoryBuckets).root, join(dir, "store"))
      assert.equal(existsSync(join(dir, "store")), false, "the directory store makes nothing until it keeps something")
      const deployed = chooseStorage({ KUBERNETES_SERVICE_HOST: "10.0.0.1" }, { serviceDirectory: dir })
      assert.equal(deployed.backend, "unavailable")
      assert.equal(deployed.buckets, noStorage)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  test("the memory and directory stores refuse the buckets service.json does not declare", async () => {
    const dir = mkdtempSync(join(tmpdir(), "ankka-declared-"))
    try {
      writeFileSync(join(dir, "service.json"), JSON.stringify({ name: "x", service: { buckets: [{ name: "photos", exposure: "public" }, { name: "mine", supplied: { endpoint: "https://s3" } }] } }))
      assert.deepEqual(declaredBuckets(dir), { unnamed: false, buckets: { photos: "public", mine: "supplied" } })
      for (const backend of ["memory", "directory"]) {
        const chosen = chooseStorage({ ANKKA_STORAGE_BACKEND: backend, ANKKA_STORAGE_DIR: join(dir, "s") }, { serviceDirectory: dir })
        assert.equal(chosen.buckets.bucket("photos").exposure, "public")
        assert.match((await refusal(() => chosen.buckets.default)).message, /provisionObjectStorage/)
        assert.match((await refusal(() => chosen.buckets.bucket("videos"))).message, /names 'mine', 'photos'/)
      }
      writeFileSync(join(dir, "service.json"), JSON.stringify({ service: { buckets: [{ name: "p", exposure: "open" }] } }))
      assert.throws(() => declaredBuckets(dir), /'p' has exposure 'open'/)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})

describe("who is given the buckets", () => {
  test("an entity or a view is given none", () => {
    for (const cls of [EventSourcedEntity, KeyValueEntity, View, KeyedView]) assert.equal("storage" in cls.prototype, false, cls.name)
    const neverCalled = (es: EventSourcedEntity<any, any>, kv: KeyValueEntity<any>, view: View<any, any>) => {
      // @ts-expect-error an event sourced entity has no storage
      void es.storage
      // @ts-expect-error a key value entity has no storage
      void kv.storage
      // @ts-expect-error a view has no storage
      void view.storage
    }
    void neverCalled
  })

  test("an endpoint, a consumer, a graph consumer, a timed action, an agent, an autonomous agent and a workflow are given them", () => {
    for (const cls of [Endpoint, Consumer, GraphConsumer, TimedAction, Agent, AutonomousAgent, Workflow]) assert.equal("storage" in cls.prototype, true, cls.name)
  })

  test("a component answers the process's store until a test gives it one", () => {
    class Plain extends Endpoint {
      static readonly prefix = "/plain"
      static readonly acl = Acl.allowAll
      static readonly routes = {}
    }
    const ep = new Plain()
    assert.equal(ep.storage, noStorage)
    const given = new InMemoryBuckets()
    ep.storage = given
    assert.equal(ep.storage, given)
  })

  test("a workflow reaches its buckets in a step and not in a command handler", async () => {
    class Archive extends Workflow<string> {
      static readonly componentId = "archive-storage-test"
      static readonly state = s.string
      static readonly handlers = {}
      static readonly steps = { keep: step("keep", (w: Archive) => w.keep()) }
      emptyState(): string {
        return ""
      }
      async keep() {
        await this.storage.default.put("archived", utf8.encode("kept in a step"), { contentType: "text/plain" })
        return this.stepEffects.updateState("kept").thenEnd()
      }
    }
    const workflow = new Archive()
    workflow.storage = new InMemoryBuckets()
    assert.throws(() => workflow.storage, (e: unknown) => e instanceof CommandError && e.code === "BAD_REQUEST" && e.message.includes("in a step, not in a command handler"))
    workflow._enterStep()
    assert.ok(workflow.storage instanceof InMemoryBuckets)

    const kit = WorkflowTestKit.of(Archive)
    await kit.runStep("keep")
    assert.equal(kit.state, "kept")
    assert.equal(text(await (await kit.storage.default.get("archived"))!.bytes()), "kept in a step")
  })

  test("a unit kit's endpoint keeps an object and the test reads it from kit.storage", async () => {
    class Uploads extends Endpoint {
      static readonly prefix = "/uploads"
      static readonly acl = Acl.allowAll
      static readonly routes = {
        upload: post("/{name}", s.string, Done, async (ep: Uploads, req, body) => {
          await ep.storage.bucket("photos").put(req.params.name, utf8.encode(body), { contentType: "text/plain" })
          return done
        }),
      }
    }
    const kit = EndpointTestKit.of(Uploads, undefined, new InMemoryBuckets({ buckets: { photos: "public" } }))
    assert.equal((await kit.post("/uploads/front.jpg", "pixels")).status, 204)
    assert.equal(text(await (await kit.storage.bucket("photos").get("front.jpg"))!.bytes()), "pixels")
  })

  test("an endpoint of the developer's under /_ankka is refused at registration", () => {
    class Sneaky extends Endpoint {
      static readonly prefix = "/_ankka/storage"
      static readonly acl = Acl.allowAll
      static readonly routes = {}
    }
    assert.throws(() => Ankka.service().register(Sneaky).validate(), /'\/_ankka\/storage' is under \/_ankka/)
  })
})

describe("the directory store's endpoint", () => {
  const root = mkdtempSync(join(tmpdir(), "ankka-endpoint-"))
  const store = new DirectoryBuckets(root, { declared: { unnamed: true, buckets: { photos: "public", scans: "private" } }, addressBase: () => "http://127.0.0.1:9000" })
  const kit = EndpointTestKit.of(DirectoryStorageEndpoint, undefined, store)
  const pathOf = (url: string) => {
    const u = new URL(url)
    return u.pathname + u.search
  }
  after(() => rmSync(root, { recursive: true, force: true }))

  test("is declared at /_ankka/storage, a route per depth", () => {
    const spec = renderSpec(Ankka.service().register(DirectoryStorageEndpoint).validate())
    const ep = spec.endpoints[0]!
    assert.equal(ep.prefix, "/_ankka/storage")
    assert.ok(ep.routes.some((r) => r.method === "GET" && r.template === "/{bucket}/{n1}/{n2}"))
    assert.ok(ep.routes.some((r) => r.method === "PUT" && r.template === "/{bucket}/{n1}" && r.hasBody))
  })

  test("a signed read answers the object with its content type", async () => {
    await store.default.put("listings/42/front photo.jpg", utf8.encode("jpeg bytes"), { contentType: "image/jpeg" })
    const r = await kit.get(pathOf(store.default.signedUrl("listings/42/front photo.jpg", "read")))
    assert.equal(r.status, 200, r.text())
    assert.equal(r.contentType, "image/jpeg")
    assert.equal(r.text(), "jpeg bytes")
  })

  test("an unsigned, wrongly signed or expired read is forbidden", async () => {
    const signed = new URL(store.default.signedUrl("listings/42/front photo.jpg", "read"))
    const unsigned = signed.pathname
    const wrong = `${signed.pathname}?access=read&expires=${signed.searchParams.get("expires")}&token=${"0".repeat(64)}`
    const keepToken = pathOf(store.default.signedUrl("listings/42/front photo.jpg", "keep"))
    const past = Math.floor(Date.now() / 1000) - 10
    const expired = `${signed.pathname}?access=read&expires=${past}&token=${store.token("_default", "listings/42/front photo.jpg", "read", past)}`
    for (const path of [unsigned, wrong, keepToken, expired]) assert.equal((await kit.get(path)).status, 403, path)
  })

  test("a read of nothing is not found", async () => {
    assert.equal((await kit.get(pathOf(store.default.signedUrl("never/there", "read")))).status, 404)
    assert.equal((await kit.get("/_ankka/storage/videos/x")).status, 404)
  })

  test("a signed keep keeps the body under its content type; a read token cannot keep", async () => {
    const keep = pathOf(store.default.signedUrl("uploads/new.png", "keep"))
    const r = await kit.put(keep, utf8.encode("png bytes"), { headers: { "content-type": "image/png" } })
    assert.equal(r.status, 200, r.text())
    const kept = await store.default.get("uploads/new.png")
    assert.deepEqual([kept?.contentType, text(await kept!.bytes())], ["image/png", "png bytes"])
    assert.equal((await kit.put(pathOf(store.default.signedUrl("uploads/other.png", "read")), utf8.encode("x"))).status, 403)
    assert.equal(await store.default.stat("uploads/other.png"), undefined)
  })

  test("a public bucket's address is read with no signature; a private one's is not", async () => {
    await store.bucket("photos").put("front.jpg", utf8.encode("public bytes"), { contentType: "image/jpeg" })
    await store.bucket("scans").put("front.jpg", utf8.encode("private bytes"), { contentType: "image/jpeg" })
    const r = await kit.get(pathOf(store.bucket("photos").url("front.jpg")))
    assert.equal(r.status, 200)
    assert.equal(r.text(), "public bytes")
    assert.equal((await kit.get("/_ankka/storage/scans/front.jpg")).status, 403)
  })
})

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"

// A browser reaching a TypeScript service's directory store through the real sidecar: the URLs the
// store signs are answered by the endpoint the process declares at /_ankka/storage, through the
// sidecar's HTTP port, for a name of several segments with a space and a plus in it.
describe("the directory store through the real sidecar", { skip: slow }, () => {
  test("a signed keep and a signed read through the sidecar, and an unsigned read refused", async () => {
    class Health extends Endpoint {
      static readonly prefix = "/health-of-storage"
      static readonly acl = Acl.allowAll
      static readonly routes = {}
    }
    const root = mkdtempSync(join(tmpdir(), "ankka-sidecar-storage-"))
    let base = ""
    const store = new DirectoryBuckets(root, { declared: { unnamed: true, buckets: { photos: "public" } }, addressBase: () => base })
    // A sidecar beside a process keeps no store of its own, so it serves the process's /_ankka/storage
    // with nothing said about storage.
    const kit = await AnkkaTestKit.start(Ankka.service().register(Health), { storage: store })
    try {
      base = kit.baseUrl
      const name = "listings/42/front photo+1.jpg"
      const keep = await fetch(store.default.signedUrl(name, "keep"), { method: "PUT", body: utf8.encode("through the sidecar"), headers: { "content-type": "image/jpeg" } })
      assert.equal(keep.status, 200, await keep.text())
      const kept = await store.default.get(name)
      assert.deepEqual([kept?.contentType, text(await kept!.bytes())], ["image/jpeg", "through the sidecar"])

      const read = await fetch(store.default.signedUrl(name, "read"))
      assert.equal(read.status, 200)
      assert.equal(read.headers.get("content-type"), "image/jpeg")
      assert.equal(await read.text(), "through the sidecar")

      assert.equal((await fetch(new URL(store.default.signedUrl(name, "read")).origin + new URL(store.default.signedUrl(name, "read")).pathname)).status, 403)

      await store.bucket("photos").put("front.jpg", utf8.encode("public"), { contentType: "image/jpeg" })
      const open = await fetch(store.bucket("photos").url("front.jpg"))
      assert.equal(open.status, 200)
      assert.equal(await open.text(), "public")
    } finally {
      await kit.stop()
      rmSync(root, { recursive: true, force: true })
    }
  })
})
