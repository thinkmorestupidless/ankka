package com.thinkmorestupidless.ankka.javasdk;

import com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeys;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ViewClient;
import com.thinkmorestupidless.ankka.javasdk.grpc.GrpcClients;
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage;
import com.thinkmorestupidless.ankka.javasdk.timer.Timers;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** A running service. Closing it stops it. */
public interface AnkkaService extends AutoCloseable {

  /** Calls to this service's components. */
  ComponentClient componentClient();

  /** Queries of this service's views. */
  ViewClient viewClient();

  /** The service's timers; refused when it registers no timed action. */
  Timers timers();

  /** Channels to other services' gRPC; refused unless the service asked for them. */
  GrpcClients grpcClients();

  /** The service's client keys, for the callers of its MCP endpoints. */
  ClientKeys clientKeys();

  /** The service's own secrets. */
  SecretStore secrets();

  /** The service's buckets. */
  ObjectStorage storage();

  /** Who the service is; refused when its name could not be read. */
  ServiceIdentity identity();

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
