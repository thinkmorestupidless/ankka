package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.runtime.remote.{Payload, RemoteWorkflowHost}
import com.thinkmorestupidless.ankka.sdk.WorkflowLifecycle

/**
 * One record of a source's journal, as a view or a consumer is told of it: a change it is handed,
 * the source's deletion, or nothing at all.
 *
 * An entity's journal and a workflow's hold different records, and a projection reads either with
 * the same handler; what differs is only how a record becomes one of these. That is said once per
 * kind of journal, in [[ChangeReader]], so every host — a plain view, a keyed view, a consumer, in
 * process or in another — reads a record the same way.
 */
private[ankka] enum SourceChange:
  /** A change: the payload to decode, and for a workflow source its standing. */
  case Changed(payload: Payload, standing: Option[WorkflowLifecycle])

  /** The source was deleted: the deletion handler runs. */
  case Deleted

  /** A record that is no change: a time-to-live set, or a workflow record that holds no state. */
  case Skip

/** How a projection's handler reads one record of the journal it follows. */
private[ankka] trait ChangeReader[A]:
  def read(record: A): SourceChange

private[ankka] object ChangeReader:

  /** An event sourced entity's journal: an event, a deletion, or a time-to-live set. */
  val journal: ChangeReader[JournalRecord] = record =>
    record.kind match
      case JournalRecord.KindDomain =>
        SourceChange.Changed(
          Payload(Payload.contentTypeFor(record.manifest), record.manifest, record.payload),
          None
        )
      case JournalRecord.KindDeleted => SourceChange.Deleted
      // A TTL being set is a storage fact, not a domain change: nothing to tell a reader.
      case _ => SourceChange.Skip

  /** A Scala workflow's journal: its state is the bytes its own serializer wrote. */
  def workflow(stateManifest: String): ChangeReader[WorkflowRecord] = record =>
    WorkflowChanges.read(
      record,
      bytes => Payload(Payload.contentTypeFor(stateManifest), stateManifest, bytes)
    )

  /**
   * A process's or a module's workflow: the engine journals the process's state with its manifest
   * and content type packed in front, because the process, not the descriptor, knows them.
   */
  val remoteWorkflow: ChangeReader[WorkflowRecord] = record =>
    WorkflowChanges.read(record, RemoteWorkflowHost.unpack)

/**
 * A workflow's record as a change (spec 046, D2). Only a recorded state is a change; it carries the
 * standing the engine stamped on it, the standing once the whole effect that recorded it was
 * applied. A record written before the engine stamped standings carries none, and its standing is
 * `Unknown` rather than one worked out after the fact.
 */
private[ankka] object WorkflowChanges:

  def read(record: WorkflowRecord, unpack: Array[Byte] => Payload): SourceChange =
    record.kind match
      case WorkflowRecord.KindStateUpdated =>
        SourceChange.Changed(unpack(record.state), Some(standingOf(record)))
      case WorkflowRecord.KindDeleted => SourceChange.Deleted
      case _                          => SourceChange.Skip

  /** The standing a `state` record carries, or `Unknown` for one written before the stamp. */
  def standingOf(record: WorkflowRecord): WorkflowLifecycle =
    Option(record.standing).flatten match
      case Some(s) =>
        WorkflowLifecycle(
          s.status,
          Option(s.step).filter(_.nonEmpty),
          Option(s.retries).getOrElse(Map.empty),
          Option(s.failure).filter(_.nonEmpty)
        )
      case None => WorkflowLifecycle.unknown
