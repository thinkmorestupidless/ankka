package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.core.effect.{KeyedViewEffect, RowChanges}
import com.thinkmorestupidless.ankka.runtime.QueryCheck
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Drives one keyed view with no actor system and no database: a change of one of its sources goes
 * to that source's handler, and the rows it names are written to a map the test reads.
 *
 * Effects are applied through `RowChanges.reduce`, the function the runtime applies them through,
 * and every row round-trips through the view's own serializer, so a row the runtime could not write
 * cannot be written here either.
 *
 * A declared query is SQL, and there is no database to run it, so the test says what each query
 * answers. A handler that asks a query the test has not answered fails the test, naming the query:
 * a kit that answered no rows would pass a handler that updated nothing.
 *
 * {{{
 * val kit = KeyedViewTestKit(Shipments)
 * kit.answering(Shipments.ofCustomer)(values => kit.rows.values.filter(_.customer == values("customer")).toVector)
 * kit.change(Shipments.shipments, "s1", ShipmentCreated("c1"))
 * kit.change(Shipments.customers, "c1", CustomerRenamed("Ada"))
 * }}}
 *
 * Building the kit checks the view's declared statements as the runtime does when a service starts,
 * so a statement that would stop the service fails the test that builds the kit.
 */
final class KeyedViewTestKit[V <: KeyedView[Row], Row] private (
    descriptor: KeyedViewDescriptor[V, Row],
    client: ComponentClient
):

  private val view = descriptor.create(SimpleViewContext(descriptor.componentId, client))

  private var stored   = Vector.empty[(String, Array[Byte])]
  private var answers  = Map.empty[String, Map[String, String] => Vector[Row]]
  private var sequence = 0L

  private def serializer: Serializer[Row] = descriptor.rowSerializer

  /** What the view holds now, by row key. */
  def rows: Map[String, Row] = stored.map((key, bytes) => key -> serializer.fromBytes(bytes)).toMap

  /** The row under `key`, or `None`. */
  def row(key: String): Option[Row] = rows.get(key)

  /** What `query` answers with, given the values it is asked with. */
  def answering(query: DeclaredQuery)(answer: Map[String, String] => Vector[Row]): this.type =
    answers = answers.updated(query.name, answer)
    this

  /**
   * Hands `value`, a change of `subject` of `source`, to the source's handler. `standing` is a
   * workflow's, for a source that reads one.
   */
  def change[Src](
      source: KeyedSource[V, Row],
      subject: String,
      value: Src,
      standing: Option[WorkflowLifecycle] = None
  ): KeyedViewEffect[Row] =
    val bytes = source.source.decoder.asInstanceOf[Serializer[Src]].toBytes(value)
    run(source, subject, Some(bytes), standing)

  /** Tells the source's handler that `subject` was deleted. */
  def deleted(source: KeyedSource[V, Row], subject: String): KeyedViewEffect[Row] =
    run(source, subject, None, None)

  private def run(
      source: KeyedSource[V, Row],
      subject: String,
      bytes: Option[Array[Byte]],
      standing: Option[WorkflowLifecycle]
  ): KeyedViewEffect[Row] =
    if !descriptor.sources.exists(_ eq source) then
      throw IllegalArgumentException(
        s"the source ${source.source.describe} is not one of view '${descriptor.componentId}'s"
      )
    sequence += 1
    val change = KeyedViewTestKit.Change(subject, sequence, KeyedViewTestKit.Rows(this), standing)
    val effect = bytes match
      case Some(payload) => source.onChange(view, source.decode(payload), change)
      case None          => source.onDelete(view, change)
    val written =
      RowChanges.reduce(effect.changes).map((key, row) => key -> row.map(serializer.toBytes))
    written.map(_._1).foreach { key =>
      if key.isEmpty then
        throw IllegalStateException(
          s"view '${descriptor.componentId}' named an empty row key; a row is kept under a key " +
            "of one character or more"
        )
    }
    written.foreach { (key, row) =>
      stored = stored.filterNot(_._1 == key) ++ row.map(key -> _)
    }
    effect

  private def answer(query: DeclaredQuery, values: Map[String, String]): Vector[Row] =
    if query.view != descriptor.componentId then
      throw IllegalArgumentException(
        s"the query '${query.name}' is view '${query.view}'s, and this is view " +
          s"'${descriptor.componentId}'"
      )
    answers
      .getOrElse(
        query.name,
        throw IllegalStateException(
          s"view '${descriptor.componentId}' asked the query '${query.name}', and the test has " +
            s"not said what it answers; say so with answering(${query.name})"
        )
      )(values)

object KeyedViewTestKit:

  def apply[V <: KeyedView[Row], Row](
      companion: KeyedView.Companion[V, Row],
      client: ComponentClient = TestTransport.unroutedClient
  ): KeyedViewTestKit[V, Row] =
    val descriptor = companion.descriptor
    QueryCheck
      .problemsOf(descriptor.componentId, descriptor.tableName, descriptor.queries)
      .headOption
      .foreach(problem => throw IllegalArgumentException(problem))
    new KeyedViewTestKit(descriptor, client)

  private final case class Change[Row](
      subject: String,
      sequenceNumber: Long,
      rows: ViewRows[Row],
      override val standing: Option[WorkflowLifecycle]
  ) extends KeyedChange[Row]

  private final class Rows[Row](kit: KeyedViewTestKit[?, Row]) extends ViewRows[Row]:
    def get(key: String): Option[Row] = kit.row(key)
    def ask(query: DeclaredQuery, values: (String, String)*): Vector[Row] =
      kit.answer(query, values.toMap)
