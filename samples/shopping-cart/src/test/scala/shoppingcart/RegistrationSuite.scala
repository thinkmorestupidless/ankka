package shoppingcart

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import shoppingcart.application.{CartRows, CheckoutLog, CheckoutNotifier, ShoppingCartEntity}

import scala.concurrent.duration.DurationInt

/**
 * How each kind of component is registered on the service builder, checked the way a service's
 * start checks it, and a key value entity called through the component client once registered.
 */
class RegistrationSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private def valid(service: com.thinkmorestupidless.ankka.runtime.ServiceBuilder): Unit =
    assert(service.validate.isRight, service.validate.left.toString)

  test("an entity is registered by its companion's descriptor") {
    // docs:start register-entity
    val service = Ankka.service
      .register(ShoppingCartEntity.descriptor)
    // docs:end register-entity
    valid(service)
  }

  test("a view is registered beside the projection runtime that writes its rows") {
    // docs:start register-view
    val service = Ankka.service
      .register(ShoppingCartEntity.descriptor)
      .register(CartRows.descriptor)
      .withExtension(ProjectionRuntime())
    // docs:end register-view
    valid(service)
  }

  test("a consumer that publishes is registered beside a projection runtime with a broker") {
    // docs:start register-consumer
    val service = Ankka.service
      .register(ShoppingCartEntity.descriptor)
      .register(CheckoutNotifier.descriptor)
      .withExtension(ProjectionRuntime.withKafka("localhost:9092"))
    // docs:end register-consumer
    valid(service)
  }

  test("a key value entity is registered like any other, and called through the client") {
    // docs:start register-key-value
    val service = Ankka.service.register(CheckoutLog.descriptor)
    // docs:end register-key-value
    valid(service)

    val testKit = AnkkaTestKit.start(CheckoutLog.descriptor)
    try
      val componentClient = testKit.componentClient
      // docs:start call-key-value
      val record =
        componentClient.forKeyValueEntity(EntityId("c1")).call(CheckoutLog.get).invoke()
      // docs:end call-key-value
      assertEquals(record.cartId, "c1")
      assert(!record.notified)
    finally testKit.stop()
  }
