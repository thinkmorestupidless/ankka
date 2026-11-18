package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Refuses;
import com.thinkmorestupidless.ankka.javasdk.annotations.Summary;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.Settings;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** The Java samples of the OpenAPI page: a service that serves its document, and a kit held to it. */
public final class OpenApiDocs {

  private OpenApiDocs() {}

  /** A greeting by name; a name nobody has is refused. */
  public static class Greetings extends HttpEndpoint {
    @Override
    protected Acl acl() {
      return Acl.allowAll();
    }

    @Get("/greetings/{name}")
    @Summary("A greeting for one name")
    @Refuses(status = 404, reason = "nobody has that name")
    public String greet(String name) {
      if (name.equals("nobody")) throw new ProblemException(Problem.notFound("nobody"));
      return "hello, " + name;
    }
  }

  // docs:start served
  public static ServiceBuilder service() {
    return Ankka.service("greetings")
        .withEndpoint(Greetings.class)
        // GET /_ankka/openapi.json, answered to the callers this ACL admits.
        .withOpenApi(Acl.allowAll());
  }
  // docs:end served

  /** The served document's body. */
  public static String document() throws Exception {
    try (var kit = AnkkaTestKit.start(service())) {
      return get(kit, "/_ankka/openapi.json").body();
    }
  }

  // docs:start held
  /** Two calls of a test, each answer checked against the service's document. */
  public static void held() throws Exception {
    // close() fails, listing every answer the document does not allow.
    try (var kit = AnkkaTestKit.start(service(), Settings.defaults().heldToDocument())) {
      get(kit, "/greetings/ada");
      get(kit, "/greetings/nobody"); // 404, which the route's @Refuses names
    }
  }
  // docs:end held

  private static HttpResponse<String> get(AnkkaTestKit kit, String path) throws Exception {
    return kit.httpClient()
        .send(
            HttpRequest.newBuilder(kit.http().resolve(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
