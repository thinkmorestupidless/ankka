package com.thinkmorestupidless.ankka.javasdk.routes.broken;

import com.thinkmorestupidless.ankka.javasdk.annotations.*;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import java.util.Optional;

/** A query parameter named twice, and a body shape named two ways. */
public class ReadsBound extends HttpEndpoint {
  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/x")
  @ReadsQuery(name = "page")
  public String m(@QueryParam("page") Optional<Integer> page) {
    return "";
  }

  @Post("/y")
  @BodyShape(value = String.class, schema = "{}")
  public String n(@Body String text) {
    return text;
  }
}
