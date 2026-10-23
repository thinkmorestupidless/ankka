package com.thinkmorestupidless.ankka.javasdk;

import com.thinkmorestupidless.ankka.javasdk.bridge.JavaServiceBuilder;

/**
 * Where a service begins:
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   Ankka.service("shop")
 *       .register(ShoppingCart.class)
 *       .withEndpoint(CartEndpoint.class)
 *       .start()
 *       .awaitReady();
 * }
 * }</pre>
 *
 * Configuration comes from the same {@code ANKKA_*} variables a Scala service reads.
 */
public final class Ankka {

  private Ankka() {}

  /** A service definition with nothing registered; {@code name} is how its logs name it. */
  public static ServiceBuilder service(String name) {
    return new JavaServiceBuilder(name);
  }
}
