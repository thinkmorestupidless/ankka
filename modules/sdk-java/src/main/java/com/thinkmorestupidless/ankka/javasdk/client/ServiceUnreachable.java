package com.thinkmorestupidless.ankka.javasdk.client;

/**
 * Another service could not be reached, was not who it should be, or did not answer: one of
 * {@link ServiceUnresolvable}, {@link ServiceIdentityMismatch} and {@link ServiceUnanswered}.
 */
public sealed class ServiceUnreachable extends RuntimeException
    permits ServiceUnresolvable, ServiceIdentityMismatch, ServiceUnanswered {

  private static final long serialVersionUID = 1L;

  private final String service;

  public ServiceUnreachable(String service, String message, Throwable cause) {
    super(message, cause);
    this.service = service;
  }

  public String service() {
    return service;
  }
}
