package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.core.effect.ViewEffect
import com.thinkmorestupidless.ankka.runtime.QueryCheck
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Drives one view with no actor system and no database: a change of its source goes to its handler
 * with the row the view holds for that subject, and the row it writes is kept in a map the test
 * reads.
 *
 * The handler is set up as the runtime sets it up (the row, then a change context with the subject
 * and sequence number), the change is decoded from the bytes the source would carry, and every row
 * round-trips through the view's serializer. A declared query is SQL, which nothing here runs; the
 * kit checks the view's statements as a service's start does, so a statement that would stop the
 * service fails the test that builds the kit.
 *
 * {{{
 * val kit = ViewTestKit(CartRows)
 * kit.change("cart-1", ItemAdded("socks"))
 * assertEquals(kit.row("cart-1").map(_.items), Some(Vector("socks")))
 * }}}
 */
final class ViewTestKit[V <: View[Src, Row], Src, Row] private (
    descriptor: ViewDescriptor[V, Src, Row],
    client: ComponentClient
):

  private val view = descriptor.create(SimpleViewContext(descriptor.componentId, client))

  private var stored    = Map.empty[String, Array[Byte]]
  private var sequences = Map.empty[String, Long]

  private def serializer: Serializer[Row] = descriptor.rowSerializer

  /** What the view holds now, by subject. */
  def rows: Map[String, Row] = stored.map((key, bytes) => key -> serializer.fromBytes(bytes))

  /** The row for `subject`, or `None`. */
  def row(subject: String): Option[Row] = stored.get(subject).map(serializer.fromBytes)

  /** The view's declared queries, as a service's start checked them. */
  def queries: Vector[DeclaredQuery] = descriptor.queries

  /**
   * Hands `value`, a change of `subject`, to the view's handler. The sequence number counts each
   * subject's changes from one unless the test gives it. `standing` is a workflow's, for a view of
   * one; `installation` and `localOrigin` a replicated entity's.
   */
  def change(
      subject: String,
      value: Src,
      sequenceNumber: Long = -1,
      standing: Option[WorkflowLifecycle] = None,
      installation: Option[String] = None,
      localOrigin: Boolean = true
  ): ViewEffect[Row] =
    val decoder = descriptor.source.decoder
    val decoded = decoder.fromBytes(decoder.toBytes(value))
    run(subject, sequenceNumber, standing, installation, localOrigin)(view.onChange(decoded))

  /** Tells the view's handler that `subject` was deleted. */
  def deleted(subject: String, sequenceNumber: Long = -1): ViewEffect[Row] =
    run(subject, sequenceNumber, None, None, localOrigin = true)(view.onDelete)

  private def run(
      subject: String,
      sequenceNumber: Long,
      standing: Option[WorkflowLifecycle],
      installation: Option[String],
      localOrigin: Boolean
  )(handle: => ViewEffect[Row]): ViewEffect[Row] =
    val sequence =
      if sequenceNumber >= 0 then sequenceNumber else sequences.getOrElse(subject, 0L) + 1
    sequences = sequences.updated(subject, sequence)
    view._setRow(row(subject))
    view._setContext(
      Some(SimpleChangeContext(subject, sequence, localOrigin, standing, installation))
    )
    val effect =
      try handle
      finally view._setContext(None)
    effect match
      case ViewEffect.UpdateRow(value) =>
        stored = stored.updated(subject, serializer.toBytes(value))
      case ViewEffect.DeleteRow => stored = stored - subject
      case ViewEffect.Ignore    => ()
    effect

object ViewTestKit:

  def apply[V <: View[Src, Row], Src, Row](
      companion: View.Companion[V, Src, Row],
      client: ComponentClient = TestTransport.unroutedClient
  ): ViewTestKit[V, Src, Row] =
    val descriptor = companion.descriptor
    QueryCheck
      .problemsOf(descriptor.componentId, descriptor.tableName, descriptor.queries)
      .headOption
      .foreach(problem => throw IllegalArgumentException(problem))
    new ViewTestKit(descriptor, client)
