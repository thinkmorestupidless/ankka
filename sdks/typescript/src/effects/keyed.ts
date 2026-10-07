// Effects for a keyed view: the rows one change writes and deletes, each named by its key.

import type { EffectLike } from "./common.ts"

/** One row written under its key, or the key's row deleted. */
export type RowChange<Row> = { readonly key: string; readonly row: Row } | { readonly key: string; readonly deleted: true }

/**
 * What a keyed view's handler says to do with one change: rows to write and rows to delete, in order.
 * Empty is "nothing". The platform deletes no row the handler did not name; a row is moved by deleting
 * its old key and writing its new one in the same effect.
 */
export type KeyedViewEffect<Row> = { readonly kind: "rows"; readonly changes: readonly RowChange<Row>[] } & EffectLike<never>

function rows<Row>(changes: readonly RowChange<Row>[]): KeyedViewEffect<Row> {
  return Object.freeze({ kind: "rows", changes: Object.freeze([...changes]) })
}

function checkedKey(key: unknown): string {
  if (typeof key !== "string" || key === "") throw new TypeError("a row key is a string of one character or more")
  return key
}

export class KeyedViewEffects<Row> {
  updateRow(key: string, row: Row): KeyedViewEffect<Row> {
    return rows([{ key: checkedKey(key), row }])
  }
  deleteRow(key: string): KeyedViewEffect<Row> {
    return rows([{ key: checkedKey(key), deleted: true }])
  }
  /** Several rows written, each under its key. */
  updateRows(entries: Iterable<readonly [string, Row]>): KeyedViewEffect<Row> {
    return rows([...entries].map(([key, row]) => ({ key: checkedKey(key), row })))
  }
  deleteRows(keys: Iterable<string>): KeyedViewEffect<Row> {
    return rows([...keys].map((key) => ({ key: checkedKey(key), deleted: true as const })))
  }
  ignore(): KeyedViewEffect<Row> {
    return rows([])
  }
  /** Several effects as one, in the order given. */
  all(...effects: readonly KeyedViewEffect<Row>[]): KeyedViewEffect<Row> {
    return rows(effects.flatMap((e) => e.changes))
  }
}

/**
 * The final change for each key, a later change to a key winning, keys in the order they first
 * appear; `undefined` is a deletion. How the test kit applies an effect, as the runtime does.
 */
export function reduceRowChanges<Row>(changes: readonly RowChange<Row>[]): [string, Row | undefined][] {
  const last = new Map<string, Row | undefined>()
  for (const change of changes) last.set(change.key, "deleted" in change ? undefined : change.row)
  return [...last.entries()]
}
