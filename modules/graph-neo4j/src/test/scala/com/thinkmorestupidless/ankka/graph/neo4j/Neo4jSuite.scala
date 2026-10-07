package com.thinkmorestupidless.ankka.graph.neo4j

import org.neo4j.driver.{AuthTokens, Driver, GraphDatabase}
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** One Neo4j in a container for a suite, named by `ankka.neo4j.image`, never by a literal tag. */
trait Neo4jSuite extends munit.FunSuite:

  val AdminPassword = "ankka-test-password"

  val neo4j: Neo4jContainer[?] =
    new Neo4jContainer(
      DockerImageName
        .parse(
          sys.props.getOrElse(
            "ankka.neo4j.image",
            throw IllegalStateException("ankka.neo4j.image is not set; the build sets it")
          )
        )
        .asCompatibleSubstituteFor("neo4j")
    ).withAdminPassword(AdminPassword)

  private var open: Option[Driver] = None

  override val munitTimeout: FiniteDuration = 5.minutes

  def boltUri: String = neo4j.getBoltUrl

  def settings: Neo4jSettings =
    Neo4jSettings(boltUri, "neo4j", AdminPassword, transactionTimeout = 10.seconds)

  def driver: Driver =
    open.getOrElse {
      val d = GraphDatabase.driver(boltUri, AuthTokens.basic("neo4j", AdminPassword))
      open = Some(d)
      d
    }

  override def beforeAll(): Unit =
    super.beforeAll()
    neo4j.start()

  override def afterAll(): Unit =
    open.foreach(_.close())
    neo4j.stop()
    super.afterAll()

  def query(cypher: String, params: Map[String, AnyRef] = Map.empty): Vector[Map[String, AnyRef]] =
    val session = driver.session()
    try session.run(cypher, params.asJava).list().asScala.toVector.map(_.asMap().asScala.toMap)
    finally session.close()

  def clear(): Unit =
    query("MATCH (n) DETACH DELETE n"): Unit
    query("DROP CONSTRAINT element_id IF EXISTS"): Unit

  def nodeRow(id: String): Map[String, AnyRef] =
    query(
      "MATCH (n:Element {id: $id}) RETURN labels(n) AS labels, properties(n) AS props",
      Map("id" -> id)
    ).head

  def labels(id: String): Set[String] =
    nodeRow(id)("labels").asInstanceOf[java.util.List[String]].asScala.toSet

  def props(id: String): Map[String, AnyRef] =
    nodeRow(id)("props").asInstanceOf[java.util.Map[String, AnyRef]].asScala.toMap
