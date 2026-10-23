package com.thinkmorestupidless.ankka.javasdk.client;

/** No address is known for the service: the name is not one this service can reach. */
public final class ServiceUnresolvable extends ServiceUnreachable {

  private static final long serialVersionUID = 1L;

  public ServiceUnresolvable(String service, String message, Throwable cause) {
    super(service, message, cause);
  }
}
