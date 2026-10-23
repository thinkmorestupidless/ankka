package com.thinkmorestupidless.ankka.javasdk.client;

/** The service was reached and did not answer in time, or the connection failed. */
public final class ServiceUnanswered extends ServiceUnreachable {

  private static final long serialVersionUID = 1L;

  public ServiceUnanswered(String service, String message, Throwable cause) {
    super(service, message, cause);
  }
}
