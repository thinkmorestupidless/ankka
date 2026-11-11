package shoppingcart

import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService}
import shoppingcart.application.ShoppingCartEntity

/**
 * The name a service is listed under in the local console. Compiled with the tests and never
 * started: a test runs a service through the testkit, and this is the call a service's own main
 * makes.
 */
object LocalConsoleName:
  def cart(): AnkkaService =
    // docs:start named
    Ankka.service.register(ShoppingCartEntity.descriptor).start(name = "cart")
    // docs:end named
