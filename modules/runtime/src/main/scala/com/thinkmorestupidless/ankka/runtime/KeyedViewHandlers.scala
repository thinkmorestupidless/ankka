package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.runtime.remote.Payload
import com.thinkmorestupidless.ankka.core.effect.{KeyedViewEffect, RowChanges}
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.query.{
  DeletedDurableState,
  DurableStateChange,
  UpdatedDurableState
}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.projection.r2dbc.scaladsl.{R2dbcHandler, R2dbcSession}
import org.apache.pekko.projection.scaladsl.Handler

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Future}

/**
 * What every keyed view host does with one change, whoever runs the handler: take the view's lock,
 * have the change handled, and write every row the handler named — all in the change's transaction
 * (`contracts/keyed-views.md`, E1–E6 and O1–O3).
 *
 * The view's advisory lock, the one a rebuild takes, is taken exclusively before anything else, by
 * every source's handler on every instance, and held until the transaction ends. So the view
 * handles one change at a time, and what a handler reads is what it writes over: nothing else can
 * commit to the table between. The lock is taken before the transaction's statement timeout is set,
 * so a change waiting its turn is not failed by the waiting; the handler then has the ask timeout
 * to answer, or its change fails and is handled again, and the lock is released.
 */
private[ankka] final class KeyedViewCore(val componentId: ComponentId, askTimeout: FiniteDuration)(
    using system: ActorSystem[?]
):

  private given ExecutionContext = system.executionContext

  val table: String = ViewDescriptor.tableFor(componentId)

  private val view = componentId.toString

  /**
   * What a change's transaction runs once the guard holds the view's lock and has found it at this
   * instance's version.
   */
  def opening: Vector[SqlFragment] = Vector(
    // As a query rather than a SET: a SET reports no row count, and the projection's session reads
    // one from every statement it is given. set_config with `true` is as local to the transaction.
    SqlFragment.raw(
      s"SELECT set_config('statement_timeout', '${askTimeout.toMillis.max(1L)}', true)"
    )
  )

  /**
   * The writes one effect makes, reduced through `RowChanges`, each row already encoded. Fails when
   * a key is empty or the rows weigh more than one change may carry.
   */
  def writes(changes: Vector[(String, Option[String])]): Either[String, Vector[SqlFragment]] =
    val size =
      changes.iterator.map((key, row) => key.length.toLong + row.fold(0L)(_.length.toLong)).sum
    if changes.exists(_._1.isEmpty) then
      Left(
        s"view '$view' named an empty row key; a row is kept under a key of one character or more"
      )
    else if size > ProjectionSupport.MaxResultBytes then
      Left(
        s"view '$view' wrote ${changes.size} rows of $size bytes for one change; the rows of one " +
          s"change may be at most ${ProjectionSupport.MaxResultBytes} bytes (4 MiB)"
      )
    else
      Right(changes.map {
        case (key, Some(row)) => ViewStore.upsert(table, key, row)
        case (key, None)      => ViewStore.delete(table, key)
      })

  /** `work` on a virtual thread, failed when it outlives the ask timeout. */
  def bounded[A](work: => A): Future[A] =
    val running = Future(work)(using AnkkaExecutors.virtual)
    val late = org.apache.pekko.pattern.after(askTimeout)(
      Future.failed(
        TimeoutException(s"view '$view' did not handle a change within $askTimeout")
      )
    )(using system.classicSystem)
    Future.firstCompletedOf(Seq(running, late))

/** One change, as a keyed handler is handed it. */
private[ankka] final case class SimpleKeyedChange[Row](
    subject: String,
    sequenceNumber: Long,
    rows: ViewRows[Row],
    override val standing: Option[WorkflowLifecycle] = None
) extends KeyedChange[Row]

/**
 * What a keyed view does with one change of one source: its subject, sequence number, payload —
 * absent for the source's deletion — and, for a workflow, its standing. Answers the rows to write.
 */
private[ankka] type KeyedHandle =
  (String, Long, Option[Payload], Option[WorkflowLifecycle]) => Future[
    Vector[(String, Option[String])]
  ]

/** A keyed view's own rows, read through the view client on a connection of its own. */
private[ankka] final class KeyedViewRows[Row](queries: ViewQueries[Row]) extends ViewRows[Row]:
  def get(key: String): Option[Row] = queries.get(key)
  def ask(query: DeclaredQuery, values: (String, String)*): Vector[Row] =
    queries.ask(query, values*)

/** A keyed view written in Scala: its handlers, and how its rows are encoded and read. */
private[ankka] final class KeyedViewHost[V <: KeyedView[Row], Row](
    descriptor: KeyedViewDescriptor[V, Row],
    client: ComponentClient,
    askTimeout: FiniteDuration
)(using system: ActorSystem[?]):

  private given ExecutionContext = system.executionContext

  val core: KeyedViewCore = KeyedViewCore(descriptor.componentId, askTimeout)

  private val view          = descriptor.create(SimpleViewContext(descriptor.componentId, client))
  private val observability = Observability(system)
  private val rows = KeyedViewRows(
    ViewQueries(
      descriptor.componentId.toString,
      descriptor.tableName,
      descriptor.rowSerializer,
      Database(),
      askTimeout,
      QueryCheck.checkedAll(descriptor.componentId, descriptor.tableName, descriptor.queries)
    )
  )

  /**
   * The changes the handler for `source` names for one change — `bytes` absent for the source
   * entity's deletion — encoded and reduced.
   */
  def handle(
      source: KeyedSource[V, Row],
      subject: String,
      sequenceNumber: Long,
      payload: Option[Payload],
      standing: Option[WorkflowLifecycle]
  ): Future[Vector[(String, Option[String])]] =
    val change = SimpleKeyedChange(subject, sequenceNumber, rows, standing)
    core
      .bounded {
        ProjectionSupport.handling(
          observability,
          descriptor.componentId.toString,
          source.componentId.toString
        ) {
          payload match
            case Some(p) => source.onChange(view, source.decode(p.data), change)
            case None    => source.onDelete(view, change)
        }
      }
      .map(effect => encode(effect))

  private def encode(effect: KeyedViewEffect[Row]): Vector[(String, Option[String])] =
    RowChanges
      .reduce(effect.changes)
      .map((key, row) => key -> row.map(r => String(descriptor.rowSerializer.toBytes(r), "UTF-8")))

/**
 * Applies one change of an event sourced source to a keyed view, exactly once: the lock, the rows
 * and the projection's offset in one transaction.
 */
private[ankka] final class KeyedViewEventHandler[A](
    core: KeyedViewCore,
    guard: EntityViewGuard,
    handle: KeyedHandle,
    reader: ChangeReader[A]
)(using system: ActorSystem[?])
    extends R2dbcHandler[EventEnvelope[A]]:

  private given ExecutionContext = system.executionContext

  private def run(session: R2dbcSession, fragment: SqlFragment): Future[Done] =
    session
      .updateOne(Database.bind(session.createStatement(fragment.render), fragment))
      .map(_ => Done)

  def process(session: R2dbcSession, envelope: EventEnvelope[A]): Future[Done] =
    val subject = PersistenceId.extractEntityId(envelope.persistenceId)
    reader.read(envelope.event) match
      // A TTL being set, or a workflow record that holds no state: not a change a view is told of.
      case SourceChange.Skip => Future.successful(Done)
      case change =>
        val payload = change match
          case SourceChange.Changed(p, _) => Some(p)
          case _                          => None
        def select(fragment: SqlFragment) =
          session.selectOne(Database.bind(session.createStatement(fragment.render), fragment))(_ =>
            ()
          )
        for
          _ <- guard.inSession(session)
          _ <- core.opening.foldLeft(Future.successful[Option[Unit]](None))((f, s) =>
            f.flatMap(_ => select(s))
          )
          changes <- handle(
            subject,
            envelope.sequenceNr,
            payload,
            ProjectionSupport.standingOf(change)
          )
          writes <- core
            .writes(changes)
            .fold(why => Future.failed(IllegalStateException(why)), Future.successful)
          _ <- writes.foldLeft(Future.successful[Done](Done))((f, w) =>
            f.flatMap(_ => run(session, w))
          )
        yield Done

/**
 * Applies one change of a key value source to a keyed view: the lock and the rows in one
 * transaction, the offset recorded afterwards, so a change may be handled again — as it may for any
 * view of a key value entity, whose store keeps no history to make it otherwise.
 */
private[ankka] final class KeyedViewStateHandler(
    core: KeyedViewCore,
    guard: EntityViewGuard,
    handle: KeyedHandle
)(using system: ActorSystem[?])
    extends Handler[DurableStateChange[StateRecord]]:

  private given ExecutionContext = system.executionContext
  private val database           = Database()

  def process(change: DurableStateChange[StateRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(change.persistenceId)
    val (payload, revision) = change match
      // A deletion is a state marked deleted (`KeyValueEntityHost.Stored`).
      case updated: UpdatedDurableState[StateRecord] if updated.value.deleted =>
        (None, updated.revision)
      case updated: UpdatedDurableState[StateRecord] =>
        (
          Some(
            Payload(
              Payload.contentTypeFor(updated.value.manifest),
              updated.value.manifest,
              updated.value.payload
            )
          ),
          updated.revision
        )
      case deleted: DeletedDurableState[StateRecord] => (None, deleted.revision)
    database.inTransaction { tx =>
      for
        _ <- guard.inTransaction(tx)
        _ <- core.opening.foldLeft(Future.successful(Vector.empty[Unit]))((f, s) =>
          f.flatMap(_ => tx.query(s)(_ => ()))
        )
        changes <- handle(subject, revision, payload, None)
        writes <- core
          .writes(changes)
          .fold(why => Future.failed(IllegalStateException(why)), Future.successful)
        _ <- writes.foldLeft(Future.successful(0L))((f, w) => f.flatMap(_ => tx.execute(w)))
      yield Done
    }
