package com.thinkmorestupidless.ankka.javasdk

import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart
import com.thinkmorestupidless.ankka.javasdk.fixtures.broken.{
  DuplicateWireName,
  QueryThatPersists,
  Unannotated
}
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.startfail.OrphanEndpoint

/**
 * The "fails at start" scenarios of `features/java-kotlin/embedded.feature`, Java rows: the service
 * does not start, the message names what is wrong, and no request was needed to find it.
 */
class StartFailuresSuite extends munit.FunSuite:

  private def refused(service: ServiceBuilder): String =
    val before = OrphanEndpoint.requests.get
    val e      = intercept[ServiceProblems](service.withHttp("127.0.0.1", 0).start())
    assertEquals(OrphanEndpoint.requests.get, before, "a request reached the service")
    e.getMessage

  test("two handlers declared with one name fail the service at start, naming it") {
    val message = refused(Ankka.service("shop").register(classOf[DuplicateWireName]))
    assert(message.contains("add-item"), message)
  }

  test("a function shaped like a handler that carries no name fails the start, naming it") {
    val message = refused(Ankka.service("shop").register(classOf[Unannotated]))
    assert(message.contains("Unannotated.get"), message)
  }

  test("a query whose declared outcome could record an event fails the start, naming it") {
    val message = refused(Ankka.service("shop").register(classOf[QueryThatPersists]))
    assert(message.contains("QueryThatPersists.get"), message)
  }

  test("an endpoint calling a component the service does not register fails the start, naming it") {
    val message = refused(Ankka.service("shop").withEndpoint(classOf[OrphanEndpoint]))
    assert(message.contains("'shopping-cart'"), message)
    assert(message.contains("OrphanEndpoint"), message)
  }

  test("the same endpoint is accepted once the component is registered") {
    val read = bridge.JavaServiceBuilder.read(
      Vector(classOf[ShoppingCart] -> None),
      Map.empty,
      Vector(classOf[OrphanEndpoint])
    )
    assertEquals(read.endpoints.map(_.cls), Vector(classOf[OrphanEndpoint]))
  }
