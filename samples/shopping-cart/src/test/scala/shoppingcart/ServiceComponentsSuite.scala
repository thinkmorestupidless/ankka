package shoppingcart

import com.thinkmorestupidless.ankka.core.{ComponentId, ComponentKind}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}
import shoppingcart.api.ShoppingCartEndpoint
import shoppingcart.application.{CartRows, ShoppingCartEntity}

/** A service's registration is its whole inventory: what it names is what it hosts. */
class ServiceComponentsSuite extends munit.FunSuite:

  // docs:start registration
  val service = Ankka.service
    .register(ShoppingCartEntity.descriptor)
    .register(CartRows.descriptor)
    .withExtension(ProjectionRuntime())
    .withExtension(HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient)))
  // docs:end registration

  test("the registered components are the service's components, and nothing else") {
    val registry = service.validate.fold(problems => fail(problems.mkString("; ")), identity)
    assertEquals(registry.size, 2)
    assert(registry.get(ComponentKind.EventSourcedEntity, ComponentId("shopping-cart")).isDefined)
    assertEquals(
      registry.ofKind(ComponentKind.View).map(_.componentId),
      Vector(ComponentId("cart-rows"))
    )
  }
