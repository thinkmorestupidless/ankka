package com.thinkmorestupidless.ankka.javasdk.http;

/** A frame sent on a socket that is already closed; {@link #reason} says how it closed. */
public final class SocketClosed extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String reason;

  public SocketClosed(String reason) {
    super("the socket is closed (" + reason + ")");
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
