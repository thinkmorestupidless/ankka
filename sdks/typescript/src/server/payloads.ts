// The bridge between a codec and the protocol's `Payload`: `{ content_type, manifest, data }`.

import { create } from "@bufbuild/protobuf"
import { PayloadSchema, type Payload } from "../_proto/ankka/protocol/v1/payload_pb.ts"
import type { Codec } from "../codec.ts"

export function encodePayload<T>(codec: Codec<T>, value: T): Payload {
  return create(PayloadSchema, { contentType: codec.contentType, manifest: codec.manifest, data: codec.encode(value) })
}

/** Decodes a payload with `codec`. An absent payload is the empty payload, which is what `unit` and `done` are. */
export function decodePayload<T>(codec: Codec<T>, payload: Payload | undefined): T {
  return codec.decode(payload?.data ?? new Uint8Array())
}

export const EMPTY_PAYLOAD: Payload = create(PayloadSchema, { contentType: "application/octet-stream", manifest: "unit", data: new Uint8Array() })
