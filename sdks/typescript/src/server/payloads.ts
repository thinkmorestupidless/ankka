// The bridge between a codec and the protocol's `Payload`: `{ content_type, manifest, data }`.
//
// A payload is what a serializer writes, so a personal field in it is the personal envelope, bound to
// the payload's manifest (feature 042): both directions run under that scope. The codec is synchronous,
// so the keys it needs are fetched first: `prefetchPayload` before a decode, `prefetchValue` before an
// encode. Each is a no-op when the process has no keyring or nothing personal is in sight.

import { create } from "@bufbuild/protobuf"
import { PayloadSchema, type Payload } from "../_proto/ankka/protocol/v1/payload_pb.ts"
import type { Codec } from "../codec.ts"
import { withinScope } from "../personal.ts"
import { prefetchForDecode, prefetchForEncode } from "../keyring.ts"

export function encodePayload<T>(codec: Codec<T>, value: T): Payload {
  return create(PayloadSchema, { contentType: codec.contentType, manifest: codec.manifest, data: withinScope(codec.manifest, () => codec.encode(value)) })
}

/** Decodes a payload with `codec`. An absent payload is the empty payload, which is what `unit` and `done` are. */
export function decodePayload<T>(codec: Codec<T>, payload: Payload | undefined): T {
  return withinScope(codec.manifest, () => codec.decode(payload?.data ?? new Uint8Array()))
}

/** Fetches the keys every personal envelope in these payloads needs. Never throws. */
export async function prefetchPayload(...payloads: (Payload | undefined)[]): Promise<void> {
  for (const p of payloads) await prefetchForDecode(p?.data)
}

/** Fetches the keys every personal field in these values needs to be written. Never throws. */
export async function prefetchValue(...values: unknown[]): Promise<void> {
  for (const v of values) await prefetchForEncode(v)
}

export const EMPTY_PAYLOAD: Payload = create(PayloadSchema, { contentType: "application/octet-stream", manifest: "unit", data: new Uint8Array() })
