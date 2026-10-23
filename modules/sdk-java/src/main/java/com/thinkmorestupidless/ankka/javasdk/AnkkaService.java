package com.thinkmorestupidless.ankka.javasdk;

import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ViewClient;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** A running service. Closing it stops it. */
public interface AnkkaService extends AutoCloseable {

  /** Calls to this service's components. */
  ComponentClient componentClient();

  /** Queries of this service's views. */
  ViewClient viewClient();

  /** Where the endpoints are served, once ready. Throws when the service has no endpoint. */
  URI httpAddress();

  /** Waits until the service has joined its cluster and can answer, at most twenty seconds. */
  void awaitReady();

  void awaitReady(Duration timeout);

  /** Completes when the service has stopped. */
  CompletionStage<Void> whenTerminated();

  /** Begins stopping the service. */
  void terminate();

  /** Stops the service and waits for it to stop. */
  @Override
  void close();
}
