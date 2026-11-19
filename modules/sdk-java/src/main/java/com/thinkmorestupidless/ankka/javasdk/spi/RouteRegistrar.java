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
  record Declaration(
      String verb,
      String template,
      @Nullable Type body,
      Handler handler,
      List<Description> described) {

    public Declaration {
      described = List.copyOf(described);
    }

    /** A route described by nothing but its types. */
    public Declaration(String verb, String template, @Nullable Type body, Handler handler) {
      this(verb, template, body, handler, List.of());
    }
  }

  /**
   * What a scope of routes declared in code says of them in the service's OpenAPI document (feature
   * 067), as Scala's {@code described} and Java's annotations say it. A declaration carries the
   * scopes it was made in, outermost first; one scope is one object, shared by every declaration
   * made inside it, so a summary, a body or an answer given to a scope of several routes is refused.
   */
  record Description(
      List<String> summaries,
      List<Shape> bodies,
      List<Answer> answers,
      List<Refusal> refusals,
      List<Shape> refusalShapes,
      List<Query> queries,
      List<String> tags) {

    public Description {
      summaries = List.copyOf(summaries);
      bodies = List.copyOf(bodies);
      answers = List.copyOf(answers);
      refusals = List.copyOf(refusals);
      refusalShapes = List.copyOf(refusalShapes);
      queries = List.copyOf(queries);
      tags = List.copyOf(tags);
    }
  }

  /** A shape: a type the codec writes, or JSON Schema as text. Exactly one is given. */
  record Shape(@Nullable Type type, @Nullable String schema) {}

  /** An answer by status; no shape is no body. */
  record Answer(int status, @Nullable Shape shape) {}

  /** A refusal: its status, why, and its own shape when it has one. */
  record Refusal(int status, String reason, @Nullable Shape shape) {}

  /** A query parameter the handler reads, as its type, and whether it must be sent. */
  record Query(String name, Class<?> type, boolean required) {}

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
