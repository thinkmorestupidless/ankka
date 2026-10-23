package com.thinkmorestupidless.ankka.javasdk.samples

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, Done, EntityId}
import com.thinkmorestupidless.ankka.javasdk.bridge.JavaServiceBuilder
import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.{Config, ConfigFactory}
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.{LineItem, ShoppingCart}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * A service written in one language recovers what a service written in the other recorded (SC-003,
 * FR-018): the Scala cart writes a journal, the Java cart is started on that database and recovers
 * every cart through its own code, and the reverse. The rows `Scala | Java` and `Java | Scala` of
 * `features/polyglot/entities.feature`, which no Gherkin suite runs.
 *
 * Both services are called through the Scala cart's handles: a call is routed by component id and
 * wire name, and the reply's bytes are the same in either language (`CrossLanguageBytesSuite`), so
 * one caller reads both and the comparison is of one type.
 */
class JournalPortabilitySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val scalaCart: Seq[ComponentDescriptor] = Seq(ShoppingCartEntity.descriptor)

  private val javaCart: Seq[ComponentDescriptor] =
    shoppingcart.javasdk.Main.service() match
      case b: JavaServiceBuilder => JavaServiceBuilder.read(b.classes, b.serializers).descriptors
      case other                 => fail(s"$other is not a Java service")

  private val items = List(
    LineItem("a", "Apple", 2),
    LineItem("b", "Bread", 1),
    LineItem("c", "Cheese", 4)
  )

  /** A database of its own holding the schema, with nothing running on it. */
  private def database(): AnkkaTestKit =
    val kit = AnkkaTestKit.start(Nil)
    kit.stopService()
    kit

  private def configFor(kit: AnkkaTestKit): Config =
    val url = java.net.URI.create(kit.jdbcUrl.stripPrefix("jdbc:"))
    ConfigFactory
      .parseMap(
        Map(
          "pekko.persistence.r2dbc.connection-factory.host"     -> url.getHost,
          "pekko.persistence.r2dbc.connection-factory.port"     -> url.getPort,
          "pekko.persistence.r2dbc.connection-factory.database" -> url.getPath.stripPrefix("/"),
          "pekko.persistence.r2dbc.connection-factory.user"     -> "ankka",
          "pekko.persistence.r2dbc.connection-factory.password" -> "ankka"
        ).asJava
      )
      .withFallback(ConfigFactory.load())
      .resolve()

  private def running[A](descriptors: Seq[ComponentDescriptor], config: Config)(
      body: AnkkaService => A
  ): A =
    val service = Ankka.service.registerAll(descriptors).start("portability", config)
    try
      service.awaitReady(60.seconds)
      body(service)
    finally
      service.terminate()
      Await.ready(service.whenTerminated, 30.seconds): Unit

  private def cart(service: AnkkaService, id: String) =
    service.componentClient.forEventSourcedEntity(EntityId(id))

  private def portable(written: Seq[ComponentDescriptor], recovered: Seq[ComponentDescriptor]) =
    val kit = database()
    try
      val config = configFor(kit)
      val ids    = Vector("c1", "c2", "c3")
      val before = running(written, config) { service =>
        ids.map { id =>
          items.foreach(i =>
            assertEquals(cart(service, id).call(ShoppingCartEntity.addItem).invoke(i), Done)
          )
          // One cart checked out, so a case with no record is in the journal too.
          if id == "c3" then cart(service, id).call(ShoppingCartEntity.checkout).invoke(): Unit
          cart(service, id).call(ShoppingCartEntity.getCart).invoke()
        }
      }
      val after = running(recovered, config) { service =>
        ids.map(id => cart(service, id).call(ShoppingCartEntity.getCart).invoke())
      }
      assertEquals(after, before)
      assertEquals(after.head, ShoppingCart("c1", items, checkedOut = false))
      assert(after.last.checkedOut)
    finally kit.stop()

  test("the Java cart recovers what the Scala cart recorded") {
    portable(written = scalaCart, recovered = javaCart)
  }

  test("the Scala cart recovers what the Java cart recorded") {
    portable(written = javaCart, recovered = scalaCart)
  }
