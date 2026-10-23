package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ViewClient;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.ServiceKit;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The whole service, started against a throwaway database of its own and ready before it is
 * returned: its endpoints on {@code 127.0.0.1} and a free port, its components called as another
 * component would call them. {@link #restartService()} drops everything held in memory, so a test
 * proves what was recorded rather than what was cached.
 *
 * <pre>{@code
 * try (var kit = AnkkaTestKit.start(Main.service())) {
 *   kit.componentClient().forEventSourcedEntity("c1").call(ShoppingCart::addItem).invoke(item);
 * }
 * }</pre>
 */
public final class AnkkaTestKit implements AutoCloseable {

  private final ServiceKit kit;

  private AnkkaTestKit(ServiceKit kit) {
    this.kit = kit;
  }

  public static AnkkaTestKit start(ServiceBuilder service) {
    return start(service, Settings.defaults());
  }

  public static AnkkaTestKit start(ServiceBuilder service, Settings settings) {
    return new AnkkaTestKit(ServiceKit.start(service, settings));
  }

  public ComponentClient componentClient() {
    return kit.componentClient();
  }

  /** Queries of the service's views. */
  public ViewClient viewClient() {
    return kit.viewClient();
  }

  /** Where the service's endpoints are served. Throws when it serves none. */
  public URI http() {
    return kit.http();
  }

  /** A client for {@link #http()}. */
  public HttpClient httpClient() {
    return kit.httpClient();
  }

  /** Stops the service and starts it again on the same database. */
  public void restartService() {
    kit.restartService();
  }

  /**
   * Runs {@code assertion} until it passes or {@code within} has passed, then fails with its last
   * failure. Retry on the value that changes; assert the identity that does not.
   */
  public void eventually(Duration within, Runnable assertion) {
    kit.eventually(within, assertion);
  }

  /** The stand-in named in {@link Settings#withScriptedService(String)}. */
  public ScriptedService service(String name) {
    return kit.service(name);
  }

  @Override
  public void close() {
    kit.close();
  }
}
