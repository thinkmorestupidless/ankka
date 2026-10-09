package com.thinkmorestupidless.ankka.runtime

import org.apache.pekko.persistence.typed.{EventAdapter, EventSeq, SnapshotAdapter}

/**
 * A host's adapters, run inside its service's scope: where its domain values are encoded and
 * decoded.
 */
private[runtime] object ScopedAdapters:

  def events[E, J](scope: ServiceScope, inner: EventAdapter[E, J]): EventAdapter[E, J] =
    new EventAdapter[E, J]:
      def toJournal(event: E): J     = scope.within(inner.toJournal(event))
      def manifest(event: E): String = inner.manifest(event)
      def fromJournal(record: J, manifest: String): EventSeq[E] =
        scope.within(inner.fromJournal(record, manifest))

  def snapshots[S](scope: ServiceScope, inner: SnapshotAdapter[S]): SnapshotAdapter[S] =
    new SnapshotAdapter[S]:
      def toJournal(state: S): Any  = scope.within(inner.toJournal(state))
      def fromJournal(from: Any): S = scope.within(inner.fromJournal(from))
