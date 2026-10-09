// The erasure handler (protocol 1.15): what a service does of its own when a data subject of its
// project is erased. The platform does everything it can see before the handler runs — the subject's
// key destroyed, its view rows redacted, its agent sessions forgotten. The handler is for what the
// platform cannot see, chiefly the subject's objects in the service's bucket, which
// `ctx.objects.erase()` removes under `subjects/<subject>/`. It runs on every application and again on
// each later one, so it must be safe to run twice.

import type { ComponentClient } from "./client.ts"

/** The prefix a service keeps a data subject's objects under. */
export function objectPrefix(subject: string): string {
  return `subjects/${subject}/`
}

export interface ErasedObjects {
  readonly count: number
  /** When the deletion is final on the object store. */
  readonly finalAt: Date
}

export interface ObjectErasure {
  /** Every object under the subject's prefix, every version where the store keeps versions. */
  erase(): Promise<ErasedObjects>
}

export interface ErasureContext {
  readonly subject: string
  readonly erasureId: string
  /** Whether the handler has run for this erasure before. */
  readonly reapply: boolean
  readonly objects: ObjectErasure
  readonly client: ComponentClient
}

export type ErasureOutcome = { readonly kind: "done"; readonly detail?: string; readonly objects?: ErasedObjects } | { readonly kind: "failed"; readonly reason: string }

export type ErasureHandler = (ctx: ErasureContext) => Promise<ErasureOutcome>

export const ErasureOutcomes = Object.freeze({
  done(detail = "", objects?: ErasedObjects): ErasureOutcome {
    return objects ? { kind: "done", detail, objects } : { kind: "done", detail }
  },
  /** Not done: the handler is run again on the next application. */
  failed(reason: string): ErasureOutcome {
    return { kind: "failed", reason }
  },
})
