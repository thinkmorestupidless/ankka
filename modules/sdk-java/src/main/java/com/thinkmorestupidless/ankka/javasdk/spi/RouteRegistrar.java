package com.thinkmorestupidless.ankka.javasdk.spi;

import com.thinkmorestupidless.ankka.javasdk.http.Request;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An endpoint that declares routes in code as well as by annotation: the Kotlin SDK's {@code
 * routes { get("/carts/{id}") { ... } }}. The SDK builds the endpoint once at start, asks it for
 * its routes, and serves each beside the annotated ones under the endpoint's ACL. A route declared
 * twice, in code or by annotation, stops the start.
 */
public interface RouteRegistrar {

  /** The routes this endpoint declares in code. */
  List<Declaration> routes();

  /**
   * One route: its verb ({@code GET}, {@code POST}, {@code PUT}, {@code DELETE} or {@code PATCH}),
   * its path template, the type its body is read as ({@code null} for none), and its handler.
   */
  record Declaration(String verb, String template, @Nullable Type body, Handler handler) {}

  /**
   * Answers one request. The reply is encoded as an annotated route's is: a record or other value
   * by its serializer, a {@code Response} as it is, {@code null} or {@code Done} as no content.
   */
  @FunctionalInterface
  interface Handler {
    @Nullable Object handle(Request request, Map<String, String> path, @Nullable Object body)
        throws Exception;
  }
}
