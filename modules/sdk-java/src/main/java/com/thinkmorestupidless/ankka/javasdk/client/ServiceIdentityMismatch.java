package com.thinkmorestupidless.ankka.javasdk.client;

/** The service answered with an identity other than the one it was called by. */
public final class ServiceIdentityMismatch extends ServiceUnreachable {

  private static final long serialVersionUID = 1L;

  public ServiceIdentityMismatch(String service, String message, Throwable cause) {
    super(service, message, cause);
  }
}
