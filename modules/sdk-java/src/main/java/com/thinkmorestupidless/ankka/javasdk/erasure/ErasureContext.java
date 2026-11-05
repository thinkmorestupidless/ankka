package com.thinkmorestupidless.ankka.javasdk.erasure;

import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage;

/**
 * What an erasure handler is given: the service's clients and its buckets, as a timed action's
 * are, and which application of an erasure this run is.
 */
public interface ErasureContext {

  /** The service's own components. */
  ComponentClient componentClient();

  /** Other services, called as this one. */
  ServiceClients services();

  /** The service's secret store. */
  SecretStore secrets();

  /** The service's buckets, where a handler erases the objects it keeps of the data subject. */
  ObjectStorage storage();

  /** Erasure requests asked as this service, in another project under its grant. */
  Erasures erasures();

  /** The erasure's sequence in the installation's erasure log. */
  long erasureSeq();

  /** Whether this run is a re-run of an erasure already applied. */
  boolean rerun();
}
