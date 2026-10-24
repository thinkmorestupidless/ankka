package com.thinkmorestupidless.ankka.javasdk.testkit.steps

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.javasdk.{Ankka, Done, ServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.bridge.{Clients, JavaServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.cart.{CartState, LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.codec.{EncodingException, RecordCodec}
import com.thinkmorestupidless.ankka.javasdk.fields.Priced
import com.thinkmorestupidless.ankka.javasdk.fixtures.broken.{
  DuplicateWireName,
  QueryThatPersists,
  Unannotated
}
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.rename.RenamedCart
import com.thinkmorestupidless.ankka.javasdk.startfail.OrphanEndpoint
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Optional

/**
 * The Java rows of `features/java-kotlin/embedded.feature` that run embedded in this JVM. A Kotlin
 * row is skipped here, naming the suite that runs it.
 */
trait EmbeddedSteps extends GherkinSuite:

  /** The suite that runs a scenario's Kotlin row. */
  protected def kotlinSuite: String

  private def java(language: String): Unit =
    assume(language == "Java", s"the $language row is run by $kotlinSuite")

  // ── a service that fails at start ──────────────────────────────────────────

  private var builder: ServiceBuilder        = null
  private var refusal: Option[String]        = None
  private var requestsBefore                 = 0
  private var failedFunction: Option[String] = None

  private def service(name: String, configure: ServiceBuilder => ServiceBuilder): Unit =
    builder = configure(Ankka.service(name))
    refusal = None

  Given(
    "a service {string} written in {string} with two handlers of the entity {string} declared with the name {string}"
  ) { (name: String, language: String, _: String, _: String) =>
    java(language)
    service(name, _.register(classOf[DuplicateWireName]))
  }

  Given(
    "a service {string} written in {string} whose entity {string} has a function shaped like a handler that carries no name"
  ) { (name: String, language: String, _: String) =>
    java(language)
    service(name, _.register(classOf[Unannotated]))
    failedFunction = Some("Unannotated.get")
  }

  Given(
    "a service {string} written in {string} whose entity {string} has a query whose declared outcome could record an event"
  ) { (name: String, language: String, _: String) =>
    java(language)
    service(name, _.register(classOf[QueryThatPersists]))
    failedFunction = Some("QueryThatPersists.get")
  }

  Given(
    "a service {string} written in {string} whose endpoint calls the entity {string}, which {string} does not register"
  ) { (name: String, language: String, _: String, _: String) =>
    java(language)
    service(name, _.withEndpoint(classOf[OrphanEndpoint]))
  }

  When("the developer starts {string}") { (_: String) =>
    requestsBefore = OrphanEndpoint.requests.get
    refusal =
      try
        builder.withHttp("127.0.0.1", 0).start().close()
        None
      catch case e: ServiceProblems => Some(e.getMessage)
  }

  Then("{string} does not start, naming {string}") { (_: String, named: String) =>
    val message = refusal.getOrElse(fail("the service started"))
    assert(message.contains(named), message)
  }

  Then("{string} does not start, naming the function") { (_: String) =>
    val message = refusal.getOrElse(fail("the service started"))
    failedFunction.foreach(f => assert(message.contains(f), message))
  }

  Then("no caller's request is needed to find out") { () =>
    assert(refusal.isDefined, "the service started")
    assertEquals(OrphanEndpoint.requests.get, requestsBefore)
  }

  // ── a handler's name declared apart from its function ───────────────────────

  private var kit: AnkkaTestKit    = null
  private var registered: Class[?] = classOf[ShoppingCart]
  private def read                 = JavaServiceBuilder.read(Vector(registered -> None), Map.empty)
  private def descriptors: Vector[ComponentDescriptor] = read.descriptors
  private val pen                                      = LineItem("p1", "Pen", 1)

  override def afterEach(context: AfterEach): Unit =
    if kit != null then kit.stop()
    kit = null
    super.afterEach(context)

  private def cart(id: String) =
    Clients.of(kit.componentClient, read.refs).forEventSourcedEntity(id)

  Given(
    "a service {string} written in {string} whose entity {string} has the handler {string} declared on the function {string}"
  ) { (_: String, language: String, _: String, handler: String, function: String) =>
    java(language)
    registered = classOf[ShoppingCart]
    assertEquals(
      read.plans.head.handlers.find(_.wireName == handler).map(_.method.getName),
      Some(function)
    )
    kit = AnkkaTestKit.start(Nil, configure = _.registerAll(descriptors))
  }

  When("the developer renames the function to {string} and runs {string} again") {
    (function: String, _: String) =>
      cart("c1").call("shopping-cart", "add-item", classOf[LineItem], classOf[Done]).invoke(pen)
      registered = classOf[RenamedCart]
      assertEquals(
        read.plans.head.handlers.find(_.wireName == "add-item").map(_.method.getName),
        Some(function)
      )
      kit.restartService()
  }

  Then("a caller's command {string} to the cart {string} is still answered") {
    (handler: String, id: String) =>
      assertEquals(
        cart(id).call("shopping-cart", handler, classOf[LineItem], classOf[Done]).invoke(pen),
        Done.DONE
      )
  }

  Then("the cart {string} is recovered from what it recorded before the rename") { (id: String) =>
    val state =
      cart(id).call("shopping-cart", "get-cart", classOf[Void], classOf[CartState]).invoke()
    assertEquals(state.items.get(0).quantity, 2)
  }

  // ── a field that may hold nothing ──────────────────────────────────────────

  private var written: Either[EncodingException, String] = null

  Given("a state declared in {string} with a field {string} that may hold nothing") {
    (language: String, field: String) =>
      java(language)
      assertEquals(field, "discount")
  }

  private def write(value: Priced): Unit =
    written =
      try Right(String(RecordCodec.forType(classOf[Priced]).toBytes(value), UTF_8))
      catch case e: EncodingException => Left(e)

  When("the state is written with the field holding nothing") { () =>
    write(Priced("tea", Optional.empty()))
  }

  When("the state is written with the field holding null") { () =>
    write(Priced("tea", null))
  }

  // The encoding's absent value is the Scala SDK's for `None`: the field written as null
  // (`protocol/fixtures/sum-type-optional-absent.json`).
  Then("the field is absent from what is written") { () =>
    assertEquals(written, Right("""{"name":"tea","discount":null}"""))
  }

  Then("the writing is refused, naming the field {string}") { (field: String) =>
    val refused = written.left.getOrElse(fail(s"written: $written"))
    assert(refused.path.contains(field), refused.getMessage)
  }
