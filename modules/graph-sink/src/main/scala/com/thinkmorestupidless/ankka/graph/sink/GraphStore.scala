package com.thinkmorestupidless.ankka.graph.sink

import com.thinkmorestupidless.ankka.core.graph.GraphDelta

/**
 * What a graph sink writes to: a store of nodes and relationships that applies deltas under the
 * contract's rules. An implementation keeps, for each element key, the element's version, whether
 * it is deleted, and its labels, properties and endpoints, and applies a delta thus:
 *
 *   - a merge of an element the store does not hold creates it at the delta's version;
 *   - a merge whose version is higher than the stored one replaces the element's labels and
 *     properties whole, clears its deletion, and takes the version;
 *   - an edge merge creates its endpoints as placeholders at version -1 when the store does not
 *     hold them, so a node's own delta, arriving later, replaces the placeholder;
 *   - a tombstone of an element the store does not hold creates it deleted at the delta's version;
 *   - a tombstone whose version is higher marks the element deleted, clears its labels and
 *     properties, and takes the version;
 *   - a delta whose version is equal to or lower than the stored one changes nothing: it is stale.
 *
 * One delta is applied atomically, so a store built twice from one topic holds the same elements. A
 * failure to apply is thrown: the sink then fails the change, and the delta is handed to it again.
 * `InMemoryGraphStore` is the reference, proven against the contract's fixtures; a store over a
 * database implements the same in that database's terms.
 */
trait GraphStore:

  /** Applies one delta under the rules above, or throws. */
  def apply(delta: GraphDelta): Unit

  /** Releases what the store holds open; the sink calls it when its service stops. */
  def close(): Unit = ()
