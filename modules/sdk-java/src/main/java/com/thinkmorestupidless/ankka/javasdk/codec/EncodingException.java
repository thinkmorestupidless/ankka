package com.thinkmorestupidless.ankka.javasdk.codec;

/**
 * A value the encoding cannot write, bytes it cannot read, or a type it has no form for. {@link
 * #path()} names where: {@code ShoppingCart.items[2].discount}.
 */
public final class EncodingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String path;

  public EncodingException(String path, String message) {
    super(path.isEmpty() ? message : path + ": " + message);
    this.path = path;
  }

  public EncodingException(String path, String message, Throwable cause) {
    super(path.isEmpty() ? message : path + ": " + message, cause);
    this.path = path;
  }

  /** Where in the value the problem is; empty for the value as a whole. */
  public String path() {
    return path;
  }
}
