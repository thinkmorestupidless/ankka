package com.thinkmorestupidless.ankka.javasdk.client;

/** Calls to other services, by name. Offered to endpoints; never to an entity. */
public interface ServiceClients {

  /** A service of this project. */
  ServiceClient of(String service);

  /** A service of another project, which must have granted this one. */
  ServiceClient of(String project, String service);
}
