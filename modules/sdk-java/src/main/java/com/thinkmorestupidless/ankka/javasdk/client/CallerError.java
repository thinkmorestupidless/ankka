package com.thinkmorestupidless.ankka.javasdk.client;

/**
 * A call that names no handler: a lambda where a method reference belongs, a method with no
 * {@code @Command} or {@code @Query}, a component that is not registered, an unknown wire name.
 * The message names the call site's class and method.
 */
public final class CallerError extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CallerError(String message) {
    super(message);
  }
}
