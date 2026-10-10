// Where a topic source starts, and what a view or consumer that reads a topic may declare about it.

/**
 * Where a topic source begins, the first time its consumer group reads a partition. Applied once and
 * committed at once, so a restart, a rebalance or a new instance resumes where the group got to,
 * never from here again.
 */
export type StartFrom =
  | { readonly kind: "earliest" }
  | { readonly kind: "latest" }
  | { readonly kind: "at"; readonly atMillis: number }

export const StartFrom = Object.freeze({
  /** The oldest message the broker still holds. */
  earliest: Object.freeze({ kind: "earliest" }) as StartFrom,
  /** After the newest: only what is published from then on. */
  latest: Object.freeze({ kind: "latest" }) as StartFrom,
  /** The first message published at or after `when`. */
  at(when: Date): StartFrom {
    const atMillis = when instanceof Date ? when.getTime() : Number.NaN
    if (!Number.isFinite(atMillis)) throw new TypeError(`StartFrom.at needs a valid Date, not ${String(when)}`)
    return Object.freeze({ kind: "at", atMillis })
  },
})

/** The first protocol in which a process can declare a start position or a version. */
export const START_POSITION_PROTOCOL: readonly [number, number] = [1, 7]

export function isStartFrom(value: unknown): value is StartFrom {
  if (typeof value !== "object" || value === null) return false
  const kind = (value as { kind?: unknown }).kind
  if (kind === "earliest" || kind === "latest") return true
  return kind === "at" && Number.isFinite((value as { atMillis?: unknown }).atMillis)
}

/** The first protocol in which a process can declare a view's queries. */
export const DECLARED_QUERY_PROTOCOL: readonly [number, number] = [1, 13]

function olderThan(protocolVersion: string, [wantMajor, wantMinor]: readonly [number, number]): boolean {
  const [major, minor] = protocolVersion.split(".").map((part) => Number.parseInt(part, 10))
  if (!Number.isFinite(major) || !Number.isFinite(minor)) return false
  return major < wantMajor || (major === wantMajor && minor < wantMinor)
}

/** Whether a sidecar speaking `protocolVersion` would ignore a start position and a version. */
export function olderThanStartPositions(protocolVersion: string): boolean {
  return olderThan(protocolVersion, START_POSITION_PROTOCOL)
}

/** Whether a sidecar speaking `protocolVersion` would ignore a view's declared queries. */
export function olderThanDeclaredQueries(protocolVersion: string): boolean {
  return olderThan(protocolVersion, DECLARED_QUERY_PROTOCOL)
}

/** Whether a sidecar speaking `protocolVersion` would not know a watched query. */
export function olderThanViewStreams(protocolVersion: string): boolean {
  return olderThan(protocolVersion, [1, 15])
}

/** Whether a sidecar speaking `protocolVersion` would ignore a topic's contract, broker and parallel reading. */
export function olderThanContracts(protocolVersion: string): boolean {
  return olderThan(protocolVersion, [1, 14])
}
