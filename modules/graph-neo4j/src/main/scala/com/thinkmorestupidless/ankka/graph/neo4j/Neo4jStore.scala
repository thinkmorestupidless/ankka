package com.thinkmorestupidless.ankka.graph.neo4j

import com.thinkmorestupidless.ankka.core.graph.{GraphDelta, PropertyValue}
import org.neo4j.driver.exceptions.ClientException
import org.neo4j.driver.{
  AuthTokens,
  Config,
  Driver,
  GraphDatabase,
  SessionConfig,
  TransactionConfig
}
import org.slf4j.LoggerFactory

import java.time.Duration as JDuration
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * A Neo4j store the sink writes deltas to, one delta per write transaction. Every statement is
 * version-guarded and state-shaped, so a delta applied twice, after a redelivery, changes nothing
 * the second time:
 *
 *   - a node is `:Element {id}` plus the delta's labels and properties, with `_version` the version
 *     of the last applied delta; a merge replaces labels and properties whole;
 *   - an edge merges both endpoints as placeholders (`_version = -1`) when absent, then the
 *     relationship under the delta's type and `id`;
 *   - a tombstone sets `_deleted = true` and clears labels and properties, at its version;
 *   - a delta at or below the stored `_version` changes nothing: it is stale.
 *
 * The driver is opened on first use and discarded on a failure, so a connection that hung never
 * serves again. The password reaches no message: every failure is redacted before it is thrown.
 */
final class Neo4jStore(settings: Neo4jSettings):

  import Neo4jStore.*

  private val log = LoggerFactory.getLogger(classOf[Neo4jStore])

  @volatile private var driver: Option[Driver] = None
  @volatile private var constraintEnsured      = false

  /** Applies one delta. Throws, redacted, when the store refused or could not be reached. */
  def apply(delta: GraphDelta): Unit =
    try
      val d       = open()
      val session = d.session(SessionConfig.forDatabase(settings.database))
      try
        session.executeWrite(
          tx =>
            val (statement, key, row) = statementFor(delta)
            tx.run(statement, Map[String, AnyRef](key -> java.util.List.of(row)).asJava)
              .consume(): Unit
          ,
          TransactionConfig
            .builder()
            .withTimeout(JDuration.ofMillis(settings.transactionTimeout.toMillis))
            .build()
        ): Unit
      finally session.close()
    catch
      case e: Throwable =>
        discard()
        throw Neo4jRefused(settings.redact(Option(e.getMessage).getOrElse(e.toString)))

  /**
   * Opens the driver, checks the server is new enough, and ensures the uniqueness constraint once.
   */
  def open(): Driver = synchronized {
    driver.getOrElse {
      val created = GraphDatabase.driver(
        settings.uri,
        AuthTokens.basic(settings.username, settings.password),
        Config
          .builder()
          .withConnectionTimeout(10, TimeUnit.SECONDS)
          .withConnectionAcquisitionTimeout(
            settings.transactionTimeout.toMillis,
            TimeUnit.MILLISECONDS
          )
          .withMaxTransactionRetryTime(settings.transactionTimeout.toMillis, TimeUnit.MILLISECONDS)
          .build()
      )
      try
        created.verifyConnectivity()
        val agent = serverAgent(created)
        if !supported(agent) then
          throw IllegalStateException(
            s"$agent at ${settings.uri} is older than Neo4j 5.26, which the sink needs (dynamic labels)"
          )
        if !constraintEnsured then
          ensureConstraint(created)
          constraintEnsured = true
      catch
        case e: Throwable =>
          Try(created.closeAsync()): Unit
          throw e
      driver = Some(created)
      log.info("graph sink open against {} ({})", settings.uri, settings.database)
      created
    }
  }

  def close(): Unit = discard()

  private def discard(): Unit = synchronized {
    driver.foreach(d => Try(d.closeAsync()))
    driver = None
  }

  private def serverAgent(d: Driver): String =
    val session = d.session(SessionConfig.forDatabase(settings.database))
    try session.run("RETURN 1").consume().server().agent()
    finally session.close()

  private def ensureConstraint(d: Driver): Unit =
    val session = d.session(SessionConfig.forDatabase(settings.database))
    try session.run(Constraint).consume(): Unit
    catch
      case e: ClientException if e.code.startsWith("Neo.ClientError.Security") =>
        log.warn(
          "could not create constraint element_id: {}; merges will scan until it exists",
          settings.redact(e.getMessage)
        )
    finally session.close()

object Neo4jStore:

  /** The store refused a delta, or could not be reached; the message never holds the password. */
  final class Neo4jRefused(message: String) extends RuntimeException(message)

  val Constraint: String =
    "CREATE CONSTRAINT element_id IF NOT EXISTS FOR (n:Element) REQUIRE n.id IS UNIQUE"

  def supported(agent: String): Boolean =
    val Version = """Neo4j/(\d+)\.(\d+).*""".r
    agent match
      case Version(major, minor) => major.toInt > 5 || (major.toInt == 5 && minor.toInt >= 26)
      case _                     => false

  /** The statement, its parameter name and the one row for a delta. */
  def statementFor(delta: GraphDelta): (String, String, java.util.Map[String, AnyRef]) =
    val version = java.lang.Long.valueOf(delta.version)
    (delta.kind, delta.element) match
      case (GraphDelta.Kind.Node, _) =>
        (
          Statements.Nodes,
          "nodes",
          Map[String, AnyRef](
            "id"         -> delta.id,
            "version"    -> version,
            "labels"     -> delta.labels.asJava,
            "properties" -> properties(delta.properties)
          ).asJava
        )
      case (GraphDelta.Kind.Edge, _) =>
        (
          Statements.Edges,
          "edges",
          Map[String, AnyRef](
            "id"         -> delta.id,
            "version"    -> version,
            "type"       -> delta.edgeType.getOrElse(""),
            "from"       -> delta.from.getOrElse(""),
            "to"         -> delta.to.getOrElse(""),
            "properties" -> properties(delta.properties)
          ).asJava
        )
      case (GraphDelta.Kind.Tombstone, GraphDelta.Element.Node) =>
        (
          Statements.NodeTombstones,
          "nodeTombstones",
          Map[String, AnyRef]("id" -> delta.id, "version" -> version).asJava
        )
      case (GraphDelta.Kind.Tombstone, GraphDelta.Element.Edge) =>
        (
          Statements.EdgeTombstones,
          "edgeTombstones",
          Map[String, AnyRef](
            "id"      -> delta.id,
            "version" -> version,
            "type"    -> delta.edgeType.getOrElse(""),
            "from"    -> delta.from.getOrElse(""),
            "to"      -> delta.to.getOrElse("")
          ).asJava
        )

  private def properties(values: Map[String, PropertyValue]): java.util.Map[String, AnyRef] =
    values.map((name, value) => name -> toJava(value)).asJava

  private def toJava(value: PropertyValue): AnyRef = value match
    case text: String   => text
    case flag: Boolean  => java.lang.Boolean.valueOf(flag)
    case whole: Int     => java.lang.Long.valueOf(whole.toLong)
    case whole: Long    => java.lang.Long.valueOf(whole)
    case number: Double => java.lang.Double.valueOf(number)
    case items: Seq[?]  => items.map(item => toJava(item.asInstanceOf[PropertyValue])).asJava

  object Statements:
    val Nodes: String =
      """UNWIND $nodes AS d
        |MERGE (n:Element {id: d.id})
        |  ON CREATE SET n._version = -1
        |WITH n, d WHERE n._version < d.version
        |REMOVE n:$([l IN labels(n) WHERE l <> 'Element'])
        |SET n = d.properties, n.id = d.id, n._version = d.version
        |SET n:$(d.labels)
        |RETURN count(n) AS written""".stripMargin

    val Edges: String =
      """UNWIND $edges AS d
        |MERGE (a:Element {id: d.from}) ON CREATE SET a._version = -1
        |MERGE (b:Element {id: d.to})   ON CREATE SET b._version = -1
        |MERGE (a)-[r:$(d.type) {id: d.id}]->(b)
        |  ON CREATE SET r._version = -1
        |WITH r, d WHERE r._version < d.version
        |SET r = d.properties, r.id = d.id, r._version = d.version
        |RETURN count(r) AS written""".stripMargin

    val NodeTombstones: String =
      """UNWIND $nodeTombstones AS d
        |MERGE (n:Element {id: d.id})
        |  ON CREATE SET n._version = -1
        |WITH n, d WHERE n._version < d.version
        |REMOVE n:$([l IN labels(n) WHERE l <> 'Element'])
        |SET n = {id: d.id, _version: d.version, _deleted: true}
        |RETURN count(n) AS written""".stripMargin

    val EdgeTombstones: String =
      """UNWIND $edgeTombstones AS d
        |MERGE (a:Element {id: d.from}) ON CREATE SET a._version = -1
        |MERGE (b:Element {id: d.to})   ON CREATE SET b._version = -1
        |MERGE (a)-[r:$(d.type) {id: d.id}]->(b)
        |  ON CREATE SET r._version = -1
        |WITH r, d WHERE r._version < d.version
        |SET r = {id: d.id, _version: d.version, _deleted: true}
        |RETURN count(r) AS written""".stripMargin
