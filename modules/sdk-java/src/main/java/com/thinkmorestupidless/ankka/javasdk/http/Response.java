package com.thinkmorestupidless.ankka.javasdk.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A response with a status and headers of the handler's choosing. A handler that returns a plain
 * value answers 200 with it; one that returns {@code Done} answers 204.
 */
public final class Response {

  private final int status;
  private final @Nullable Object body;
  private final Optional<String> contentType;
  private final List<Map.Entry<String, String>> headers;

  private Response(
      int status,
      @Nullable Object body,
      Optional<String> contentType,
      List<Map.Entry<String, String>> headers) {
    this.status = status;
    this.body = body;
    this.contentType = contentType;
    this.headers = List.copyOf(headers);
  }

  /** 200 with {@code body}, written by its type's serializer. */
  public static Response ok(Object body) {
    return new Response(200, body, Optional.empty(), List.of());
  }

  /** 201 with {@code body}. */
  public static Response created(Object body) {
    return new Response(201, body, Optional.empty(), List.of());
  }

  /** 204, no body. */
  public static Response noContent() {
    return new Response(204, null, Optional.empty(), List.of());
  }

  /** A status and no body. */
  public static Response status(int status) {
    return new Response(status, null, Optional.empty(), List.of());
  }

  /** 200 with an HTML page. */
  public static Response html(String html) {
    return new Response(200, html, Optional.of("text/html; charset=UTF-8"), List.of());
  }

  /** 200 with bytes of the given content type. */
  public static Response bytes(byte[] bytes, String contentType) {
    return new Response(200, bytes.clone(), Optional.of(contentType), List.of());
  }

  /** 303 to {@code location}. */
  public static Response redirect(String location) {
    return status(303).header("Location", location);
  }

  /** The problem's status, with its detail. */
  public static Response problem(Problem problem) {
    return new Response(problem.status(), problem, Optional.empty(), List.of());
  }

  /** This response with another status. */
  public Response withStatus(int status) {
    return new Response(status, body, contentType, headers);
  }

  /** This response with one more header. */
  public Response header(String name, String value) {
    List<Map.Entry<String, String>> more = new ArrayList<>(headers);
    more.add(Map.entry(name, value));
    return new Response(status, body, contentType, more);
  }

  public int statusCode() {
    return status;
  }

  public Optional<Object> body() {
    return Optional.ofNullable(body);
  }

  public Optional<String> contentType() {
    return contentType;
  }

  public List<Map.Entry<String, String>> headers() {
    return headers;
  }
}
