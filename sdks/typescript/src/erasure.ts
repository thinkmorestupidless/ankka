// The erasure handler (feature 042): what the platform cannot erase for the service, such as objects in
// a bucket. `service.erasureHandler(async (ctx, subject) => …)`; one per service. The sidecar calls it
// for each erased data subject and again on each re-run within the window, so it must be idempotent. A
// handler that throws is recorded as failed for that run and run again on the next.

import type { ComponentClient, Secrets } from "./client.ts"
import type { Metadata } from "./effects/common.ts"
import type { DataSubject } from "./personal.ts"
import type { Buckets } from "./storage.ts"
import type { Duration } from "./time.ts"

/** What a handler is given beside the subject. */
export interface ErasureContext {
  readonly client: ComponentClient
  readonly secrets: Secrets
  /** The service's buckets. */
  readonly storage: Buckets
  /** The erasure's sequence number in the keyring. */
  readonly erasureSeq: bigint
  /** Whether this is a re-run of a handler that already ran for this subject. */
  readonly rerun: boolean
  readonly metadata: Metadata
}

/** When what the handler erased is gone: at once, or after the store's own retention. */
export type Finality = { readonly kind: "immediate" } | { readonly kind: "after"; readonly after: Duration }

/** What a handler reports: how many objects it erased, and when they are gone. */
export interface ErasureReport {
  readonly objectsErased?: number | bigint
  readonly finality?: Finality
}

export type ErasureHandler = (ctx: ErasureContext, subject: DataSubject) => Promise<ErasureReport | void> | ErasureReport | void

/** The finality as the protocol carries it: `immediate`, or `after:` and an ISO-8601 duration. */
export function finalityToProto(finality: Finality | undefined): string {
  if (finality === undefined || finality.kind === "immediate") return "immediate"
  return `after:${finality.after.toString()}`
}
