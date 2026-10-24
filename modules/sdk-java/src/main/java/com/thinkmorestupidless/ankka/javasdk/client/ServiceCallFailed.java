package com.thinkmorestupidless.ankka.javasdk.client;

/** Another service answered, and not with success. */
public final class ServiceCallFailed extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String service;
  private final int status;
  private final String body;

  public ServiceCallFailed(String service, int status, String body) {
    super(service + " answered " + status + ": " + body);
    this.service = service;
    this.status = status;
    this.body = body;
  }

  public String service() {
    return service;
  }

  public int status() {
    return status;
  }

  public String body() {
    return body;
  }
}
