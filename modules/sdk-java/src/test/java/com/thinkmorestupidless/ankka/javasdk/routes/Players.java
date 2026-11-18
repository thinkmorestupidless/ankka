package com.thinkmorestupidless.ankka.javasdk.routes;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.*;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Response;
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar;
import java.util.List;
import java.util.Optional;

/** Every OpenAPI annotation, on the class and on its handlers (feature 067). */
@Tag("player")
@Refuses(status = 403, reason = "wrong brand")
@RefusalShape(Players.Refusal.class)
public class Players extends HttpEndpoint implements RouteRegistrar {

  public record Profile(String name, Optional<String> nick) {}

  public record Messages(List<String> items, int total) {}

  public record Refusal(String why) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/players/{playerId}/messages")
  @Summary("A player's messages")
  @Refuses(status = 404, reason = "not found")
  @ReadsQuery(name = "currency")
  public Messages messages(String playerId, @QueryParam("page") Optional<Integer> page) {
    return new Messages(List.of(), 0);
  }

  @Put("/players/{playerId}")
  public Done save(String playerId, @Body Profile profile) {
    return Done.done();
  }

  @Delete("/players/{n}")
  public void remove(long n) {}

  @Get("/players/{playerId}/raw")
  public Response raw(String playerId) {
    return Response.ok("x");
  }

  @Get("/players/{playerId}/card")
  @Answers(schema = "{\"type\":\"string\"}")
  @Answers(status = 410)
  public Response card(String playerId) {
    return Response.ok("x");
  }

  @Override
  public List<Declaration> routes() {
    return List.of(
        new Declaration("POST", "/players/{playerId}/notes", Profile.class, (r, p, b) -> null));
  }
}
