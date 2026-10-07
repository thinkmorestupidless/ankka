package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A tree: each node is under another node, or under none, and has a kind. What the declared and
 * recursive query suites read.
 */
final case class Node(under: Option[String], kind: String)

enum NodeEvent:
  case Placed(under: Option[String], kind: String)

final class NodeEntity extends EventSourcedEntity[Node, NodeEvent]:

  def emptyState: Node = Node(None, "")

  def applyEvent(event: NodeEvent): Node = event match
    case NodeEvent.Placed(under, kind) => Node(under, kind)

  def place(node: Node): Effect[Done] =
    effects.persist(NodeEvent.Placed(node.under, node.kind)).thenReply(_ => Done)

object NodeEntity
    extends EventSourcedEntity.Companion[NodeEntity, Node, NodeEvent](
      componentId = ComponentId("node"),
      stateSerializer = Codecs.serializer[Node]("node"),
      eventSerializer = Codecs.serializer[NodeEvent]("node-event")
    ):
  def create(context: EventSourcedEntityContext) = new NodeEntity

  val place = command("place")(_.place)

/** One row per node: its own key, the key of the row it is under, and its kind. */
final case class NodeRow(key: String, under: Option[String], kind: String)

final class NodesView extends View[NodeEvent, NodeRow]:
  def onChange(event: NodeEvent): Effect = event match
    case NodeEvent.Placed(under, kind) =>
      effects.updateRow(NodeRow(updateContext.subject, under, kind))

object Nodes
    extends View.Companion[NodesView, NodeEvent, NodeRow](
      componentId = ComponentId("nodes"),
      source = ChangeSource.eventsOf(NodeEntity),
      rowSerializer = Codecs.serializer[NodeRow]("node-row")
    ):

  // docs:start declared-recursive-query
  /** Every row under the row `row`, to any depth. */
  val under = query("under")(s"""
    WITH RECURSIVE below AS (
      SELECT row_key, payload FROM $table WHERE payload::jsonb->>'under' = :row
      UNION
      SELECT n.row_key, n.payload FROM $table n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
    )
    SELECT payload FROM below""")
  // docs:end declared-recursive-query

  /** The rows of one kind. */
  val ofKind = query("of-kind")(s"SELECT payload FROM $table WHERE payload::jsonb->>'kind' = :kind")

  /** Every row, by key: what a limit is tried on. */
  val byKey = query("by-key-order")(s"SELECT payload FROM $table ORDER BY row_key")

  /** Names another table only in a comment and in a value. */
  val noted = query("noted")(
    s"""SELECT payload FROM $table -- not ankka_view_accounts
       |WHERE payload::jsonb->>'kind' <> 'ankka_view_accounts' /* nor here */""".stripMargin
  )

  /** Never ends: the largest of an endless series. */
  val around = query("around")(s"""
    WITH RECURSIVE counting(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM counting)
    SELECT payload FROM $table WHERE row_key = (SELECT max(n)::text FROM counting /* around */)""")

  /** Selects no payload: a statement that should not have been declared. */
  val keysOnly = query("keys-only")(s"SELECT row_key FROM $table")

  /** Calls a function the database refuses in a read-only transaction. */
  val writes = query("writes")(
    s"SELECT payload FROM $table WHERE nextval('ankka_test_sequence') > 0"
  )

  /** What a statement counter is shown to see. */
  val control = query("control")(
    s"SELECT payload FROM $table WHERE row_key <> 'sc002-control' AND payload::jsonb->>'kind' = :kind"
  )

  def create(ctx: ViewComponentContext) = new NodesView

/**
 * A view of the same entity declaring one statement, for a service that must not start: a fresh
 * companion each time, since a companion's queries are fixed once it is registered.
 */
def nodesDeclaring(name: String, statement: String): ViewDescriptor[NodesView, NodeEvent, NodeRow] =
  new View.Companion[NodesView, NodeEvent, NodeRow](
    ComponentId("nodes"),
    ChangeSource.eventsOf(NodeEntity),
    Codecs.serializer[NodeRow]("node-row")
  ):
    @annotation.nowarn("msg=unused")
    val declared                          = query(name)(statement)
    def create(ctx: ViewComponentContext) = new NodesView
  .descriptor

/** A second view, whose table a statement of `nodes` may not read. */
final case class AccountRow(name: String)

final class AccountsView extends View[NodeEvent, AccountRow]:
  def onChange(event: NodeEvent): Effect = effects.ignore()

object Accounts
    extends View.Companion[AccountsView, NodeEvent, AccountRow](
      componentId = ComponentId("accounts"),
      source = ChangeSource.eventsOf(NodeEntity),
      rowSerializer = Codecs.serializer[AccountRow]("account-row")
    ):
  def create(ctx: ViewComponentContext) = new AccountsView

/** The statements a service must refuse to start with, each carrying a marker to look for. */
object Refused:
  private val table = ViewDescriptor.tableFor(ComponentId("nodes"))

  val statements: Vector[(String, String)] = Vector(
    "writes a row" ->
      s"INSERT INTO $table (row_key, payload) VALUES ('sc002-insert', '{}')",
    "deletes a row" -> s"DELETE FROM $table WHERE row_key = 'sc002-delete'",
    "holds a second statement after the query" ->
      s"SELECT payload FROM $table; SELECT 'sc002-second' AS payload",
    "reads a table that is no view's" ->
      "SELECT 'sc002-catalog' AS payload FROM pg_stat_activity",
    "reads the table of accounts" ->
      s"SELECT payload FROM ${ViewDescriptor.tableFor(ComponentId("accounts"))} WHERE payload <> 'sc002-other'"
  )
