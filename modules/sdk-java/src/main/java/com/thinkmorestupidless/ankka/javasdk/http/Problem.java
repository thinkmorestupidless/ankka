package com.thinkmorestupidless.ankka.javasdk.http;

/**
 * A rejection with a status: what {@code effects().error(problem)} returns and what an endpoint
 * answers. {@code title} is the short reason; {@code detail} says what happened in this case.
 */
public record Problem(int status, String title, String detail) {

  public static Problem badRequest(String detail) {
    return new Problem(400, "Bad Request", detail);
  }

  public static Problem notFound(String detail) {
    return new Problem(404, "Not Found", detail);
  }

  public static Problem conflict(String detail) {
    return new Problem(409, "Conflict", detail);
  }

  public static Problem forbidden(String detail) {
    return new Problem(403, "Forbidden", detail);
  }
}
